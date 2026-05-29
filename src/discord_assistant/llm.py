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

from .models import OllamaModel

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
