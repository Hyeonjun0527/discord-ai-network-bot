"""Multi-provider LLM adapters (Ollama, OpenAI, Anthropic) and Ollama model manager."""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import queue
import shutil
import time
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator, Awaitable, Callable, Coroutine, Iterator
from dataclasses import dataclass, field
from typing import Any, TypeVar
from urllib import error, parse, request

from .models import LLMProvider, OllamaModel

logger = logging.getLogger(__name__)

# #20: _with_retry / _with_circuit_breaker 의 반환 타입을 일반화하기 위한 TypeVar.
# 텍스트(str)를 반환하던 generate 경로뿐 아니라, 툴 루프의 payload(dict) 반환에도
# 같은 재시도/서킷 브레이커 헬퍼를 재사용한다(시그니처·동작 불변).
_R = TypeVar("_R")


# ---------------------------------------------------------------------------
# #12: 멀티모달 이미지 입력 타입
# ---------------------------------------------------------------------------

# generate()/_generate_sync 에 넘길 이미지 입력 타입.
# - bytes: 원시 이미지 바이트(이 경우 MIME 은 image/png 기본값으로 가정).
# - (mime, bytes): MIME 타입과 바이트를 함께 지정.
# 리스트로 여러 장을 넘길 수 있다(제공자별 상한은 호출부에서 관리).
ImageInput = bytes | tuple[str, bytes]
# 기본 이미지 MIME. (mime, bytes) 형태가 아니라 raw bytes 만 넘어온 경우 사용한다.
_DEFAULT_IMAGE_MIME = "image/png"


# ---------------------------------------------------------------------------
# #20: 함수/툴 호출 (OpenAI tools / Anthropic tool_use) 경량 에이전트 루프 타입
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class ToolSpec:
    """LLM 에 노출할 단일 툴(함수) 정의 (#20).

    - ``name``: 모델이 호출할 함수 이름(예: "search_messages").
    - ``description``: 모델이 언제 이 툴을 써야 하는지 알 수 있는 설명.
    - ``parameters``: JSON Schema(object) 형태의 파라미터 명세. 제공자별
      규격(OpenAI ``parameters`` / Anthropic ``input_schema``)에 그대로 매핑된다.

    제공자 비종속(provider-agnostic) 표현이며, 각 어댑터가 자신의 와이어 포맷으로
    변환한다. 기본값은 인자 없는 빈 object 스키마다.
    """

    name: str
    description: str
    parameters: dict[str, Any] = field(
        default_factory=lambda: {"type": "object", "properties": {}}
    )


# 툴 실행기: (tool_name, arguments dict) → 결과 문자열(모델에 되돌려줄 관측값).
# bot.py 가 search_messages 같은 실제 동작을 여기에 연결한다. 비동기다.
ToolRunner = Callable[[str, dict[str, Any]], Awaitable[str]]

# 에이전트 루프의 안전 상한(과도한 왕복/비용 폭증 방지). 2~3회면 충분하다.
_DEFAULT_TOOL_ITERATIONS = 3
# 한 번에 모델에 되돌려줄 툴 결과의 최대 길이(컨텍스트 폭증 방지).
_MAX_TOOL_RESULT_CHARS = 4000


async def _run_tool_safely(
    tool_runner: "ToolRunner", name: str, args: dict[str, Any]
) -> str:
    """tool_runner 를 호출하되 실패해도 루프를 깨지 않도록 결과 문자열로 흡수한다 (#20).

    툴 실행 중 예외가 나면 모델에 되돌려줄 수 있는 오류 문자열로 변환한다(루프가
    멈추지 않고 모델이 상황을 인지해 답변할 수 있게 한다). LLMError(제공자 오류)는
    루프 자체를 중단시켜야 하므로 그대로 다시 던진다.
    """
    try:
        return await tool_runner(name, args)
    except LLMError:
        raise
    except Exception as exc:  # noqa: BLE001 — 툴 실패는 모델에 관측값으로 전달
        logger.warning("툴 실행 실패 (name=%s): %s", name, exc)
        return f"(tool '{name}' failed: {exc})"


def _normalize_image(image: ImageInput) -> tuple[str, bytes]:
    """이미지 입력을 (mime, bytes) 튜플로 정규화한다 (#12).

    raw bytes 만 넘어오면 MIME 을 기본값(image/png)으로 가정한다. 빈 MIME 이
    들어오면 기본값으로 대체한다(제공자가 빈 MIME 을 거부하지 않도록).
    """
    if isinstance(image, tuple):
        mime, data = image
        return (mime or _DEFAULT_IMAGE_MIME, data)
    return (_DEFAULT_IMAGE_MIME, image)


def _encode_image_b64(image: ImageInput) -> tuple[str, str]:
    """이미지 입력을 (mime, base64-문자열) 로 인코딩한다 (#12)."""
    mime, data = _normalize_image(image)
    return mime, base64.b64encode(data).decode("ascii")


# ---------------------------------------------------------------------------
# #17: 토큰 사용량 집계용 경량 컨테이너
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class TokenUsage:
    """단일 LLM 응답의 토큰 사용량 (#17).

    각 제공자의 usage 메타데이터를 통일된 형태로 보관한다. 파싱 실패/미제공 시
    (0, 0) 으로 둔다. ``generate()`` 의 반환(텍스트)에는 영향을 주지 않으며,
    클라이언트 인스턴스의 ``last_usage`` 속성으로만 노출된다(부수효과).
    """

    prompt_tokens: int = 0
    completion_tokens: int = 0


def _coerce_token_count(value: Any) -> int:
    """usage 메타데이터의 토큰 수 값을 안전하게 정수로 변환한다 (#17).

    None/비숫자/음수는 0 으로 보정한다(과금/통계가 깨지지 않도록).
    """
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return 0
    count = int(value)
    return count if count > 0 else 0


def _parse_openai_usage(payload: dict[str, Any]) -> TokenUsage:
    """OpenAI Chat Completions 응답의 usage(prompt/completion_tokens) 파싱 (#17)."""
    usage = payload.get("usage")
    if not isinstance(usage, dict):
        return TokenUsage()
    return TokenUsage(
        prompt_tokens=_coerce_token_count(usage.get("prompt_tokens")),
        completion_tokens=_coerce_token_count(usage.get("completion_tokens")),
    )


def _parse_anthropic_usage(payload: dict[str, Any]) -> TokenUsage:
    """Anthropic Messages 응답의 usage(input/output_tokens) 파싱 (#17)."""
    usage = payload.get("usage")
    if not isinstance(usage, dict):
        return TokenUsage()
    return TokenUsage(
        prompt_tokens=_coerce_token_count(usage.get("input_tokens")),
        completion_tokens=_coerce_token_count(usage.get("output_tokens")),
    )


def _parse_gemini_usage(payload: dict[str, Any]) -> TokenUsage:
    """Gemini generateContent 응답의 usageMetadata 파싱 (#17).

    ``promptTokenCount`` 이 입력, ``candidatesTokenCount`` 가 출력이다.
    """
    meta = payload.get("usageMetadata")
    if not isinstance(meta, dict):
        return TokenUsage()
    return TokenUsage(
        prompt_tokens=_coerce_token_count(meta.get("promptTokenCount")),
        completion_tokens=_coerce_token_count(meta.get("candidatesTokenCount")),
    )


def _parse_ollama_usage(payload: dict[str, Any]) -> TokenUsage:
    """Ollama /api/generate 응답의 prompt_eval_count/eval_count 파싱 (#17)."""
    return TokenUsage(
        prompt_tokens=_coerce_token_count(payload.get("prompt_eval_count")),
        completion_tokens=_coerce_token_count(payload.get("eval_count")),
    )


