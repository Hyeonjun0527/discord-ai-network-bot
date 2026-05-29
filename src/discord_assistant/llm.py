"""Multi-provider LLM adapters (Ollama, OpenAI, Anthropic) and Ollama model manager."""
from __future__ import annotations

import asyncio
import json
import logging
import shutil
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
    """Base class for all LLM provider errors."""


class OllamaError(LLMError):
    """Raised when Ollama cannot produce a usable response."""


class OpenAIError(LLMError):
    """Raised when the OpenAI API returns an error."""


class AnthropicError(LLMError):
    """Raised when the Anthropic API returns an error."""


# ---------------------------------------------------------------------------
# Retry helper
# ---------------------------------------------------------------------------


async def _with_retry(
    coro_fn: Callable[[], Coroutine[Any, Any, str]],
    max_attempts: int = 2,
    delay: float = 1.0,
) -> str:
    """Run ``coro_fn`` up to ``max_attempts`` times, retrying on LLMError with exponential backoff."""
    if max_attempts < 1:
        raise ValueError(f"max_attempts must be >= 1, got {max_attempts}")
    last_exc: LLMError | None = None
    for attempt in range(max_attempts):
        try:
            return await coro_fn()
        except LLMError as exc:
            last_exc = exc
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
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.default_model = default_model
        self.timeout_seconds = timeout_seconds
        self.keep_alive = keep_alive
        self.temperature = temperature
        self.num_ctx = num_ctx

    async def generate(self, prompt: str, *, model: str | None = None) -> str:
        resolved_model = model or self.default_model
        return await _with_retry(
            lambda: asyncio.to_thread(self._generate_sync, prompt, resolved_model)
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
            raise OllamaError(f"Ollama 요청 실패 (HTTP {exc.code})") from exc
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

    def __init__(self, *, api_key: str, default_model: str = "gpt-4o-mini", timeout_seconds: int = 60) -> None:
        self.api_key = api_key
        self.default_model = default_model
        self.timeout_seconds = timeout_seconds

    async def generate(self, prompt: str, *, model: str | None = None) -> str:
        resolved_model = model or self.default_model
        return await _with_retry(
            lambda: asyncio.to_thread(self._generate_sync, prompt, resolved_model)
        )

    def _generate_sync(self, prompt: str, model: str) -> str:
        body = json.dumps(
            {
                "model": model,
                "messages": [
                    {"role": "system", "content": "You are a helpful Discord bot assistant."},
                    {"role": "user", "content": prompt},
                ],
                "temperature": 0.2,
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
            raise OpenAIError(f"OpenAI API 요청 실패 (HTTP {exc.code})") from exc
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
    ) -> None:
        self.api_key = api_key
        self.default_model = default_model
        self.timeout_seconds = timeout_seconds

    async def generate(self, prompt: str, *, model: str | None = None) -> str:
        resolved_model = model or self.default_model
        return await _with_retry(
            lambda: asyncio.to_thread(self._generate_sync, prompt, resolved_model)
        )

    def _generate_sync(self, prompt: str, model: str) -> str:
        body = json.dumps(
            {
                "model": model,
                "max_tokens": 4096,
                "system": "You are a helpful Discord bot assistant.",
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
            raise AnthropicError(f"Anthropic API 요청 실패 (HTTP {exc.code})") from exc
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
