"""Multi-provider LLM adapters (Ollama, OpenAI, Anthropic) and Ollama model manager."""
from __future__ import annotations

import asyncio
import json
import logging
import shutil
import time
from abc import ABC, abstractmethod
from collections.abc import Callable, Coroutine
from typing import Any
from urllib import error, request

from .models import LLMProvider, OllamaModel

logger = logging.getLogger(__name__)


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
    coro_fn: Callable[[], Coroutine[Any, Any, str]],
    max_attempts: int = 2,
    delay: float = 1.0,
) -> str:
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
    coro_fn: Callable[[], Coroutine[Any, Any, str]],
    *,
    max_attempts: int = 2,
    delay: float = 1.0,
) -> str:
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
    """Minimal interface every LLM provider adapter must implement."""

    @abstractmethod
    async def generate(self, prompt: str, *, model: str | None = None) -> str: ...


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

    async def generate(self, prompt: str, *, model: str | None = None) -> str:
        resolved_model = model or self.default_model
        return await _with_circuit_breaker(
            self.circuit_breaker,
            lambda: asyncio.to_thread(self._generate_sync, prompt, resolved_model),
        )

    def _generate_sync(self, prompt: str, model: str) -> str:
        body = json.dumps(
            {
                "model": model,
                "prompt": prompt,
                "stream": False,
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
        return parse_generate_response(payload)


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

    async def generate(self, prompt: str, *, model: str | None = None) -> str:
        resolved_model = model or self.default_model
        return await _with_circuit_breaker(
            self.circuit_breaker,
            lambda: asyncio.to_thread(self._generate_sync, prompt, resolved_model),
        )

    def _generate_sync(self, prompt: str, model: str) -> str:
        body = json.dumps(
            {
                "model": model,
                "messages": [
                    {"role": "system", "content": self.system_prompt},
                    {"role": "user", "content": prompt},
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
            return content.strip()
        except (KeyError, IndexError, TypeError) as exc:
            logger.debug("Unexpected OpenAI response shape: %s", payload)
            raise OpenAIError("OpenAI 응답 형식을 해석할 수 없습니다.") from exc


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

    async def generate(self, prompt: str, *, model: str | None = None) -> str:
        resolved_model = model or self.default_model
        return await _with_circuit_breaker(
            self.circuit_breaker,
            lambda: asyncio.to_thread(self._generate_sync, prompt, resolved_model),
        )

    def _generate_sync(self, prompt: str, model: str) -> str:
        body = json.dumps(
            {
                "model": model,
                "max_tokens": self.max_tokens,
                "system": self.system_prompt,
                "messages": [{"role": "user", "content": prompt}],
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
            return text.strip()
        except (KeyError, IndexError, TypeError) as exc:
            logger.debug("Unexpected Anthropic response shape: %s", payload)
            raise AnthropicError("Anthropic 응답 형식을 해석할 수 없습니다.") from exc


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