# ---------------------------------------------------------------------------
# Exceptions
# ---------------------------------------------------------------------------


class LLMError(RuntimeError):
    """Base class for all LLM provider errors.

    ``status_code``는 HTTP 오류에서 비롯된 경우 해당 상태 코드를 담는다.
    (네트워크/타임아웃/파싱 오류 등 HTTP 응답이 없는 경우 None)
    """

    def __init__(self, *args: Any, status_code: int | None = None) -> None:
        super().__init__(*args)
        self.status_code = status_code


class OllamaError(LLMError):
    """Raised when Ollama cannot produce a usable response."""


class OpenAIError(LLMError):
    """Raised when the OpenAI API returns an error."""


class AnthropicError(LLMError):
    """Raised when the Anthropic API returns an error."""


class GeminiError(LLMError):
    """Raised when the Google Gemini API returns an error."""


class CircuitBreakerOpenError(LLMError):
    """서킷 브레이커가 열려 있어 요청을 빠르게 실패시킬 때 발생."""


# ---------------------------------------------------------------------------
# Retry helper
# ---------------------------------------------------------------------------


def _is_retryable(exc: LLMError) -> bool:
    """재시도 가능한 오류인지 판정한다.

    - 4xx 클라이언트 오류(400/401/403 등)는 재시도해도 동일하게 실패하므로 즉시 실패.
    - 429(Too Many Requests)와 5xx 서버 오류는 일시적일 수 있으므로 재시도 가능.
    - status_code가 없는 오류(네트워크/타임아웃/파싱 등)는 일시적일 수 있으므로 재시도 가능.
    """
    code = exc.status_code
    if code is None:
        return True
    if code == 429:
        return True
    return 500 <= code <= 599


async def _with_retry(
    coro_fn: Callable[[], Coroutine[Any, Any, _R]],
    max_attempts: int = 2,
    delay: float = 1.0,
) -> _R:
    """Run ``coro_fn`` up to ``max_attempts`` times, retrying on retryable LLMError with backoff.

    재시도 불가능한 오류(4xx 클라이언트 오류 등)는 즉시 다시 던진다.
    """
    if max_attempts < 1:
        raise ValueError(f"max_attempts must be >= 1, got {max_attempts}")
    last_exc: LLMError | None = None
    for attempt in range(max_attempts):
        try:
            return await coro_fn()
        except LLMError as exc:
            last_exc = exc
            # 재시도 불가능한 오류는 즉시 실패시킨다.
            if not _is_retryable(exc):
                raise
            if attempt < max_attempts - 1:
                backoff = delay * (2 ** attempt)
                logger.warning(
                    "LLM request failed (attempt %d/%d): %s — retrying in %.1fs",
                    attempt + 1, max_attempts, exc, backoff,
                )
                await asyncio.sleep(backoff)
    assert last_exc is not None
    raise last_exc


# ---------------------------------------------------------------------------
# Circuit breaker
# ---------------------------------------------------------------------------


class CircuitBreaker:
    """Provider 단위의 간단한 서킷 브레이커.

    연속 실패가 ``failure_threshold`` 회에 도달하면 ``reset_timeout`` 초 동안
    빠르게 실패(open 상태)시킨다. reset_timeout 경과 후 한 번 시도를 허용(half-open)하고,
    성공하면 닫힌다(closed). 테스트 결정성을 위해 ``time_fn``을 주입할 수 있다.
    """

    def __init__(
        self,
        *,
        failure_threshold: int = 5,
        reset_timeout: float = 30.0,
        time_fn: Callable[[], float] | None = None,
    ) -> None:
        self.failure_threshold = failure_threshold
        self.reset_timeout = reset_timeout
        self._time_fn = time_fn or time.monotonic
        self._failures = 0
        self._opened_at: float | None = None

    def _is_open(self) -> bool:
        if self._opened_at is None:
            return False
        # reset_timeout이 지났으면 half-open으로 전환되어 한 번의 시도를 허용한다.
        if self._time_fn() - self._opened_at >= self.reset_timeout:
            return False
        return True

    def before_call(self) -> None:
        """요청 직전 호출. open 상태면 빠르게 실패시킨다."""
        if self._is_open():
            raise CircuitBreakerOpenError(
                "일시적으로 요청을 처리할 수 없습니다 (서킷 브레이커 동작 중). "
                "잠시 후 다시 시도해 주세요."
            )

    def record_success(self) -> None:
        self._failures = 0
        self._opened_at = None

    def record_failure(self) -> None:
        self._failures += 1
        if self._failures >= self.failure_threshold:
            self._opened_at = self._time_fn()


async def _with_circuit_breaker(
    breaker: CircuitBreaker | None,
    coro_fn: Callable[[], Coroutine[Any, Any, _R]],
    *,
    max_attempts: int = 2,
    delay: float = 1.0,
) -> _R:
    """서킷 브레이커와 재시도를 결합해 ``coro_fn``을 실행한다.

    ``breaker``가 None이면 기존 ``_with_retry``와 동일하게 동작(백워드 호환).
    """
    if breaker is None:
        return await _with_retry(coro_fn, max_attempts=max_attempts, delay=delay)
    breaker.before_call()
    try:
        result = await _with_retry(coro_fn, max_attempts=max_attempts, delay=delay)
    except LLMError:
        breaker.record_failure()
        raise
    breaker.record_success()
    return result


# ---------------------------------------------------------------------------
# Abstract base
# ---------------------------------------------------------------------------


class BaseLLMClient(ABC):
    """Minimal interface every LLM provider adapter must implement.

    #17: 마지막 ``generate()`` 호출의 토큰 사용량을 ``last_usage`` 로 노출한다.
    응답에 usage 메타데이터가 없으면 (0, 0) 으로 남는다. ``generate()`` 의
    텍스트 반환은 절대 바뀌지 않으므로 비스트리밍 호출부는 그대로 동작한다.
    """

    # 마지막 generate() 호출의 토큰 사용량(부수효과로 갱신). 기본은 (0, 0).
    last_usage: TokenUsage = TokenUsage()

    @abstractmethod
    async def generate(
        self,
        prompt: str,
        *,
        model: str | None = None,
        images: list[ImageInput] | None = None,
    ) -> str:
        """프롬프트(+선택적 이미지)에 대한 텍스트 응답을 반환한다.

        #12: ``images`` 는 keyword-only 이며 기본값은 None 이다. None 이면 기존
        텍스트 전용 경로 그대로다(백워드 호환). bytes 또는 (mime, bytes) 의
        리스트를 넘기면 비전 지원 제공자/모델에 멀티모달 입력으로 전달된다.
        반환 타입(텍스트)은 이미지 유무와 무관하게 항상 str 이다.
        """
        ...

    async def generate_stream(
        self, prompt: str, *, model: str | None = None
    ) -> AsyncIterator[str]:
        """프롬프트에 대한 응답을 점진적(스트리밍)으로 yield 한다 (#16).

        기본 구현은 ``generate()`` 결과 전체를 단일 청크로 한 번 yield 하는
        폴백이다. 실제 스트리밍을 지원하는 어댑터(Ollama/OpenAI 등)는 이 메서드를
        오버라이드해 토큰/델타 단위로 yield 한다. ``generate`` 의 시그니처·반환은
        절대 바뀌지 않으므로(백워드 호환), 비스트리밍 호출부는 그대로 동작한다.
        """
        yield await self.generate(prompt, model=model)

    async def generate_with_tools(
        self,
        prompt: str,
        *,
        tools: list[ToolSpec],
        tool_runner: ToolRunner,
        model: str | None = None,
        max_iterations: int = _DEFAULT_TOOL_ITERATIONS,
    ) -> str:
        """툴(함수) 호출을 지원하는 경량 에이전트 루프로 최종 텍스트를 반환한다 (#20).

        기본 구현은 툴을 사용하지 않고 ``generate()`` 로 폴백한다(미지원 제공자 —
        Ollama/Gemini 등). 실제 툴 루프를 지원하는 어댑터(OpenAI/Anthropic)는 이
        메서드를 오버라이드해, 모델이 ``tools`` 중 하나를 호출하면 ``tool_runner``
        로 실행하고 그 결과를 다시 모델에 돌려주는 식으로 최대 ``max_iterations``
        회 반복한 뒤 최종 텍스트를 만든다.

        ``generate()`` 의 시그니처·반환(텍스트)은 절대 바뀌지 않으므로, 기존
        호출부·테스트는 영향을 받지 않는다.
        """
        return await self.generate(prompt, model=model)


