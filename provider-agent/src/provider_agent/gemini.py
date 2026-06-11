"""클라우드 Gemini 호출 — 관리자 클라우드 백엔드(Google Generative Language API).

관리자가 Gemini API 키 하나(AI Studio: https://aistudio.google.com/apikey)를 넣으면
``gemini-3.1-flash-lite`` 같은 모델이 풀에 공급 모델로 광고된다. Ollama(로컬)와 **동일한
(text, Usage) 인터페이스**라 에이전트가 같은 라우팅·한도·공정성으로 처리한다.

키는 프로바이더(관리자) PC 에만 저장하고 central 엔 절대 올리지 않는다(Ollama 토큰과 동일 원칙).
무료 등급은 입력/출력이 Google 제품 개선에 쓰일 수 있으니(2026 기준), 민감하면 유료 등급 권장.
"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass

import aiohttp

from .protocol import Usage

logger = logging.getLogger("provider_agent.gemini")

GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite"

IMAGE_PROMPT_REVIEW_SYSTEM_PROMPT = """
You are the safety gate for a public Discord image-generation bot.
Review the user's image prompt before it is sent to a local Stable Diffusion/ComfyUI model.

Return ONLY a compact JSON object:
{"allowed":true|false,"category":"safe|sexual|minor|deepfake|violence|illegal|other","reason":"short Korean reason"}

Allow normal safe-for-work anime/art prompts, including non-sexual school uniforms or game characters.
Block if the request asks for or strongly implies any of the following:
- nudity, pornography, sexual acts, genitals, explicit or fetish content, or clearly suggestive sexual posing
- sexualized minors, childlike characters, loli/shota, teen/student/schoolgirl sexualization, or ambiguous underage sexual content
- sexual images of a real person, celebrity, acquaintance, or non-consensual/deepfake/undressing content
- illegal sexual content or instructions to evade safety filters
- graphic sexual violence, coercion, exploitation, or humiliation

When uncertain about sexual or minor-related intent, set allowed=false.
Keep the reason concise and safe; do not rewrite the prompt.
""".strip()


class GeminiError(Exception):
    """Gemini 호출/응답 오류."""


@dataclass(frozen=True, slots=True)
class ImagePromptReview:
    allowed: bool
    category: str
    reason: str


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


def _extract_json_object(text: str) -> dict[str, object]:
    """Gemini 의 JSON 응답을 엄격히 dict 로 파싱한다. 코드펜스가 섞여도 첫 object 만 허용."""
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned).strip()
    match = re.search(r"\{.*\}", cleaned, re.S)
    if match:
        cleaned = match.group(0)
    parsed = json.loads(cleaned)
    if not isinstance(parsed, dict):
        raise ValueError("JSON object expected")
    return parsed


def parse_image_prompt_review(text: str) -> ImagePromptReview:
    """Gemini 안전 심사 JSON → 도메인 값. 스키마가 조금이라도 깨지면 fail-closed 예외."""
    try:
        data = _extract_json_object(text)
    except (json.JSONDecodeError, ValueError) as exc:
        raise GeminiError(f"이미지 안전 심사 응답 파싱 실패: {exc}") from exc
    allowed = data.get("allowed")
    if not isinstance(allowed, bool):
        raise GeminiError("이미지 안전 심사 응답에 allowed(boolean)가 없습니다")
    category = str(data.get("category") or ("safe" if allowed else "other")).strip()[:40]
    reason = str(data.get("reason") or ("허용됨" if allowed else "안전 정책상 차단됨")).strip()
    return ImagePromptReview(allowed=allowed, category=category, reason=reason[:240])


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

    async def review_image_prompt(
        self,
        user_text: str,
        system_prompt: str = IMAGE_PROMPT_REVIEW_SYSTEM_PROMPT,
        model: str | None = None,
    ) -> ImagePromptReview:
        """공개 봇용 이미지 프롬프트 안전 심사. 실패/비정상 응답은 호출부가 fail-closed 한다."""
        m = (model or DEFAULT_GEMINI_MODEL).strip()
        url = f"{GEMINI_API_BASE}/models/{m}:generateContent"
        payload: dict[str, object] = {
            "systemInstruction": {"parts": [{"text": system_prompt}]},
            "contents": [{"role": "user", "parts": [{"text": user_text}]}],
            "generationConfig": {"temperature": 0.0, "responseMimeType": "application/json"},
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
        return parse_image_prompt_review(text)

    async def health(self) -> bool:
        """키가 유효한지 가벼운 호출(모델 목록)로 확인 — capability 광고 판단용."""
        url = f"{GEMINI_API_BASE}/models"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(url, headers={"x-goog-api-key": self._key}) as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False
