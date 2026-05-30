"""localhost Ollama 호출 (차수 3). /api/generate(비스트리밍)·/api/tags."""
from __future__ import annotations

import logging

import aiohttp

from .protocol import Usage

logger = logging.getLogger("provider_agent.ollama")


class OllamaError(Exception):
    """Ollama 호출/응답 오류."""


class OllamaClient:
    def __init__(self, base_url: str, timeout: float = 120.0) -> None:
        self._base = base_url.rstrip("/")
        self._timeout = aiohttp.ClientTimeout(total=timeout)

    async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
        """프롬프트를 추론해 (text, usage) 를 반환한다. 오류 시 OllamaError."""
        url = f"{self._base}/api/generate"
        payload = {"model": model or "", "prompt": prompt, "stream": False}
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                async with s.post(url, json=payload) as r:
                    data = await r.json()
        except aiohttp.ClientError as exc:
            raise OllamaError(f"Ollama 연결 실패: {exc}") from exc
        if isinstance(data, dict) and data.get("error"):
            raise OllamaError(str(data["error"]))
        text = data.get("response") if isinstance(data, dict) else None
        if not isinstance(text, str):
            raise OllamaError("Ollama 응답에 response 텍스트가 없습니다")
        usage = Usage(
            prompt_tokens=int(data.get("prompt_eval_count", 0) or 0),
            completion_tokens=int(data.get("eval_count", 0) or 0),
        )
        return text.strip(), usage

    async def list_models(self) -> list[str]:
        """설치된 모델명 목록. 오류 시 OllamaError."""
        url = f"{self._base}/api/tags"
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                async with s.get(url) as r:
                    data = await r.json()
        except aiohttp.ClientError as exc:
            raise OllamaError(f"Ollama 연결 실패: {exc}") from exc
        models = data.get("models", []) if isinstance(data, dict) else []
        return [str(m["name"]) for m in models if isinstance(m, dict) and "name" in m]

    async def health(self) -> bool:
        """Ollama 가 응답하는지(차수 10 복구 감지)."""
        try:
            await self.list_models()
            return True
        except OllamaError:
            return False

    async def pull(self, model: str) -> None:
        """모델을 내려받는다(차수 10, 선택). 실패 시 OllamaError."""
        url = f"{self._base}/api/pull"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=1800)) as s:
                async with s.post(url, json={"model": model, "stream": False}) as r:
                    data = await r.json()
        except aiohttp.ClientError as exc:
            raise OllamaError(f"Ollama pull 실패: {exc}") from exc
        if isinstance(data, dict) and data.get("error"):
            raise OllamaError(str(data["error"]))