# ---------------------------------------------------------------------------
# Streaming 보조: 블로킹 동기 제너레이터(urllib SSE 등)를 비동기 이터레이터로 변환
# ---------------------------------------------------------------------------

# 스트림 종료를 알리는 센티넬. (큐에 흘려보내 워커 스레드 종료를 신호한다)
_STREAM_DONE = object()


async def _iter_in_thread(
    make_iter: Callable[[], Iterator[str]],
) -> AsyncIterator[str]:
    """블로킹 동기 제너레이터를 별도 스레드에서 돌려 비동기로 청크를 yield 한다.

    urllib 기반 스트리밍은 ``response.readline()`` 등이 블로킹이므로 이벤트 루프를
    막지 않도록 워커 스레드에서 실행하고, ``queue.Queue`` 로 청크를 메인 루프에
    전달한다. 워커에서 발생한 예외는 큐를 통해 전달해 호출부에서 다시 던진다.
    """
    q: queue.Queue[Any] = queue.Queue(maxsize=64)

    def _worker() -> None:
        try:
            for chunk in make_iter():
                q.put(chunk)
        except BaseException as exc:  # noqa: BLE001 — 예외를 메인 루프로 전달
            q.put(exc)
        finally:
            q.put(_STREAM_DONE)

    worker = asyncio.create_task(asyncio.to_thread(_worker))
    try:
        while True:
            item = await asyncio.to_thread(q.get)
            if item is _STREAM_DONE:
                break
            if isinstance(item, BaseException):
                raise item
            yield item
    finally:
        await worker


# ---------------------------------------------------------------------------
# Ollama
# ---------------------------------------------------------------------------


def parse_generate_response(payload: dict[str, Any]) -> str:
    """Extract text from an Ollama /api/generate JSON payload."""
    if "error" in payload and payload["error"]:
        raise OllamaError(str(payload["error"]))
    response = payload.get("response")
    if not isinstance(response, str):
        raise OllamaError("Ollama response did not contain a text response")
    return response.strip()


