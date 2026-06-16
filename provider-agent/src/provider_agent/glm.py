"""클라우드 GLM 호출 — 관리자 클라우드 백엔드(z.ai, OpenAI 호환 API).

관리자가 z.ai API 키 하나(``ZAI_API_KEY``)를 넣으면 ``glm-5.1`` 같은 모델이 풀에 공급
모델로 광고된다. Ollama(로컬)와 **동일한 (text, Usage) 인터페이스**라 에이전트가 같은
라우팅·한도·공정성으로 처리한다.

z.ai 는 OpenAI 호환이라 ``POST {base}/chat/completions`` 에 ``{"model","messages",...}`` 를
보내고 ``choices[0].message.content`` 를 받는다(이전 Gemini 백엔드를 대체).

키는 프로바이더(관리자) PC 에만 저장하고 central 엔 절대 올리지 않는다(Ollama 토큰과 동일 원칙).
"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass

import aiohttp

from . import sslutil
from .protocol import Usage

logger = logging.getLogger("provider_agent.glm")

GLM_API_BASE = "https://api.z.ai/api/paas/v4"
DEFAULT_GLM_MODEL = "glm-5.1"

# 사용자(디스코드)에게 노출되는 일반화 메시지. 업스트림 status·body 등 상세는 로그로만 남긴다.
_USER_ERROR_MESSAGE = "클라우드 AI 일시 오류"

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


class GlmError(Exception):
    """GLM(z.ai) 호출/응답 오류."""


@dataclass(frozen=True, slots=True)
class ImagePromptReview:
    allowed: bool
    category: str
    reason: str


def _extract_content(data: object) -> str | None:
    """chat/completions 응답에서 첫 choice 의 message.content. 비정상/빈 응답이면 None."""
    if not isinstance(data, dict):
        return None
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        return None
    message = choices[0].get("message")
    content = message.get("content") if isinstance(message, dict) else None
    return content if isinstance(content, str) and content else None


def _extract_usage(data: object) -> Usage:
    """OpenAI 호환 usage(prompt_tokens/completion_tokens) → 도메인 Usage."""
    um = data.get("usage", {}) if isinstance(data, dict) else {}
    if not isinstance(um, dict):
        um = {}
    return Usage(
        prompt_tokens=int(um.get("prompt_tokens", 0) or 0),
        completion_tokens=int(um.get("completion_tokens", 0) or 0),
    )


def _extract_json_object(text: str) -> dict[str, object]:
    """GLM 의 JSON 응답을 엄격히 dict 로 파싱한다. 코드펜스가 섞여도 첫 object 만 허용."""
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
    """GLM 안전 심사 JSON → 도메인 값. 스키마가 조금이라도 깨지면 fail-closed 예외."""
    try:
        data = _extract_json_object(text)
    except (json.JSONDecodeError, ValueError) as exc:
        raise GlmError(f"이미지 안전 심사 응답 파싱 실패: {exc}") from exc
    allowed = data.get("allowed")
    if not isinstance(allowed, bool):
        raise GlmError("이미지 안전 심사 응답에 allowed(boolean)가 없습니다")
    category = str(data.get("category") or ("safe" if allowed else "other")).strip()[:40]
    reason = str(data.get("reason") or ("허용됨" if allowed else "안전 정책상 차단됨")).strip()
    return ImagePromptReview(allowed=allowed, category=category, reason=reason[:240])


class GlmClient:
    def __init__(self, api_key: str, timeout: float = 120.0, *, base_url: str = GLM_API_BASE) -> None:
        self._key = api_key
        self._base = (base_url or GLM_API_BASE).rstrip("/")
        self._timeout = aiohttp.ClientTimeout(total=timeout)

    async def _chat(self, messages: list[dict[str, str]], model: str | None, **extra: object) -> dict[str, object]:
        """chat/completions 한 번 호출 → 원시 응답 dict. 오류 시 GlmError(원인 보존)."""
        m = (model or DEFAULT_GLM_MODEL).strip()
        url = f"{self._base}/chat/completions"
        payload: dict[str, object] = {"model": m, "messages": messages, **extra}
        headers = {"Authorization": f"Bearer {self._key}", "Content-Type": "application/json"}
        try:
            async with aiohttp.ClientSession(
                timeout=self._timeout, connector=aiohttp.TCPConnector(ssl=sslutil.ssl_context())
            ) as s:
                async with s.post(url, json=payload, headers=headers) as r:
                    if r.status >= 400:
                        body = await r.text()
                        # 업스트림 원문(status·body)은 정보 노출 소지가 있어 로그로만 남기고,
                        # 사용자에겐 일반화된 메시지만 전달한다(예외 원칙 — 내부 상세 미노출).
                        logger.warning("GLM HTTP %s: %s", r.status, body[:500])
                        raise GlmError(_USER_ERROR_MESSAGE)
                    data = await r.json()
        except aiohttp.ClientError as exc:
            logger.warning("GLM 연결 실패: %s", exc)
            raise GlmError(_USER_ERROR_MESSAGE) from exc
        if not isinstance(data, dict):
            raise GlmError("GLM 응답 형식이 올바르지 않습니다")
        if data.get("error"):
            err = data["error"]
            msg = err.get("message") if isinstance(err, dict) else str(err)
            # 업스트림 에러 메시지도 원문 노출하지 않고 로그로만 남긴다.
            logger.warning("GLM 업스트림 오류: %s", msg)
            raise GlmError(_USER_ERROR_MESSAGE)
        return data

    async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
        """프롬프트를 GLM 으로 추론해 (text, Usage) 반환. 오류 시 GlmError."""
        data = await self._chat([{"role": "user", "content": prompt}], model)
        text = _extract_content(data)
        if text is None:
            raise GlmError("GLM 응답에 텍스트가 없습니다(안전 필터 차단 또는 빈 응답)")
        return text.strip(), _extract_usage(data)

    async def translate(self, user_text: str, system_prompt: str, model: str | None = None) -> str:
        """system_prompt 규칙에 따라 user_text 를 변환(이미지 프롬프트 번역용). 오류 시 GlmError.

        '성인·SFW·품질 prefix' 정책을 system 메시지로 강제해, 정상적인 SFW 요청이 미성년
        오탐으로 거부되지 않게 한다(거부는 central 정책 소유, 실행만 여기서).
        """
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_text},
        ]
        data = await self._chat(messages, model, temperature=0.7)
        text = _extract_content(data)
        if text is None:
            raise GlmError("GLM 응답에 텍스트가 없습니다(안전 필터 차단 또는 빈 응답)")
        return text.strip()

    async def review_image_prompt(
        self,
        user_text: str,
        system_prompt: str = IMAGE_PROMPT_REVIEW_SYSTEM_PROMPT,
        model: str | None = None,
    ) -> ImagePromptReview:
        """공개 봇용 이미지 프롬프트 안전 심사. 실패/비정상 응답은 호출부가 fail-closed 한다."""
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_text},
        ]
        data = await self._chat(
            messages, model, temperature=0.0, response_format={"type": "json_object"}
        )
        text = _extract_content(data)
        if text is None:
            raise GlmError("GLM 응답에 텍스트가 없습니다(안전 필터 차단 또는 빈 응답)")
        return parse_image_prompt_review(text)

    async def health(self) -> bool:
        """키가 유효한지 가벼운 호출(모델 목록)로 확인 — capability 광고 판단용."""
        url = f"{self._base}/models"
        try:
            async with aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=10), connector=aiohttp.TCPConnector(ssl=sslutil.ssl_context())
            ) as s:
                async with s.get(url, headers={"Authorization": f"Bearer {self._key}"}) as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False
