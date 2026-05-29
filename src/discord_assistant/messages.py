"""사용자 대면 문자열 번역 카탈로그 + ``t()`` 헬퍼 (#87 i18n).

기본 언어(ko)는 **기존 한국어 문자열을 100% 그대로** 렌더한다(기존 테스트가
한국어 문자열을 단언하므로 회귀 금지). en/ja/zh/fr/de/es 등은 카탈로그에 번역이
있으면 그 번역을, 없으면 ko(원문)로 폴백한다.

설계 원칙:
- 언어 코드 체계는 ``prompts._LANGUAGE_LABELS`` 를 단일 출처(SSOT)로 재사용한다
  (#98 처럼 카탈로그 중복 생성 금지). ``prompts._LANGUAGE_ALIASES`` 도 함께 본다.
- 카탈로그는 ``MESSAGES[key][lang] = 문자열`` 형태. ``ko`` 는 필수, ``en`` 은
  핵심 표면에 최소한으로 제공한다. 그 외 언어는 비워 두어 ko 폴백을 타게 한다.
- ``t(key, lang, **kwargs)`` 는 선택한 언어 문자열을 찾아 ``str.format(**kwargs)``
  로 포맷한다. 포맷 인자가 없으면 원문을 그대로 돌려준다.
"""

from __future__ import annotations

from .prompts import _LANGUAGE_ALIASES, _LANGUAGE_LABELS

# ---------------------------------------------------------------------------
# 번역 카탈로그
#
# 키 네이밍 규칙: "<표면>.<항목>" 형태(예: "welcome.title", "settings.field.model").
# ko 는 반드시 채우고(원문 그대로), en 은 핵심 사용자 대면 문자열에 제공한다.
# 누락된 (key, lang) 은 ko 로 폴백한다.
# ---------------------------------------------------------------------------