class OllamaClient(BaseLLMClient):
    """Async wrapper around Ollama's blocking HTTP API."""

    def __init__(
        self,
        *,
        base_url: str,
        default_model: str,
        timeout_seconds: int = 60,
        keep_alive: str = "10m",
        temperature: float = 0.2,
        num_ctx: int = 8192,
        circuit_breaker: CircuitBreaker | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.default_model = default_model
        self.timeout_seconds = timeout_seconds
        self.keep_alive = keep_alive
        self.temperature = temperature
        self.num_ctx = num_ctx
        self.circuit_breaker = circuit_breaker
        # #17: 마지막 generate() 호출의 토큰 사용량(부수효과로 갱신).
        self.last_usage = TokenUsage()

    async def generate(
        self,
        prompt: str,
        *,
        model: str | None = None,
        images: list[ImageInput] | None = None,
    ) -> str:
        resolved_model = model or self.default_model
        return await _with_circuit_breaker(
            self.circuit_breaker,
            lambda: asyncio.to_thread(
                self._generate_sync, prompt, resolved_model, images
            ),
        )

    def _generate_sync(
        self, prompt: str, model: str, images: list[ImageInput] | None = None
    ) -> str:
        payload_body: dict[str, Any] = {
            "model": model,
            "prompt": prompt,
            "stream": False,
            "keep_alive": self.keep_alive,
            "options": {"temperature": self.temperature, "num_ctx": self.num_ctx},
        }
        # #12: Ollama 는 /api/generate 에서 images=[base64, ...] 로 멀티모달 입력을
        # 받는다. 이미지가 없으면 필드를 추가하지 않아 기존 텍스트 경로 그대로다.
        if images:
            payload_body["images"] = [_encode_image_b64(img)[1] for img in images]
        body = json.dumps(payload_body).encode("utf-8")
        req = request.Request(
            f"{self.base_url}/api/generate",
            data=body,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.timeout_seconds) as response:  # noqa: S310
                payload = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            logger.debug("Ollama HTTP %s error body: %s", exc.code, detail)
            raise OllamaError(f"Ollama 요청 실패 (HTTP {exc.code})", status_code=exc.code) from exc
        except error.URLError as exc:
            raise OllamaError(
                "Ollama가 실행 중이지 않습니다. 터미널에서 `ollama serve`를 실행해 주세요."
            ) from exc
        except TimeoutError as exc:
            raise OllamaError(
                "응답 시간이 초과됐습니다. 더 작은 모델을 사용하거나 `/settings`에서 제공자를 변경해보세요."
            ) from exc
        except json.JSONDecodeError as exc:
            raise OllamaError("Ollama returned invalid JSON") from exc
        # #17: Ollama 는 prompt_eval_count(입력)/eval_count(출력) 를 응답에 담는다.
        self.last_usage = _parse_ollama_usage(payload)
        return parse_generate_response(payload)

    async def generate_stream(
        self, prompt: str, *, model: str | None = None
    ) -> AsyncIterator[str]:
        """Ollama /api/generate 를 stream=true 로 호출해 토큰 단위로 yield 한다 (#16).

        Ollama 는 한 줄에 하나씩 JSON 객체(``{"response": "...", "done": false}``)를
        흘려보낸다. 서킷 브레이커/재시도 로직은 비스트리밍 ``generate`` 에 한정하고,
        스트림 경로는 단순 yield 만 한다(부분 출력 후 실패해도 호출부가 폴백 가능).
        """
        resolved_model = model or self.default_model
        async for chunk in _iter_in_thread(
            lambda: self._stream_sync(prompt, resolved_model)
        ):
            yield chunk

    def _stream_sync(self, prompt: str, model: str) -> Iterator[str]:
        body = json.dumps(
            {
                "model": model,
                "prompt": prompt,
                "stream": True,
                "keep_alive": self.keep_alive,
                "options": {"temperature": self.temperature, "num_ctx": self.num_ctx},
            }
        ).encode("utf-8")
        req = request.Request(
            f"{self.base_url}/api/generate",
            data=body,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.timeout_seconds) as response:  # noqa: S310
                for raw_line in response:
                    line = raw_line.decode("utf-8", errors="replace").strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if obj.get("error"):
                        raise OllamaError(str(obj["error"]))
                    piece = obj.get("response")
                    if isinstance(piece, str) and piece:
                        yield piece
                    if obj.get("done"):
                        break
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            logger.debug("Ollama stream HTTP %s error body: %s", exc.code, detail)
            raise OllamaError(
                f"Ollama 요청 실패 (HTTP {exc.code})", status_code=exc.code
            ) from exc
        except error.URLError as exc:
            raise OllamaError(
                "Ollama가 실행 중이지 않습니다. 터미널에서 `ollama serve`를 실행해 주세요."
            ) from exc
        except TimeoutError as exc:
            raise OllamaError(
                "응답 시간이 초과됐습니다. 더 작은 모델을 사용하거나 `/settings`에서 제공자를 변경해보세요."
            ) from exc


# ---------------------------------------------------------------------------
# OpenAI
# ---------------------------------------------------------------------------


class OpenAIClient(BaseLLMClient):
    """Async wrapper around OpenAI Chat Completions API (raw HTTP, no SDK)."""

    _BASE = "https://api.openai.com/v1"

    def __init__(
        self,
        *,
        api_key: str,
        default_model: str = "gpt-4o-mini",
        timeout_seconds: int = 60,
        temperature: float = 0.2,
        system_prompt: str = "You are a helpful Discord bot assistant.",
        circuit_breaker: CircuitBreaker | None = None,
    ) -> None:
        self.api_key = api_key
        self.default_model = default_model
        self.timeout_seconds = timeout_seconds
        self.temperature = temperature
        self.system_prompt = system_prompt
        self.circuit_breaker = circuit_breaker
        # #17: 마지막 generate() 호출의 토큰 사용량(부수효과로 갱신).
        self.last_usage = TokenUsage()

    async def generate(
        self,
        prompt: str,
        *,
        model: str | None = None,
        images: list[ImageInput] | None = None,
    ) -> str:
        resolved_model = model or self.default_model
        return await _with_circuit_breaker(
            self.circuit_breaker,
            lambda: asyncio.to_thread(
                self._generate_sync, prompt, resolved_model, images
            ),
        )

    def _build_user_content(
        self, prompt: str, images: list[ImageInput] | None
    ) -> Any:
        """user 메시지 content 를 구성한다 (#12).

        이미지가 없으면 기존처럼 평문 문자열을 반환한다(백워드 호환). 이미지가
        있으면 OpenAI 멀티모달 규격의 content 배열(텍스트 + image_url data URI)을
        반환한다.
        """
        if not images:
            return prompt
        parts: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
        for img in images:
            mime, b64 = _encode_image_b64(img)
            parts.append(
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:{mime};base64,{b64}"},
                }
            )
        return parts

    def _generate_sync(
        self, prompt: str, model: str, images: list[ImageInput] | None = None
    ) -> str:
        body = json.dumps(
            {
                "model": model,
                "messages": [
                    {"role": "system", "content": self.system_prompt},
                    {"role": "user", "content": self._build_user_content(prompt, images)},
                ],
                "temperature": self.temperature,
            }
        ).encode("utf-8")
        req = request.Request(
            f"{self._BASE}/chat/completions",
            data=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.timeout_seconds) as response:  # noqa: S310
                payload = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            logger.debug("OpenAI HTTP %s error body: %s", exc.code, detail)
            raise OpenAIError(
                f"OpenAI API 요청 실패 (HTTP {exc.code})", status_code=exc.code
            ) from exc
        except error.URLError as exc:
            raise OpenAIError(f"Cannot reach OpenAI: {exc.reason}") from exc
        except TimeoutError as exc:
            raise OpenAIError(
                "응답 시간이 초과됐습니다. 더 작은 모델을 사용하거나 `/settings`에서 제공자를 변경해보세요."
            ) from exc
        except json.JSONDecodeError as exc:
            raise OpenAIError("OpenAI returned invalid JSON") from exc
        try:
            content: str = payload["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            logger.debug("Unexpected OpenAI response shape: %s", payload)
            raise OpenAIError("OpenAI 응답 형식을 해석할 수 없습니다.") from exc
        # #17: 응답 파싱 성공 후에만 usage 를 기록한다.
        self.last_usage = _parse_openai_usage(payload)
        return content.strip()

    async def generate_stream(
        self, prompt: str, *, model: str | None = None
    ) -> AsyncIterator[str]:
        """OpenAI Chat Completions 를 stream=true(SSE)로 호출해 델타를 yield 한다 (#16).

        SSE 는 ``data: {json}`` 줄들의 나열이며 마지막은 ``data: [DONE]`` 이다.
        각 청크의 ``choices[0].delta.content`` 를 누적 없이 그대로 흘려보낸다.
        """
        resolved_model = model or self.default_model
        async for chunk in _iter_in_thread(
            lambda: self._stream_sync(prompt, resolved_model)
        ):
            yield chunk

    def _stream_sync(self, prompt: str, model: str) -> Iterator[str]:
        body = json.dumps(
            {
                "model": model,
                "messages": [
                    {"role": "system", "content": self.system_prompt},
                    {"role": "user", "content": prompt},
                ],
                "temperature": self.temperature,
                "stream": True,
            }
        ).encode("utf-8")
        req = request.Request(
            f"{self._BASE}/chat/completions",
            data=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
                "Accept": "text/event-stream",
            },
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.timeout_seconds) as response:  # noqa: S310
                for raw_line in response:
                    line = raw_line.decode("utf-8", errors="replace").strip()
                    if not line or not line.startswith("data:"):
                        continue
                    data = line[len("data:") :].strip()
                    if data == "[DONE]":
                        break
                    try:
                        obj = json.loads(data)
                    except json.JSONDecodeError:
                        continue
                    try:
                        delta = obj["choices"][0]["delta"].get("content")
                    except (KeyError, IndexError, TypeError):
                        continue
                    if isinstance(delta, str) and delta:
                        yield delta
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            logger.debug("OpenAI stream HTTP %s error body: %s", exc.code, detail)
            raise OpenAIError(
                f"OpenAI API 요청 실패 (HTTP {exc.code})", status_code=exc.code
            ) from exc
        except error.URLError as exc:
            raise OpenAIError(f"Cannot reach OpenAI: {exc.reason}") from exc
        except TimeoutError as exc:
            raise OpenAIError(
                "응답 시간이 초과됐습니다. 더 작은 모델을 사용하거나 `/settings`에서 제공자를 변경해보세요."
            ) from exc

    # ------------------------------------------------------------------
    # #20: 함수/툴 호출 (OpenAI tools + tool_calls) 경량 에이전트 루프
    # ------------------------------------------------------------------

    @staticmethod
    def _tools_to_openai(tools: list[ToolSpec]) -> list[dict[str, Any]]:
        """ToolSpec 목록을 OpenAI tools(function) 와이어 포맷으로 변환한다 (#20)."""
        return [
            {
                "type": "function",
                "function": {
                    "name": t.name,
                    "description": t.description,
                    "parameters": t.parameters,
                },
            }
            for t in tools
        ]

    def _chat_sync(
        self, messages: list[dict[str, Any]], model: str, tools: list[dict[str, Any]]
    ) -> dict[str, Any]:
        """messages + tools 로 한 번의 Chat Completions 호출을 수행해 payload 를 반환한다 (#20).

        ``_generate_sync`` 와 동일한 HTTP/에러 처리지만, content 만 뽑지 않고 전체
        payload(choices[0].message — tool_calls 포함)를 돌려준다. last_usage 도
        호출마다 갱신한다(여러 왕복의 합산은 호출부가 관리할 수 있다).
        """
        body_dict: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "temperature": self.temperature,
        }
        if tools:
            body_dict["tools"] = tools
        body = json.dumps(body_dict).encode("utf-8")
        req = request.Request(
            f"{self._BASE}/chat/completions",
            data=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.timeout_seconds) as response:  # noqa: S310
                payload: dict[str, Any] = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            logger.debug("OpenAI(tools) HTTP %s error body: %s", exc.code, detail)
            raise OpenAIError(
                f"OpenAI API 요청 실패 (HTTP {exc.code})", status_code=exc.code
            ) from exc
        except error.URLError as exc:
            raise OpenAIError(f"Cannot reach OpenAI: {exc.reason}") from exc
        except TimeoutError as exc:
            raise OpenAIError(
                "응답 시간이 초과됐습니다. 더 작은 모델을 사용하거나 `/settings`에서 제공자를 변경해보세요."
            ) from exc
        except json.JSONDecodeError as exc:
            raise OpenAIError("OpenAI returned invalid JSON") from exc
        self.last_usage = _parse_openai_usage(payload)
        return payload

    async def generate_with_tools(
        self,
        prompt: str,
        *,
        tools: list[ToolSpec],
        tool_runner: ToolRunner,
        model: str | None = None,
        max_iterations: int = _DEFAULT_TOOL_ITERATIONS,
    ) -> str:
        """OpenAI tools(function calling) 기반 경량 에이전트 루프 (#20).

        모델이 tool_calls 를 내면 각 호출을 ``tool_runner`` 로 실행해 결과를 role=
        "tool" 메시지로 되돌려주고 다시 모델을 호출한다. tool_calls 가 없으면(또는
        반복 상한 도달) 최종 텍스트를 반환한다. 툴이 비어 있으면 일반 generate 로
        폴백한다(기존 동작과 동일).
        """
        if not tools:
            return await self.generate(prompt, model=model)
        resolved_model = model or self.default_model
        openai_tools = self._tools_to_openai(tools)
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": self.system_prompt},
            {"role": "user", "content": prompt},
        ]
        last_text = ""
        for _ in range(max(1, max_iterations)):
            payload = await _with_circuit_breaker(
                self.circuit_breaker,
                lambda: asyncio.to_thread(
                    self._chat_sync, messages, resolved_model, openai_tools
                ),
            )
            try:
                message = payload["choices"][0]["message"]
            except (KeyError, IndexError, TypeError) as exc:
                raise OpenAIError("OpenAI 응답 형식을 해석할 수 없습니다.") from exc
            tool_calls = message.get("tool_calls") or []
            content = message.get("content")
            if isinstance(content, str) and content.strip():
                last_text = content.strip()
            if not tool_calls:
                # 더 호출할 툴이 없으면 최종 텍스트를 반환한다.
                return last_text
            # assistant 의 tool_calls 메시지를 대화에 추가한 뒤 각 결과를 되돌려준다.
            messages.append(message)
            for call in tool_calls:
                fn = call.get("function") or {}
                name = str(fn.get("name") or "")
                raw_args = fn.get("arguments") or "{}"
                try:
                    args = json.loads(raw_args) if isinstance(raw_args, str) else dict(raw_args)
                except (json.JSONDecodeError, TypeError, ValueError):
                    args = {}
                result = await _run_tool_safely(tool_runner, name, args)
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call.get("id", ""),
                        "content": result[:_MAX_TOOL_RESULT_CHARS],
                    }
                )
        # 반복 상한 도달 — 마지막 텍스트가 없으면 도구 결과 기반 최종 답변을 한 번 더 요청.
        if not last_text:
            messages.append(
                {
                    "role": "user",
                    "content": "위 도구 결과를 바탕으로 최종 답변을 작성해 주세요.",
                }
            )
            payload = await _with_circuit_breaker(
                self.circuit_breaker,
                lambda: asyncio.to_thread(
                    self._chat_sync, messages, resolved_model, []
                ),
            )
            try:
                final = payload["choices"][0]["message"].get("content")
            except (KeyError, IndexError, TypeError):
                final = None
            if isinstance(final, str) and final.strip():
                last_text = final.strip()
        return last_text


