"""클라우드 Gemini 호출 — 관리자 클라우드 백엔드(Google Generative Language API).

관리자가 Gemini API 키 하나(AI Studio: https://aistudio.google.com/apikey)를 넣으면
``gemini-2.5-flash-lite`` 같은 모델이 풀에 공급 모델로 광고된다. Ollama(로컬)와 **동일한
(text, Usage) 인터페이스**라 에이전트가 같은 라우팅·한도·공정성으로 처리한다.

키는 프로바이더(관리자) PC 에만 저장하고 central 엔 절대 올리지 않는다(Ollama 토큰과 동일 원칙).
무료 등급은 입력/출력이 Google 제품 개선에 쓰일 수 있으니(2026 기준), 민감하면 유료 등급 권장.
"""
from __future__ import annotations

import logging

import aiohttp

from .protocol import Usage

logger = logging.getLogger("provider_agent.gemini")

GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite"


class GeminiError(Exception):
    """Gemini 호출/응답 오류."""


def _extract_text(data: object) -> str | None:
    """generateContent 응답에서 첫 후보의 합쳐진 텍스트. 차단/빈 응답이면 None."""
    if not isinstance(data, dict):
        return None
    cands = data.get("candidates")
    if not isinstance(cands, list) or not cands or not isinstance(cands[0], dict):
        return None
    content = cands[0].get("content")
    parts = content.get("parts") if isinstance(content, dict) else None
    if not isinstance(parts, list):
        return None
    texts = [p["text"] for p in parts if isinstance(p, dict) and isinstance(p.get("text"), str)]
    return "".join(texts) if texts else None


class GeminiClient:
    def __init__(self, api_key: str, timeout: float = 120.0) -> None:
        self._key = api_key
        self._timeout = aiohttp.ClientTimeout(total=timeout)

    async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
        """프롬프트를 Gemini 로 추론해 (text, Usage) 반환. 오류 시 GeminiError."""
        m = (model or DEFAULT_GEMINI_MODEL).strip()
        url = f"{GEMINI_API_BASE}/models/{m}:generateContent"
        payload: dict[str, object] = {"contents": [{"role": "user", "parts": [{"text": prompt}]}]}
        headers = {"x-goog-api-key": self._key, "Content-Type": "application/json"}
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                async with s.post(url, json=payload, headers=headers) as r:
                    data = await r.json()
        except aiohttp.ClientError as exc:
            raise GeminiError(f"Gemini 연결 실패: {exc}") from exc
        if isinstance(data, dict) and data.get("error"):
            err = data["error"]
            msg = err.get("message") if isinstance(err, dict) else str(err)
            raise GeminiError(f"Gemini: {msg}")
        text = _extract_text(data)
        if text is None:
            raise GeminiError("Gemini 응답에 텍스트가 없습니다(안전 필터 차단 또는 빈 응답)")
        um = data.get("usageMetadata", {}) if isinstance(data, dict) else {}
        usage = Usage(
            prompt_tokens=int(um.get("promptTokenCount", 0) or 0),
            completion_tokens=int(um.get("candidatesTokenCount", 0) or 0),
        )
        return text.strip(), usage

    async def translate(self, user_text: str, system_prompt: str, model: str | None = None) -> str:
        """system_prompt 규칙에 따라 user_text 를 변환(이미지 프롬프트 번역용). 오류 시 GeminiError.

        systemInstruction 으로 '성인·SFW·품질 prefix' 정책을 강제해, 정상적인 SFW 요청이
        미성년 오탐으로 거부되지 않게 한다(거부는 central 정책 소유, 실행만 여기서).
        """
        m = (model or DEFAULT_GEMINI_MODEL).strip()
        url = f"{GEMINI_API_BASE}/models/{m}:generateContent"
        payload: dict[str, object] = {
            "systemInstruction": {"parts": [{"text": system_prompt}]},
            "contents": [{"role": "user", "parts": [{"text": user_text}]}],
            "generationConfig": {"temperature": 0.7},
        }
        headers = {"x-goog-api-key": self._key, "Content-Type": "application/json"}
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                async with s.post(url, json=payload, headers=headers) as r:
                    data = await r.json()
        except aiohttp.ClientError as exc:
            raise GeminiError(f"Gemini 연결 실패: {exc}") from exc
        if isinstance(data, dict) and data.get("error"):
            err = data["error"]
            msg = err.get("message") if isinstance(err, dict) else str(err)
            raise GeminiError(f"Gemini: {msg}")
        text = _extract_text(data)
        if text is None:
            raise GeminiError("Gemini 응답에 텍스트가 없습니다(안전 필터 차단 또는 빈 응답)")
        return text.strip()

    async def health(self) -> bool:
        """키가 유효한지 가벼운 호출(모델 목록)로 확인 — capability 광고 판단용."""
        url = f"{GEMINI_API_BASE}/models"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(url, headers={"x-goog-api-key": self._key}) as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False