MESSAGES: dict[str, dict[str, str]] = {
    # --- /settings 메인 임베드 ---
    "settings.title": {
        "ko": "서버 AI 설정",
        "en": "Server AI Settings",
    },
    "settings.field.provider": {
        "ko": "제공자",
        "en": "Provider",
    },
    "settings.field.model": {
        "ko": "모델",
        "en": "Model",
    },
    "settings.field.api_key": {
        "ko": "API 키",
        "en": "API Key",
    },
    "settings.field.language": {
        "ko": "언어",
        "en": "Language",
    },
    "settings.field.summary_limit": {
        "ko": "요약 범위",
        "en": "Summary Range",
    },
    "settings.summary_limit.value": {
        "ko": "{count}개 메시지",
        "en": "{count} messages",
    },
    "settings.footer": {
        "ko": "이 서버에만 적용 • 관리자 전용",
        "en": "Applies to this server only • Admins only",
    },
    "settings.api_key.na": {
        "ko": "N/A",
        "en": "N/A",
    },
    "settings.api_key.registered": {
        "ko": "✅ 등록됨 (●●●●●●)",
        "en": "✅ Registered (●●●●●●)",
    },
    "settings.api_key.missing": {
        "ko": "⚠️ 미등록",
        "en": "⚠️ Not registered",
    },
    # --- 일반 설정 임베드 ---
    "general.title": {
        "ko": "⚙️  일반 설정",
        "en": "⚙️  General Settings",
    },
    "general.field.language": {
        "ko": "🌐  응답 언어",
        "en": "🌐  Response Language",
    },
    "general.field.summary_limit": {
        "ko": "📊  요약 범위",
        "en": "📊  Summary Range",
    },
    "general.footer": {
        "ko": "ko · en · ja · zh · fr · de · es 등 지원 언어 중에서 선택하세요",
        "en": "Pick a supported language: ko · en · ja · zh · fr · de · es",
    },
    # --- 제공자 변경 임베드 ---
    "provider.title": {
        "ko": "AI 제공자 변경",
        "en": "Change AI Provider",
    },
    "provider.description": {
        "ko": (
            "**Ollama (로컬)** — 인터넷 불필요, 내 PC에서 직접 실행\n"
            "**OpenAI (GPT)** — ChatGPT API 키 필요\n"
            "**Anthropic (Claude)** — Claude API 키 필요\n"
            "**Google (Gemini)** — Gemini API 키 필요"
        ),
        "en": (
            "**Ollama (local)** — no internet needed, runs on your PC\n"
            "**OpenAI (GPT)** — requires a ChatGPT API key\n"
            "**Anthropic (Claude)** — requires a Claude API key\n"
            "**Google (Gemini)** — requires a Gemini API key"
        ),
    },
    "provider.field.current": {
        "ko": "현재",
        "en": "Current",
    },
    "provider.footer": {
        "ko": "OpenAI / Anthropic / Gemini 선택 시 API 키 입력이 필요합니다",
        "en": "Selecting OpenAI / Anthropic / Gemini requires an API key",
    },
    # --- 외부 모델 설정 임베드 ---
    "external.title": {
        "ko": "{emoji}  {provider} 설정",
        "en": "{emoji}  {provider} Settings",
    },
    "external.field.current_model": {
        "ko": "현재 모델",
        "en": "Current Model",
    },
    "external.api_key.registered": {
        "ko": "✅ 등록됨",
        "en": "✅ Registered",
    },
    "external.api_key.missing": {
        "ko": "⚠️ 미등록 — 아래 버튼으로 등록하세요",
        "en": "⚠️ Not registered — use the button below to register",
    },
    # --- 언어 선택 임베드 ---
    "language_select.title": {
        "ko": "🌐  응답 언어 선택",
        "en": "🌐  Select Response Language",
    },
    "language_select.description": {
        "ko": "아래 드롭다운에서 봇이 응답할 언어를 선택하세요.",
        "en": "Choose the language the bot replies in from the dropdown below.",
    },
    "language_select.field.current": {
        "ko": "현재 언어",
        "en": "Current Language",
    },
    "language_select.footer": {
        "ko": "'자동 감지'를 선택하면 대화 언어를 자동으로 따라갑니다",
        "en": "Choose 'Auto-detect' to follow the conversation's language",
    },
    # --- HelpView / /help 명령 ---
    "help.title": {
        "ko": "명령어 안내",
        "en": "Command Guide",
    },
    "help.footer": {
        "ko": "버튼을 눌러 섹션별 상세 안내를 볼 수 있습니다.",
        "en": "Press a button to see detailed guidance per section.",
    },
    "help.section.ai.title": {
        "ko": "AI 기능",
        "en": "AI Features",
    },
    "help.section.analysis.title": {
        "ko": "채널 분석",
        "en": "Channel Analysis",
    },
    "help.section.settings.title": {
        "ko": "설정",
        "en": "Settings",
    },
    "help.button.ai": {
        "ko": "AI 기능",
        "en": "AI Features",
    },
    "help.button.analysis": {
        "ko": "채널 분석",
        "en": "Channel Analysis",
    },
    "help.button.settings": {
        "ko": "설정",
        "en": "Settings",
    },
    "help.button.close": {
        "ko": "닫기",
        "en": "Close",
    },
    "help.button.dashboard": {
        "ko": "대시보드 열기",
        "en": "Open Dashboard",
    },
    "help.closed.title": {
        "ko": "도움말 닫힘",
        "en": "Help Closed",
    },
    "help.closed.description": {
        "ko": "다시 보려면 `/help`를 입력하세요.",
        "en": "Type `/help` to view it again.",
    },
    "help.field.summarize.value": {
        "ko": (
            "채널의 최근 대화를 AI가 요약합니다.\n"
            "```\n/summarize\n/summarize limit:100\n```"
        ),
        "en": (
            "The AI summarizes the channel's recent conversation.\n"
            "```\n/summarize\n/summarize limit:100\n```"
        ),
    },
    "help.field.ask.value": {
        "ko": (
            "채널의 최근 대화에서 근거를 찾아 질문에 답합니다.\n"
            "```\n/ask question:오늘 회의 결론이 뭐야?\n```"
        ),
        "en": (
            "Answers your question using evidence from the recent conversation.\n"
            "```\n/ask question:What was today's meeting conclusion?\n```"
        ),
    },
    "help.field.chat.value": {
        "ko": (
            "채널 맥락 없이 AI에게 자유롭게 질문합니다.\n"
            "```\n/chat message:파이썬 리스트 컴프리헨션 설명해줘\n```"
        ),
        "en": (
            "Chat freely with the AI without channel context.\n"
            "```\n/chat message:Explain Python list comprehensions\n```"
        ),
    },
    "help.field.translate.value": {
        "ko": (
            "텍스트를 지정 언어로 번역합니다.\n"
            "```\n/translate text:Hello target_language:ko\n```"
        ),
        "en": (
            "Translates text into a target language.\n"
            "```\n/translate text:Hello target_language:ko\n```"
        ),
    },
    "help.field.mention.value": {
        "ko": (
            "봇을 멘션하면 채널 대화를 요약합니다.\n"
            "멘션 뒤에 질문을 쓰면 `/ask` 처럼 동작합니다."
        ),
        "en": (
            "Mention the bot to summarize the channel conversation.\n"
            "Add a question after the mention to act like `/ask`."
        ),
    },
    "help.field.settings.value": {
        "ko": "AI 제공자, 모델, 언어, 요약 범위 등 서버 설정을 변경합니다.",
        "en": "Change server settings: AI provider, model, language, summary range.",
    },
    "help.field.settings.name": {
        "ko": "/settings  (관리자 전용)",
        "en": "/settings  (admins only)",
    },
    # --- 환영(온보딩) 메시지 ---
    "welcome.title": {
        "ko": "Discord AI Assistant에 오신 것을 환영합니다!",
        "en": "Welcome to Discord AI Assistant!",
    },
    "welcome.description": {
        "ko": "저는 채널 대화를 요약하고 질문에 답하는 AI 어시스턴트입니다.",
        "en": "I'm an AI assistant that summarizes channel conversations and answers questions.",
    },
    "welcome.field.summarize": {
        "ko": "채널 대화 요약",
        "en": "Summarize the channel conversation",
    },
    "welcome.field.ask": {
        "ko": "채널 대화 기반 Q&A",
        "en": "Q&A grounded in the channel conversation",
    },
    "welcome.field.chat": {
        "ko": "자유 대화",
        "en": "Free-form chat",
    },
    "welcome.field.settings": {
        "ko": "서버 설정 (관리자 전용) — `/settings`로 AI 제공자와 모델을 설정하세요.",
        "en": "Server settings (admins only) — use `/settings` to set the AI provider and model.",
    },
    "welcome.footer": {
        "ko": "/help 명령어로 전체 안내를 볼 수 있어요.",
        "en": "Use the /help command to see the full guide.",
    },
    # --- 응답 헤더(요약 등) ---
    "summary.header": {
        "ko": "**최근 {count}개 메시지 요약{since}**\n",
        "en": "**Summary of the last {count} messages{since}**\n",
    },
    "summary.header.cached": {
        "ko": "**최근 {count}개 메시지 요약** *(캐시)*\n",
        "en": "**Summary of the last {count} messages** *(cached)*\n",
    },
}