# ---------------------------------------------------------------------------
# Anthropic
# ---------------------------------------------------------------------------


class AnthropicClient(BaseLLMClient):
    """Async wrapper around Anthropic Messages API (raw HTTP, no SDK)."""

    _BASE = "https://api.anthropic.com/v1"
    _VERSION = "2023-06-01"

    def __init__(
        self,
        *,
        api_key: str,
        default_model: str = "claude-haiku-4-5-20251001",
        timeout_seconds: int = 60,
        max_tokens: int = 4096,
        system_prompt: str = "You are a helpful Discord bot assistant.",
        circuit_breaker: CircuitBreaker | None = None,
    ) -> None:
        self.api_key = api_key
        self.default_model = default_model
        self.timeout_seconds = timeout_seconds
        self.max_tokens = max_tokens
        self.system_prompt = system_prompt
        self.circuit_breaker = circuit_breaker
        # #17: 마지막 generate() 호출의 토큰 사용량(부수효과로 갱신).
        self.last_usage = TokenUsage()

    async def generate(
        self,
        prompt: str,
        *,
        model: str | None = None,
        images: list[ImageInput] | None = None,
    ) -> str:
        resolved_model = model or self.default_model
        return await _with_circuit_breaker(
            self.circuit_breaker,
            lambda: asyncio.to_thread(
                self._generate_sync, prompt, resolved_model, images
            ),
        )

    def _build_user_content(
        self, prompt: str, images: list[ImageInput] | None
    ) -> Any:
        """user 메시지 content 를 구성한다 (#12).

        이미지가 없으면 기존처럼 평문 문자열을 반환한다(백워드 호환). 이미지가
        있으면 Anthropic 멀티모달 규격의 content 배열(image source base64 +
        텍스트)을 반환한다. 이미지를 먼저 배치하는 것이 Anthropic 권장 순서다.
        """
        if not images:
            return prompt
        parts: list[dict[str, Any]] = []
        for img in images:
            mime, b64 = _encode_image_b64(img)
            parts.append(
                {
                    "type": "image",
                    "source": {
                        "type": "base64",
                        "media_type": mime,
                        "data": b64,
                    },
                }
            )
        parts.append({"type": "text", "text": prompt})
        return parts

    def _generate_sync(
        self, prompt: str, model: str, images: list[ImageInput] | None = None
    ) -> str:
        body = json.dumps(
            {
                "model": model,
                "max_tokens": self.max_tokens,
                "system": self.system_prompt,
                "messages": [
                    {"role": "user", "content": self._build_user_content(prompt, images)}
                ],
            }
        ).encode("utf-8")
        req = request.Request(
            f"{self._BASE}/messages",
            data=body,
            headers={
                "Content-Type": "application/json",
                "x-api-key": self.api_key,
                "anthropic-version": self._VERSION,
            },
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.timeout_seconds) as response:  # noqa: S310
                payload = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            logger.debug("Anthropic HTTP %s error body: %s", exc.code, detail)
            raise AnthropicError(
                f"Anthropic API 요청 실패 (HTTP {exc.code})", status_code=exc.code
            ) from exc
        except error.URLError as exc:
            raise AnthropicError(f"Cannot reach Anthropic: {exc.reason}") from exc
        except TimeoutError as exc:
            raise AnthropicError(
                "응답 시간이 초과됐습니다. 더 작은 모델을 사용하거나 `/settings`에서 제공자를 변경해보세요."
            ) from exc
        except json.JSONDecodeError as exc:
            raise AnthropicError("Anthropic returned invalid JSON") from exc
        try:
            text: str = payload["content"][0]["text"]
        except (KeyError, IndexError, TypeError) as exc:
            logger.debug("Unexpected Anthropic response shape: %s", payload)
            raise AnthropicError("Anthropic 응답 형식을 해석할 수 없습니다.") from exc
        # #17: 응답 파싱 성공 후에만 usage 를 기록한다.
        self.last_usage = _parse_anthropic_usage(payload)
        return text.strip()

    # ------------------------------------------------------------------
    # #20: 함수/툴 호출 (Anthropic tool_use 블록) 경량 에이전트 루프
    # ------------------------------------------------------------------

    @staticmethod
    def _tools_to_anthropic(tools: list[ToolSpec]) -> list[dict[str, Any]]:
        """ToolSpec 목록을 Anthropic tools(input_schema) 와이어 포맷으로 변환한다 (#20)."""
        return [
            {
                "name": t.name,
                "description": t.description,
                "input_schema": t.parameters,
            }
            for t in tools
        ]

    def _messages_sync(
        self, messages: list[dict[str, Any]], model: str, tools: list[dict[str, Any]]
    ) -> dict[str, Any]:
        """messages + tools 로 한 번의 Messages 호출을 수행해 payload 전체를 반환한다 (#20).

        ``_generate_sync`` 와 동일한 HTTP/에러 처리지만 text 만 뽑지 않고 전체
        payload(content 블록들 — tool_use 포함)를 돌려준다. 호출마다 usage 갱신.
        """
        body_dict: dict[str, Any] = {
            "model": model,
            "max_tokens": self.max_tokens,
            "system": self.system_prompt,
            "messages": messages,
        }
        if tools:
            body_dict["tools"] = tools
        body = json.dumps(body_dict).encode("utf-8")
        req = request.Request(
            f"{self._BASE}/messages",
            data=body,
            headers={
                "Content-Type": "application/json",
                "x-api-key": self.api_key,
                "anthropic-version": self._VERSION,
            },
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.timeout_seconds) as response:  # noqa: S310
                payload: dict[str, Any] = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            logger.debug("Anthropic(tools) HTTP %s error body: %s", exc.code, detail)
            raise AnthropicError(
                f"Anthropic API 요청 실패 (HTTP {exc.code})", status_code=exc.code
            ) from exc
        except error.URLError as exc:
            raise AnthropicError(f"Cannot reach Anthropic: {exc.reason}") from exc
        except TimeoutError as exc:
            raise AnthropicError(
                "응답 시간이 초과됐습니다. 더 작은 모델을 사용하거나 `/settings`에서 제공자를 변경해보세요."
            ) from exc
        except json.JSONDecodeError as exc:
            raise AnthropicError("Anthropic returned invalid JSON") from exc
        self.last_usage = _parse_anthropic_usage(payload)
        return payload

    @staticmethod
    def _extract_text_blocks(content: list[dict[str, Any]]) -> str:
        """Anthropic content 블록들에서 type=="text" 텍스트를 모아 이어붙인다 (#20)."""
        parts = [
            block["text"]
            for block in content
            if isinstance(block, dict)
            and block.get("type") == "text"
            and isinstance(block.get("text"), str)
        ]
        return "".join(parts).strip()

    async def generate_with_tools(
        self,
        prompt: str,
        *,
        tools: list[ToolSpec],
        tool_runner: ToolRunner,
        model: str | None = None,
        max_iterations: int = _DEFAULT_TOOL_ITERATIONS,
    ) -> str:
        """Anthropic tool_use 기반 경량 에이전트 루프 (#20).

        응답 content 에 ``tool_use`` 블록이 있으면 각 호출을 ``tool_runner`` 로
        실행해 ``tool_result`` 블록(user role)으로 되돌려주고 다시 모델을 호출한다.
        ``stop_reason`` 이 tool_use 가 아니면(또는 반복 상한 도달) 최종 텍스트를
        반환한다. 툴이 비어 있으면 일반 generate 로 폴백한다(기존 동작과 동일).
        """
        if not tools:
            return await self.generate(prompt, model=model)
        resolved_model = model or self.default_model
        anthropic_tools = self._tools_to_anthropic(tools)
        messages: list[dict[str, Any]] = [{"role": "user", "content": prompt}]
        last_text = ""
        for _ in range(max(1, max_iterations)):
            payload = await _with_circuit_breaker(
                self.circuit_breaker,
                lambda: asyncio.to_thread(
                    self._messages_sync, messages, resolved_model, anthropic_tools
                ),
            )
            content = payload.get("content")
            if not isinstance(content, list):
                raise AnthropicError("Anthropic 응답 형식을 해석할 수 없습니다.")
            text = self._extract_text_blocks(content)
            if text:
                last_text = text
            tool_uses = [
                block
                for block in content
                if isinstance(block, dict) and block.get("type") == "tool_use"
            ]
            if payload.get("stop_reason") != "tool_use" or not tool_uses:
                return last_text
            # assistant 의 tool_use 응답을 대화에 그대로 추가한 뒤 결과를 되돌려준다.
            messages.append({"role": "assistant", "content": content})
            tool_results: list[dict[str, Any]] = []
            for use in tool_uses:
                name = str(use.get("name") or "")
                args = use.get("input")
                if not isinstance(args, dict):
                    args = {}
                result = await _run_tool_safely(tool_runner, name, args)
                tool_results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": use.get("id", ""),
                        "content": result[:_MAX_TOOL_RESULT_CHARS],
                    }
                )
            messages.append({"role": "user", "content": tool_results})
        # 반복 상한 도달 — 도구 결과를 바탕으로 최종 답변을 한 번 더 요청(툴 없이).
        if not last_text:
            messages.append(
                {
                    "role": "user",
                    "content": "위 도구 결과를 바탕으로 최종 답변을 작성해 주세요.",
                }
            )
            payload = await _with_circuit_breaker(
                self.circuit_breaker,
                lambda: asyncio.to_thread(
                    self._messages_sync, messages, resolved_model, []
                ),
            )
            content = payload.get("content")
            if isinstance(content, list):
                final = self._extract_text_blocks(content)
                if final:
                    last_text = final
        return last_text


