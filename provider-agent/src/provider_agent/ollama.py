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

    async def generate(
        self,
        prompt: str,
        model: str | None,
        images: list[str] | None = None,
    ) -> tuple[str, Usage]:
        """프롬프트를 추론해 (text, usage) 를 반환한다. 오류 시 OllamaError.

        images: 비전 모델(llava 등)용 base64 이미지 목록(차수 11 #143, 선택). 빈/None 이면 텍스트 전용.
        """
        url = f"{self._base}/api/generate"
        payload: dict[str, object] = {"model": model or "", "prompt": prompt, "stream": False}
        if images:
            payload["images"] = images  # Ollama 비전 입력
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

    async def generate_stream(self, prompt: str, model: str | None):
        """스트리밍 추론(차수 3 #35). 부분 텍스트(chunk)를 yield 하고 마지막에 ('', Usage) 를 yield.

        Ollama ``stream=True`` 의 NDJSON 라인을 순차 파싱한다. 각 라인: {"response": "...", "done": bool, ...}.
        """
        import json

        url = f"{self._base}/api/generate"
        payload = {"model": model or "", "prompt": prompt, "stream": True}
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                async with s.post(url, json=payload) as r:
                    prompt_tokens = 0
                    completion_tokens = 0
                    async for raw in r.content:
                        line = raw.decode("utf-8").strip()
                        if not line:
                            continue
                        obj = json.loads(line)
                        if obj.get("error"):
                            raise OllamaError(str(obj["error"]))
                        piece = obj.get("response")
                        if isinstance(piece, str) and piece:
                            yield ("chunk", piece)
                        if obj.get("done"):
                            prompt_tokens = int(obj.get("prompt_eval_count", 0) or 0)
                            completion_tokens = int(obj.get("eval_count", 0) or 0)
                    yield ("done", Usage(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens))
        except aiohttp.ClientError as exc:
            raise OllamaError(f"Ollama 연결 실패: {exc}") from exc

    async def list_models(self) -> list[str]:
        """설치된 모델명 목록. 오류 시 OllamaError."""
        return [m["name"] for m in await self.list_models_detailed()]

    async def list_models_detailed(self) -> list[dict]:
        """설치된 모델 상세(name·size·modifiedAt·family·parameterSize). 오류 시 OllamaError.

        Ollama /api/tags 는 모델마다 size(byte)·modified_at·details 를 준다. 이름만 버리지 말고
        그대로 노출해 GUI 가 용량·마지막 사용을 표시하도록 한다(별도 메타 API 불필요).
        """
        url = f"{self._base}/api/tags"
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                async with s.get(url) as r:
                    data = await r.json()
        except aiohttp.ClientError as exc:
            raise OllamaError(f"Ollama 연결 실패: {exc}") from exc
        raw = data.get("models", []) if isinstance(data, dict) else []
        out: list[dict] = []
        for m in raw:
            if isinstance(m, dict) and "name" in m:
                details = m.get("details") or {}
                out.append(
                    {
                        "name": str(m["name"]),
                        "size": int(m.get("size") or 0),
                        "modifiedAt": str(m.get("modified_at") or ""),
                        "family": str(details.get("family") or ""),
                        "parameterSize": str(details.get("parameter_size") or ""),
                    }
                )
        return out

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