def _normalize_lang(lang: str | None) -> str:
    """언어 코드를 정규화한다(공백 제거·소문자·레거시 별칭 흡수).

    prompts._LANGUAGE_ALIASES(kr→ko, jp→ja)를 재사용해 저장된 레거시 코드도
    올바르게 매핑한다. 'auto' 는 카탈로그 키가 아니므로 ko 로 취급한다(번역 텍스트는
    실제 응답 언어가 아니라 UI 표면 언어 기준이며, auto 는 ko 폴백이 안전하다).
    """
    key = (lang or "").strip().lower()
    key = _LANGUAGE_ALIASES.get(key, key)
    if key == "auto" or not key:
        return "ko"
    return key


def t(key: str, lang: str | None = "ko", **kwargs: object) -> str:
    """카탈로그에서 ``key`` 의 ``lang`` 번역을 찾아 포맷해 돌려준다 (#87).

    폴백 규칙:
      1) 지원 언어가 아니면 ko 로 폴백한다.
      2) 해당 언어 번역이 없으면 ko 로 폴백한다.
      3) ko 조차 없으면(키 오타 등) ``key`` 자체를 돌려준다(디버깅 가시성).

    ``kwargs`` 가 주어지면 ``str.format(**kwargs)`` 로 치환한다. 포맷 인자가 없으면
    원문을 그대로 돌려준다(중괄호가 포함된 정적 문자열도 안전).
    """
    normalized = _normalize_lang(lang)
    entry = MESSAGES.get(key)
    if entry is None:
        # 등록되지 않은 키 — 키 문자열을 그대로 돌려줘 누락을 눈에 띄게 한다.
        return key
    # 지원 언어 목록(ko 포함)에 없는 코드는 ko 로 폴백.
    if normalized not in _LANGUAGE_LABELS:
        normalized = "ko"
    text = entry.get(normalized)
    if not text:
        # 해당 언어 번역 누락(None) 또는 빈 문자열 → ko 폴백 → (혹시 ko 도 없으면) key.
        text = entry.get("ko") or key
    if kwargs:
        try:
            return text.format(**kwargs)
        except (KeyError, IndexError, ValueError):
            # 포맷 인자 불일치/잘못된 포맷 스펙은 원문 그대로 돌려줘
            # 렌더 자체는 깨지지 않게 한다.
            return text
    return text