# ---------------------------------------------------------------------------
# Google Gemini (#15)
# ---------------------------------------------------------------------------


class GeminiClient(BaseLLMClient):
    """Async wrapper around Google Generative Language API (raw HTTP, no SDK).

    ``generateContent`` 엔드포인트를 사용한다. API 키는 보안상 URL 쿼리스트링이
    아니라 ``x-goog-api-key`` 헤더로 전달한다(키가 로그/URL 에 노출되지 않도록).
    system_prompt 는 ``systemInstruction`` 필드로, temperature 는
    ``generationConfig`` 로 전달해 OpenAI/Anthropic 어댑터와 일관성을 맞춘다.
    """

    _BASE = "https://generativelanguage.googleapis.com/v1beta"

    def __init__(
        self,
        *,
        api_key: str,
        default_model: str = "gemini-1.5-flash",
        timeout_seconds: int = 60,
        temperature: float = 0.2,
        system_prompt: str = "You are a helpful Discord bot assistant.",
        circuit_breaker: CircuitBreaker | None = None,
    ) -> None:
        self.api_key = api_key
        self.default_model = default_model
        self.timeout_seconds = timeout_seconds
        self.temperature = temperature
        self.system_prompt = system_prompt
        self.circuit_breaker = circuit_breaker
        # #17: 마지막 generate() 호출의 토큰 사용량(부수효과로 갱신).
        self.last_usage = TokenUsage()

    async def generate(
        self,
        prompt: str,
        *,
        model: str | None = None,
        images: list[ImageInput] | None = None,
    ) -> str:
        resolved_model = model or self.default_model
        return await _with_circuit_breaker(
            self.circuit_breaker,
            lambda: asyncio.to_thread(
                self._generate_sync, prompt, resolved_model, images
            ),
        )

    def _build_body(
        self, prompt: str, images: list[ImageInput] | None = None
    ) -> bytes:
        # #12: Gemini 는 parts 배열에 inlineData(mimeType, base64 data)로 이미지를
        # 함께 보낸다. 이미지가 없으면 텍스트 part 만 담아 기존 경로 그대로다.
        parts: list[dict[str, Any]] = [{"text": prompt}]
        for img in images or []:
            mime, b64 = _encode_image_b64(img)
            parts.append({"inlineData": {"mimeType": mime, "data": b64}})
        payload: dict[str, Any] = {
            "contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"temperature": self.temperature},
        }
        if self.system_prompt:
            payload["systemInstruction"] = {"parts": [{"text": self.system_prompt}]}
        return json.dumps(payload).encode("utf-8")

    def _generate_sync(
        self, prompt: str, model: str, images: list[ImageInput] | None = None
    ) -> str:
        # 모델 이름에 슬래시/예약문자가 들어와도 URL 경로를 깨지 않도록 인코딩한다.
        safe_model = parse.quote(model, safe="")
        body = self._build_body(prompt, images)
        req = request.Request(
            f"{self._BASE}/models/{safe_model}:generateContent",
            data=body,
            headers={
                "Content-Type": "application/json",
                "x-goog-api-key": self.api_key,
            },
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.timeout_seconds) as response:  # noqa: S310
                payload = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            logger.debug("Gemini HTTP %s error body: %s", exc.code, detail)
            raise GeminiError(
                f"Gemini API 요청 실패 (HTTP {exc.code})", status_code=exc.code
            ) from exc
        except error.URLError as exc:
            raise GeminiError(f"Cannot reach Gemini: {exc.reason}") from exc
        except TimeoutError as exc:
            raise GeminiError(
                "응답 시간이 초과됐습니다. 더 작은 모델을 사용하거나 `/settings`에서 제공자를 변경해보세요."
            ) from exc
        except json.JSONDecodeError as exc:
            raise GeminiError("Gemini returned invalid JSON") from exc
        text = _parse_gemini_payload(payload)
        # #17: 텍스트 파싱 성공(차단/빈 응답 아님) 후에만 usage 를 기록한다.
        self.last_usage = _parse_gemini_usage(payload)
        return text


def _parse_gemini_payload(payload: dict[str, Any]) -> str:
    """Gemini generateContent 응답에서 텍스트를 추출한다.

    응답은 ``candidates[0].content.parts[*].text`` 형태이며, parts 가 여러 개일
    수 있어 모두 이어붙인다. promptFeedback.blockReason 으로 차단된 경우엔 안내
    오류를 던진다.
    """
    if isinstance(payload.get("promptFeedback"), dict):
        block = payload["promptFeedback"].get("blockReason")
        if block:
            raise GeminiError(f"Gemini 안전 필터에 의해 차단됐습니다 ({block}).")
    try:
        parts = payload["candidates"][0]["content"]["parts"]
        text = "".join(
            part["text"] for part in parts if isinstance(part.get("text"), str)
        )
    except (KeyError, IndexError, TypeError) as exc:
        logger.debug("Unexpected Gemini response shape: %s", payload)
        raise GeminiError("Gemini 응답 형식을 해석할 수 없습니다.") from exc
    if not text.strip():
        raise GeminiError("Gemini 응답이 비어 있습니다.")
    return text.strip()


# ---------------------------------------------------------------------------
# Ollama model manager
# ---------------------------------------------------------------------------


class OllamaManager:
    """List and pull local Ollama models."""

    POPULAR_MODELS: list[tuple[str, str, str]] = [
        ("llama3.1:8b", "Meta Llama 3.1 8B", "범용 · ~4.7GB"),
        ("gemma2:9b", "Google Gemma 2 9B", "범용 · ~5.5GB"),
        ("qwen2.5:7b", "Alibaba Qwen 2.5 7B", "다국어 · ~4.4GB"),
        ("mistral:7b", "Mistral 7B", "범용 · ~4.1GB"),
        ("phi3:mini", "Microsoft Phi-3 Mini", "경량 · ~2.3GB"),
        ("codellama:7b", "Meta Code Llama 7B", "코딩 · ~3.8GB"),
        ("deepseek-r1:8b", "DeepSeek R1 8B", "추론 · ~4.9GB"),
        ("dolphin-mistral:latest", "Dolphin Mistral 7B", "범용 · ~4.1GB"),
    ]

    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    async def list_models(self) -> list[OllamaModel]:
        return await asyncio.to_thread(self._list_sync)

    def _list_sync(self) -> list[OllamaModel]:
        req = request.Request(
            f"{self.base_url}/api/tags",
            headers={"Accept": "application/json"},
            method="GET",
        )
        try:
            with request.urlopen(req, timeout=10) as response:  # noqa: S310
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:
            logger.debug("Failed to list Ollama models: %s", exc)
            return []
        return [OllamaModel(name=m["name"], size_bytes=m.get("size", 0)) for m in payload.get("models", [])]

    async def pull_model(self, model_name: str) -> None:
        ollama_bin = shutil.which("ollama")
        if ollama_bin is None:
            raise OllamaError(
                "`ollama` 실행 파일을 PATH에서 찾을 수 없습니다. "
                "Ollama가 설치되어 있는지, 컨테이너 환경이라면 호스트에서 설치했는지 확인해 주세요."
            )
        proc = await asyncio.create_subprocess_exec(
            ollama_bin,
            "pull",
            model_name,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await proc.communicate()
        if proc.returncode != 0:
            raise OllamaError(f"ollama pull 실패: {stderr.decode(errors='replace').strip()}")

    async def is_available(self) -> bool:
        try:
            await asyncio.to_thread(self._list_sync)
            return True
        except Exception:
            return False


# ---------------------------------------------------------------------------
# #14: 비전(멀티모달) 지원 판정 (순수 함수, capability 판정만)
# ---------------------------------------------------------------------------


def _normalize_model_name(model: str) -> str:
    """모델 이름을 비교용으로 정규화한다.

    - 앞뒤 공백 제거 후 소문자화.
    - OpenAI는 `gpt-4o-mini` 처럼 하이픈 구분, Anthropic은 `claude-3-5-sonnet` 처럼
      버전·날짜 접미사가 붙고, Ollama는 `llava:13b` 처럼 `:tag`가 붙는다.
      판정은 부분 문자열 매칭으로 처리하므로 여기서는 단순 정규화만 한다.
    """
    return model.strip().lower()


# 비전을 지원하는 모델 식별용 부분 문자열 토큰(제공자별).
# 부분 문자열 매칭이므로 버전/날짜/태그 접미사가 붙어도 인식된다.
# 예) "gpt-4o-mini" → "gpt-4o" 포함, "claude-3-5-sonnet-20241022" → "claude-3-5-sonnet" 포함.
_OPENAI_VISION_TOKENS: tuple[str, ...] = (
    "gpt-4o",          # gpt-4o, gpt-4o-mini 등
    "gpt-4-turbo",     # gpt-4-turbo (vision 지원)
    "gpt-4-vision",    # 구형 gpt-4-vision-preview
    "gpt-4.1",         # gpt-4.1, gpt-4.1-mini, gpt-4.1-nano (vision)
    "gpt-4.5",         # gpt-4.5 계열
    "o1",              # o1, o1-mini 등 추론 모델(이미지 입력 지원)
    "o3",              # o3 계열
    "o4",              # o4-mini 등
)
# vision을 지원하지 않는 OpenAI 모델 토큰(위 토큰과 겹칠 때 우선 제외).
_OPENAI_NO_VISION_TOKENS: tuple[str, ...] = (
    "gpt-3.5",         # gpt-3.5-turbo 계열은 텍스트 전용
    "o1-mini",         # o1-mini는 텍스트 전용
    "o3-mini",         # o3-mini는 텍스트 전용
)

_ANTHROPIC_VISION_TOKENS: tuple[str, ...] = (
    "claude-3",        # claude-3-opus/sonnet/haiku 및 claude-3-5-*, claude-3-7-* 모두 vision
    "claude-opus-4",   # claude-opus-4-* (Claude 4 계열)
    "claude-sonnet-4", # claude-sonnet-4-*
    "claude-haiku-4",  # claude-haiku-4-*
    "claude-3.5",      # 점 표기 변형
    "claude-3.7",
)

_OLLAMA_VISION_TOKENS: tuple[str, ...] = (
    "llava",           # llava, llava-llama3 등
    "bakllava",        # bakllava
    "llama3.2-vision", # llama3.2-vision
    "llama-3.2-vision",
    "vision",          # *-vision 류 (minicpm-v 제외하고 generic)
    "minicpm-v",       # minicpm-v 멀티모달
    "moondream",       # moondream
    "llama4",          # llama4 멀티모달
)


def supports_vision(provider: LLMProvider, model: str) -> bool:
    """주어진 제공자·모델이 이미지(비전) 입력을 지원하는지 판정한다.

    실제 멀티모달 전송 구현이 아니라 capability(가능 여부) 판정만 하는 순수 함수다.
    모델 이름은 부분 문자열 매칭으로 검사하므로 버전/날짜/태그 접미사가 붙어도 인식된다.

    Args:
        provider: LLM 제공자(OLLAMA/OPENAI/ANTHROPIC).
        model: 모델 이름(예: "gpt-4o-mini", "claude-3-5-sonnet-20241022", "llava:13b").

    Returns:
        비전 지원 시 True, 아니면 False. (빈 모델 이름은 False)
    """
    name = _normalize_model_name(model)
    if not name:
        return False

    if provider == LLMProvider.OPENAI:
        # 명시적으로 텍스트 전용으로 알려진 모델은 먼저 제외한다.
        if any(token in name for token in _OPENAI_NO_VISION_TOKENS):
            return False
        return any(token in name for token in _OPENAI_VISION_TOKENS)

    if provider == LLMProvider.ANTHROPIC:
        return any(token in name for token in _ANTHROPIC_VISION_TOKENS)

    if provider == LLMProvider.OLLAMA:
        return any(token in name for token in _OLLAMA_VISION_TOKENS)

    return False


# ---------------------------------------------------------------------------
# #18: 제공자·모델별 단가 테이블 + 비용 계산 (순수 함수)
# ---------------------------------------------------------------------------


# 1,000 토큰당 USD 단가: PRICING[(provider, normalized_model)] = (input_per_1k, output_per_1k).
# 모델 이름은 정규화(소문자) 후 부분 문자열 매칭으로 조회한다(버전/날짜 접미사 허용).
# 단가는 공개 기준가(2025년 기준)를 참고한 근사치이며 정확한 청구액은 제공자 콘솔을 따른다.
# 로컬 Ollama 모델은 무료이므로 (0.0, 0.0).
PRICING: dict[tuple[LLMProvider, str], tuple[float, float]] = {
    # --- OpenAI (USD per 1K tokens) ---
    (LLMProvider.OPENAI, "gpt-4o"): (0.0025, 0.01),
    (LLMProvider.OPENAI, "gpt-4o-mini"): (0.00015, 0.0006),
    (LLMProvider.OPENAI, "gpt-4-turbo"): (0.01, 0.03),
    (LLMProvider.OPENAI, "gpt-4.1"): (0.002, 0.008),
    (LLMProvider.OPENAI, "gpt-4.1-mini"): (0.0004, 0.0016),
    (LLMProvider.OPENAI, "gpt-4.1-nano"): (0.0001, 0.0004),
    (LLMProvider.OPENAI, "gpt-3.5-turbo"): (0.0005, 0.0015),
    (LLMProvider.OPENAI, "o1"): (0.015, 0.06),
    (LLMProvider.OPENAI, "o1-mini"): (0.0011, 0.0044),
    (LLMProvider.OPENAI, "o3-mini"): (0.0011, 0.0044),
    # --- Anthropic (USD per 1K tokens) ---
    (LLMProvider.ANTHROPIC, "claude-opus-4"): (0.015, 0.075),
    (LLMProvider.ANTHROPIC, "claude-sonnet-4"): (0.003, 0.015),
    (LLMProvider.ANTHROPIC, "claude-haiku-4"): (0.001, 0.005),
    (LLMProvider.ANTHROPIC, "claude-3-5-sonnet"): (0.003, 0.015),
    (LLMProvider.ANTHROPIC, "claude-3-5-haiku"): (0.0008, 0.004),
    (LLMProvider.ANTHROPIC, "claude-3-opus"): (0.015, 0.075),
    (LLMProvider.ANTHROPIC, "claude-3-sonnet"): (0.003, 0.015),
    (LLMProvider.ANTHROPIC, "claude-3-haiku"): (0.00025, 0.00125),
}


def _lookup_pricing(
    provider: LLMProvider, model: str
) -> tuple[float, float] | None:
    """(provider, model)에 대한 (input_per_1k, output_per_1k) 단가를 조회한다.

    - 로컬 Ollama는 무료이므로 (0.0, 0.0)을 반환한다.
    - 정확히 일치하는 키가 없으면 정규화된 모델 이름을 PRICING 키의 부분 문자열로
      매칭한다(가장 긴 키 우선 → 더 구체적인 모델 단가 선택).
    - 매칭 실패 시 None.
    """
    if provider == LLMProvider.OLLAMA:
        # 로컬 실행 모델은 토큰 과금이 없다.
        return (0.0, 0.0)

    name = _normalize_model_name(model)
    if not name:
        return None

    exact = PRICING.get((provider, name))
    if exact is not None:
        return exact

    # 부분 문자열 매칭: 같은 제공자의 키 중 모델 이름에 포함되는 가장 긴 키를 선택한다.
    best_key: str | None = None
    for (prov, key), _price in PRICING.items():
        if prov != provider:
            continue
        if key in name and (best_key is None or len(key) > len(best_key)):
            best_key = key
    if best_key is not None:
        return PRICING[(provider, best_key)]
    return None


def estimate_cost(
    provider: LLMProvider,
    model: str,
    prompt_tokens: int,
    completion_tokens: int,
) -> float:
    """프롬프트·완성 토큰 수로 대략적인 USD 비용을 추정한다(순수 함수).

    - 단가 테이블(PRICING)에 등록된 모델만 0이 아닌 값을 반환한다.
    - 미등록 모델(제공자 매칭 실패)은 0.0을 반환한다(과금 정보 없음 → 0 처리).
    - 로컬 Ollama 모델은 항상 0.0(무료).
    - 음수 토큰 수는 0으로 보정한다.

    Args:
        provider: LLM 제공자.
        model: 모델 이름.
        prompt_tokens: 입력(프롬프트) 토큰 수.
        completion_tokens: 출력(완성) 토큰 수.

    Returns:
        추정 비용(USD). 미등록 모델은 0.0.
    """
    price = _lookup_pricing(provider, model)
    if price is None:
        return 0.0
    input_per_1k, output_per_1k = price
    p_tokens = max(prompt_tokens, 0)
    c_tokens = max(completion_tokens, 0)
    return (p_tokens / 1000.0) * input_per_1k + (c_tokens / 1000.0) * output_per_1k
