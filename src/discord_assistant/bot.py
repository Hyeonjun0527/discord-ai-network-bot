"""discord.py entrypoint and command handlers."""
from __future__ import annotations

import asyncio
import contextvars
import hashlib
import io
import json
import logging
import os
import re
import signal
import sys
from datetime import datetime, timedelta, timezone
from time import perf_counter
from typing import TYPE_CHECKING, Any

import discord
from discord import app_commands
from discord.ext import commands, tasks

from . import metrics, observability
from .cache import get_translation, set_translation, summarize_cache
from .context import build_transcript, from_discord_message, normalize_content
from .crypto import CryptoError, decrypt_api_key
from .health import HealthServer
from .llm import (
    AnthropicClient,
    BaseLLMClient,
    GeminiClient,
    ImageInput,
    LLMError,
    OllamaClient,
    OllamaManager,
    OpenAIClient,
    ToolSpec,
    supports_vision,
)
from .messages import t
from .models import GuildConfig, LLMProvider, Reminder, UsageLog
from .monitor import format_disconnect_message, format_error_message, notify_developer
from .prompts import (
    _INJECTION_GUARD,
    _LANGUAGE_LABELS,
    _wrap_untrusted,
    build_ask_prompt,
    build_chat_prompt,
    build_chat_with_history_prompt,
    build_image_analysis_prompt,
    build_search_result_prompt,
    build_summarize_prompt,
    build_translate_prompt,
    detect_language_from_transcript,
)
from .settings import AppSettings
from .storage import ConfigStore
from .ui import (
    ChannelSelectView,
    FollowUpView,
    HelpView,
    LongResponseView,
    RetryView,
    SettingsView,
    ViewCtx,
    error_hint,
    settings_embed,
)

if TYPE_CHECKING:
    from collections.abc import Awaitable, Callable

logger = logging.getLogger(__name__)

_SLOW_RESPONSE_THRESHOLD_MS = 30_000


# ---------------------------------------------------------------------------
# #88: app_commands locale_str 현지화
# ---------------------------------------------------------------------------
#
# 슬래시 명령의 description / 옵션 설명을 app_commands.locale_str 로 감싸고,
# discord.app_commands.Translator 를 구현해 클라이언트 로케일(ko/en)에 맞춰
# 표시되도록 한다. 카탈로그에 없는 문자열이나 미지원 로케일은 None 을 반환해
# 기본(원문, 한국어) 문자열로 자연스럽게 폴백한다. 명령 이름/동작은 불변이다.


def _loc(text: str) -> "app_commands.locale_str":
    """description/옵션 설명을 현지화 가능한 locale_str 로 감싸는 단축 헬퍼 (#88).

    원문(한국어)을 그대로 message 로 보관하므로, 번역 카탈로그에 항목이 없거나
    번역기가 비활성/미등록이어도 원문이 그대로 표시된다(백워드 호환).
    """
    return app_commands.locale_str(text)


# 원문(한국어) → 영어 번역 카탈로그 (#88). 키는 _loc 에 넘긴 원문과 정확히 일치해야
# 한다. 여기 없는 문자열은 영어 로케일에서도 원문(한국어)으로 폴백한다.
_COMMAND_TRANSLATIONS_EN: dict[str, str] = {
    # --- 명령 설명 ---
    "봇 설정 패널을 엽니다. (관리자 전용)": "Open the bot settings panel. (admins only)",
    "최근 채널 대화를 로컬 LLM으로 요약합니다.": "Summarize the recent channel conversation with the LLM.",
    "최근 채널 대화 맥락으로 질문에 답합니다.": "Answer a question using the recent channel context.",
    "선택 기능: 짧은 텍스트를 지정 언어로 번역합니다.": "Optional: translate a short text into a target language.",
    "채널 맥락 없이 AI에게 자유롭게 질문합니다.": "Chat freely with the AI without channel context.",
    "봇 명령어 사용법을 안내합니다.": "Show how to use the bot's commands.",
    "지정한 시간 뒤 DM으로 알림을 보냅니다. (메시지 미지정 시 마지막 요약)": (
        "Send a DM reminder after a delay. (uses last summary if no message given)"
    ),
    "내 예약 알림 목록을 보고 취소합니다.": "View and cancel your scheduled reminders.",
    "내 데이터를 모두 삭제합니다. (되돌릴 수 없음)": "Delete all of your data. (irreversible)",
    "요약을 실행하고 결과를 채널에 고정합니다.": "Summarize and pin the result to the channel.",
    "여러 채널을 선택해 통합 요약합니다.": "Select multiple channels and summarize them together.",
    "채널 메시지를 마크다운 파일로 내보내기 (DM 전송)": (
        "Export channel messages to a markdown file (sent via DM)."
    ),
    "서버 봇 사용 통계를 표시합니다.": "Show this server's bot usage statistics.",
    "채널에서 키워드로 메시지를 검색하고 요약합니다.": (
        "Search the channel by keyword and summarize the matches."
    ),
    "지정한 기간의 대화를 핵심·결정·액션으로 정리합니다.": (
        "Digest a time range into key points, decisions, and action items."
    ),
    "내 사용량과 쿨다운, 서버 한도를 확인합니다.": (
        "Check your usage, cooldown, and the server's limits."
    ),
    "서버별 봇 설정을 관리합니다.": "Manage per-server bot settings.",
    "서버 기본 Ollama 모델명을 저장합니다.": "Save the server's default Ollama model name.",
    "기본 메시지 요약 범위를 저장합니다.": "Save the default message summary range.",
    "기본 응답 언어를 저장합니다.": "Save the default response language.",
    "봇 설정 권한을 가진 역할을 지정합니다.": "Set the role allowed to manage bot settings.",
    "/chat 페르소나를 설정합니다.": "Set the /chat persona.",
    "자동 요약 간격을 설정합니다. (최소 5분, 0이면 비활성화)": (
        "Set the auto-summary interval. (min 5 minutes, 0 to disable)"
    ),
    "커스텀 프롬프트를 설정합니다.": "Set a custom prompt.",
    "명령어 사용 가능 역할을 설정합니다.": "Set which role may use the commands.",
    "서버의 일일 토큰 사용 상한을 설정합니다. (0이면 무제한)": (
        "Set the server's daily token usage cap. (0 = unlimited)"
    ),
    # --- 옵션 설명 ---
    "최근 몇 개 메시지를 읽을지 지정합니다. 기본값은 서버 설정입니다.": (
        "How many recent messages to read. Defaults to the server setting."
    ),
    "시간 기반 필터. 예: 1h, 30m, 2d": "Time-based filter. e.g. 1h, 30m, 2d",
    "True면 요약 결과를 채널에 새 스레드를 만들어 게시합니다.": (
        "If true, post the summary in a new thread in the channel."
    ),
    "최근 대화에 대해 물어볼 질문입니다.": "The question to ask about the recent conversation.",
    "True면 AI가 필요 시 채널에서 추가 메시지를 검색합니다. (OpenAI/Anthropic)": (
        "If true, the AI may search the channel for more messages. (OpenAI/Anthropic)"
    ),
    "번역할 텍스트": "Text to translate",
    "목표 언어입니다. 예: ko, en, ja": "Target language. e.g. ko, en, ja",
    "AI에게 보낼 메시지입니다.": "The message to send to the AI.",
    "True로 설정하면 채널에 공개 메시지로 표시됩니다. 기본값은 비공개입니다.": (
        "If true, post a public message in the channel. Defaults to private."
    ),
    "언제 보낼지. 예: 30m, 2h, 1d (단위 없으면 분). 최대 30일.": (
        "When to send. e.g. 30m, 2h, 1d (minutes if no unit). Max 30 days."
    ),
    "알림으로 받을 임의 텍스트. 비우면 마지막 /summarize 결과를 사용합니다.": (
        "Free text to be reminded of. Empty uses your last /summarize result."
    ),
    "(선택) 반복 표시용 라벨. 예: daily, weekly (실제 반복 없이 표시만)": (
        "(optional) Repeat label for display only. e.g. daily, weekly"
    ),
    "취소할 알림의 ID. 비우면 목록만 표시합니다.": (
        "ID of the reminder to cancel. Empty shows the list only."
    ),
    "최근 몇 개 메시지를 요약할지 지정합니다.": "How many recent messages to summarize.",
    "내보낼 메시지 수 (기본값: 서버 설정)": "Number of messages to export (default: server setting).",
    "검색할 키워드입니다.": "The keyword to search for.",
    "최대 몇 개 메시지를 검색할지 지정합니다. 기본값: 200": (
        "Max number of messages to search. Default: 200."
    ),
    "정리할 기간. 예: 30m, 1h, 6h, 1d (기본: 1d)": (
        "Time range to digest. e.g. 30m, 1h, 6h, 1d (default: 1d)"
    ),
    "예: llama3.1:8b, qwen2.5:7b, gemma2:9b": "e.g. llama3.1:8b, qwen2.5:7b, gemma2:9b",
    "1~200 사이의 메시지 개수": "A number of messages between 1 and 200.",
    "예: ko, en, ja": "e.g. ko, en, ja",
    "설정 권한을 부여할 역할입니다.": "The role to grant settings permission.",
    "봇의 페르소나 설명입니다. 비워두면 초기화합니다.": (
        "The bot's persona description. Empty resets it."
    ),
    "자동 요약 간격 (분, 최소 5). 0이면 비활성화.": (
        "Auto-summary interval in minutes (min 5). 0 disables it."
    ),
    "프롬프트 유형: summarize 또는 ask": "Prompt type: summarize or ask.",
    "커스텀 프롬프트 내용. 비워두면 초기화.": "Custom prompt text. Empty resets it.",
    "명령어를 사용할 수 있는 역할입니다.": "The role allowed to use the commands.",
    "하루 동안 사용할 수 있는 최대 토큰 수. 0이면 무제한.": (
        "Max tokens usable per day. 0 = unlimited."
    ),
}

# locale_str.message → {locale_code: translated} 형태의 다국어 카탈로그 (#88).
# 현재는 영어(en 계열)만 채운다. ko 는 원문이 한국어이므로 카탈로그가 없어도
# 그대로 표시된다. 다른 언어는 None 폴백(원문 표시).
_TRANSLATION_CATALOG: dict[str, dict[str, str]] = {
    src: {"en": en} for src, en in _COMMAND_TRANSLATIONS_EN.items()
}


def _locale_to_lang(locale: "discord.Locale") -> str | None:
    """discord.Locale 을 카탈로그 언어 키(en/ko)로 매핑한다 (#88).

    영어 계열(en-US/en-GB)은 'en', 한국어는 'ko'. 그 외는 None(원문 폴백).
    """
    value = str(getattr(locale, "value", locale))
    if value.startswith("en"):
        return "en"
    if value == "ko":
        return "ko"
    return None


class CommandTranslator(app_commands.Translator):
    """슬래시 명령 description/옵션 설명을 클라이언트 로케일로 현지화한다 (#88).

    discord.py 가 명령 동기화 시 각 locale_str 에 대해 모든 지원 로케일로
    translate() 를 호출한다. 카탈로그에 매칭되는 번역이 있으면 반환하고, 없으면
    None 을 반환해 원문(한국어)으로 폴백한다(미지원 로케일/미등록 문자열 안전).
    """

    async def translate(
        self,
        string: "app_commands.locale_str",
        locale: "discord.Locale",
        context: "app_commands.TranslationContextTypes",
    ) -> str | None:
        lang = _locale_to_lang(locale)
        if lang is None or lang == "ko":
            # 원문이 한국어이므로 ko/미지원 로케일은 폴백(None).
            return None
        return _TRANSLATION_CATALOG.get(string.message, {}).get(lang)

# --- #46 correlation id ---
# 명령마다 interaction.id 를 바인딩해 로그에 cid 를 끼워 넣는다. contextvars 는
# asyncio 태스크 경계를 넘어도 값을 안전하게 전파하므로 명령 핸들러 단위로 격리된다.
_correlation_id: contextvars.ContextVar[str] = contextvars.ContextVar(
    "correlation_id", default="-"
)


def get_correlation_id() -> str:
    """현재 컨텍스트에 바인딩된 correlation id 를 반환한다(없으면 '-')."""
    return _correlation_id.get()


def set_correlation_id(cid: str | int | None) -> None:
    """현재 컨텍스트에 correlation id 를 바인딩한다(_record_usage 등 핵심 경로용)."""
    _correlation_id.set(str(cid) if cid is not None else "-")


class CorrelationIdFilter(logging.Filter):
    """로그 레코드에 ``cid`` 속성을 주입하는 필터 (#46).

    포매터가 ``%(cid)s`` 를 참조할 수 있도록 모든 레코드에 현재 컨텍스트의
    correlation id 를 채운다. 이미 설정된 레코드는 덮어쓰지 않는다.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if not hasattr(record, "cid"):
            record.cid = get_correlation_id()
        return True


# --- #51 fire-and-forget 태스크 추적 ---
# create_task 로 띄운 태스크를 강한 참조로 보관해 GC 로 인한 조용한 소실을 막고,
# 완료 시 예외를 로깅한다(삼킴 방지).
_background_tasks: set[asyncio.Task[Any]] = set()


def _on_task_done(task: asyncio.Task[Any]) -> None:
    """추적 집합에서 태스크를 제거하고, 취소가 아닌 예외는 로깅한다 (#51)."""
    _background_tasks.discard(task)
    if task.cancelled():
        return
    exc = task.exception()
    if exc is not None:
        logger.exception(
            "백그라운드 태스크에서 예외 발생: %s", task.get_name(), exc_info=exc
        )


def _track_task(coro: Any, *, name: str | None = None) -> asyncio.Task[Any]:
    """코루틴을 태스크로 띄우고 추적 집합에 등록한다 (#51).

    asyncio.create_task 직접 호출을 대체해, 강한 참조 유지 + 예외 로깅을 한다.
    """
    task = asyncio.create_task(coro, name=name)
    _background_tasks.add(task)
    task.add_done_callback(_on_task_done)
    return task


# --- #27 retention 보존일 기본값 ---
# settings 에 별도 항목이 없으므로 상수로 둔다. usage_log 90일 / chat_history 30일.
RETENTION_USAGE_DAYS = 90
RETENTION_CHAT_DAYS = 30


def _truncate(text: str, limit: int = 1024) -> str:
    """Truncate text to embed field limit with ellipsis."""
    if len(text) <= limit:
        return text
    return text[: limit - 1] + "…"


def _overflow_view(text: str, *, limit: int = 1024) -> "discord.ui.View | None":
    """임베드 필드 한도(1024)를 넘는 답변에 'DM 으로 전체 받기' 버튼을 붙인다 (#8).

    /translate·/search 의 임베드 필드는 _truncate 로 1024 자에서 잘리는데, /ask·/chat
    과 달리 전체 내용을 받을 방법이 없어 초과분이 영구 손실된다. 한도를 넘으면
    LongResponseView(DM 버튼)를 돌려줘 일관된 오버플로 폴백을 제공한다(한도 이내면 None).
    """
    if len(text) <= limit:
        return None
    return LongResponseView(full_text=text)
MAX_DISCORD_MESSAGE_CHARS = 1900
MAX_EXPORT_BYTES = 8 * 1024 * 1024  # 8 MB Discord file limit
MAX_SEARCH_MATCHES = 20  # max matching messages shown in /search (#74)

# --- #13 이미지 첨부 다운로드 상한 ---
# 비전 모델로 보낼 첨부 이미지를 다운로드할 때의 안전 상한. 과도한 메모리/대역폭
# 사용과 비용 폭증을 막기 위해 장수·용량을 제한한다.
MAX_IMAGE_ATTACHMENTS = 3          # 한 번에 분석할 최대 이미지 수
MAX_IMAGE_BYTES = 8 * 1024 * 1024  # 개별 이미지 최대 8MB

# Reaction emojis for feedback tracking
THUMBS_UP = "\U0001f44d"   # 👍
THUMBS_DOWN = "\U0001f44e"  # 👎

# --- #9 리액션 트리거 이모지 ---
# 메시지에 아래 이모지를 달면 해당 메시지를 요약/번역해 답장한다. 👍/👎 피드백
# 경로(on_reaction_add)와는 별개로 on_raw_reaction_add 에서 처리한다.
REACTION_SUMMARIZE = "\U0001f4dd"  # 📝 요약
REACTION_TRANSLATE = "\U0001f310"  # 🌐 번역

# Message IDs that correspond to bot command results (for reaction tracking)
# guild_id -> {message_id -> command_name}
_tracked_messages: dict[int, dict[int, str]] = {}
_MAX_TRACKED_PER_GUILD = 500

# Last summarize results per user (for /remind)
# user_id -> (summary_text, guild_id)
_last_summaries: dict[int, tuple[str, int | None]] = {}
_MAX_LAST_SUMMARIES = 1000


def _store_last_summary(user_id: int, text: str, guild_id: int | None) -> None:
    """마지막 요약 결과를 user 캐시에 저장한다(진짜 LRU) (#39).

    dict 는 삽입 순서를 보존하므로, 재대입 전에 기존 키를 pop 한 뒤 다시 넣어
    최근 사용한 사용자를 맨 뒤로 보낸다. 그러면 한도 초과 시 evict 대상이
    '가장 오래전에 마지막으로 쓴' 사용자(맨 앞)가 되어 FIFO 가 아닌 LRU 가 된다.
    """
    _last_summaries.pop(user_id, None)
    _last_summaries[user_id] = (text, guild_id)
    if len(_last_summaries) > _MAX_LAST_SUMMARIES:
        del _last_summaries[next(iter(_last_summaries))]

# Auto-summary tracking: guild_id -> last_run_time
_auto_summary_last_run: dict[int, datetime] = {}

# Live reminder sleep tasks: reminder_id -> Task. Used to cancel a still-sleeping
# delivery task when the user cancels the reminder (#12) and to avoid scheduling
# the same reminder twice on reconnect-driven reschedule (#13).
_reminder_tasks: dict[int, asyncio.Task[Any]] = {}

# Cooldown tracking: (guild_id, user_id) -> last used timestamp (task 25)
_cooldowns: dict[tuple[int, int], float] = {}
COOLDOWN_SECONDS = 10
# Sentinel "guild" bucket for DM cooldowns (real guilds never use id 0). Passing
# None as guild_id short-circuits _check_cooldown, so DMs need a concrete key (#20).
_DM_COOLDOWN_GUILD = 0


class UserFacingError(RuntimeError):
    """Raised for errors that should be shown plainly to Discord users."""


def _sanitize_persona(text: str) -> str:
    """Mitigate prompt injection in admin-set persona text (#42).

    Strips control characters and collapses newlines so the persona cannot
    inject fake role delimiters (e.g. a forged "User:"/"System:" block) into
    the prompt. Returns single-line, trimmed text.
    """
    # Map any whitespace (incl. newlines/tabs) to a space, drop other control
    # chars, then collapse runs of whitespace into single spaces.
    chars: list[str] = []
    for ch in text:
        if ch.isspace():
            chars.append(" ")
        elif ord(ch) < 32 or ord(ch) == 127:
            continue
        else:
            chars.append(ch)
    return re.sub(r"\s+", " ", "".join(chars)).strip()


def _parse_since(since_str: str) -> datetime:
    """Parse a duration string like '1h', '30m', '2d' into a UTC datetime in the past.

    Raises UserFacingError on invalid format.
    """
    since_str = since_str.strip().lower()
    match = re.fullmatch(r"(\d+)([mhd])", since_str)
    if not match:
        raise UserFacingError("올바른 형식: 1h, 30m, 2d (숫자 + m/h/d)")
    value = int(match.group(1))
    if value == 0:
        raise UserFacingError("0은 허용되지 않습니다. 예: 1h, 30m, 1d")
    unit = match.group(2)
    if unit == "m":
        delta = timedelta(minutes=value)
    elif unit == "h":
        delta = timedelta(hours=value)
    else:
        delta = timedelta(days=value)
    return datetime.now(timezone.utc) - delta


# --- #7 자동완성 후보 ---
# 자유 텍스트 인자(언어/기간/프롬프트 유형)에 슬래시 명령 자동완성을 붙여
# 오타·미지원 값을 줄인다. discord.py 의 app_commands.autocomplete 가 호출하는
# 콜백은 최대 25개의 Choice 만 반환할 수 있으므로 항상 슬라이스한다.

# 자동완성에 노출할 기간(since) 후보. _parse_since 가 받아들이는 형식과 일치한다.
_SINCE_CHOICES: list[tuple[str, str]] = [
    ("최근 30분", "30m"),
    ("최근 1시간", "1h"),
    ("최근 6시간", "6h"),
    ("최근 12시간", "12h"),
    ("최근 1일", "1d"),
    ("최근 3일", "3d"),
    ("최근 7일", "7d"),
]

# 커스텀 프롬프트 유형 후보 (set_custom_prompt 가 받는 값과 일치).
_PROMPT_TYPE_CHOICES: list[tuple[str, str]] = [
    ("summarize (요약)", "summarize"),
    ("ask (질문)", "ask"),
]


def _filter_choices(
    pairs: list[tuple[str, str]], current: str
) -> list[app_commands.Choice[str]]:
    """(라벨, 값) 목록을 현재 입력으로 필터링해 Choice 리스트(최대 25개)로 변환한다 (#7)."""
    needle = (current or "").strip().lower()
    out: list[app_commands.Choice[str]] = []
    for label, value in pairs:
        if not needle or needle in label.lower() or needle in value.lower():
            out.append(app_commands.Choice(name=label, value=value))
        if len(out) >= 25:
            break
    return out


async def _language_autocomplete(
    interaction: discord.Interaction, current: str
) -> list[app_commands.Choice[str]]:
    """언어 코드 자동완성: _LANGUAGE_LABELS + 'auto' 자동 감지 (#7)."""
    pairs: list[tuple[str, str]] = [("자동 감지 (auto)", "auto")]
    pairs.extend((f"{label} ({code})", code) for code, label in _LANGUAGE_LABELS.items())
    return _filter_choices(pairs, current)


async def _since_autocomplete(
    interaction: discord.Interaction, current: str
) -> list[app_commands.Choice[str]]:
    """기간(since) 자동완성: 30m/1h/6h/1d 등 (#7)."""
    return _filter_choices(_SINCE_CHOICES, current)


async def _prompt_type_autocomplete(
    interaction: discord.Interaction, current: str
) -> list[app_commands.Choice[str]]:
    """프롬프트 유형 자동완성: summarize/ask (#7)."""
    return _filter_choices(_PROMPT_TYPE_CHOICES, current)


_MAX_REMIND_DELAY = timedelta(days=30)


def _parse_remind_delay(when: str) -> timedelta:
    """'10', '30m', '2h', '1d' 형태의 지연 시간을 timedelta 로 파싱한다 (#2).

    단위가 없으면 분으로 해석한다(기존 N분 입력과의 호환). 단위 최소 단위가 분이므로
    실제 최소 지연은 1분이다. 0이거나 최대 허용치(30일)를 넘으면 UserFacingError 를
    발생시킨다.

    #19: ``\\d`` 는 아랍-인도 숫자 등 유니코드 숫자까지 매칭해 의도와 다른 지연이
    설정될 수 있으므로 ASCII 숫자([0-9])만 허용한다.
    """
    text = when.strip().lower()
    match = re.fullmatch(r"([0-9]+)\s*([mhd]?)", text)
    if not match:
        raise UserFacingError("올바른 형식: 30m, 2h, 1d 또는 분 단위 숫자 (예: 10)")
    value = int(match.group(1))
    if value == 0:
        raise UserFacingError("0은 허용되지 않습니다. 예: 30m, 2h, 1d")
    unit = match.group(2) or "m"
    if unit == "m":
        delta = timedelta(minutes=value)
    elif unit == "h":
        delta = timedelta(hours=value)
    else:
        delta = timedelta(days=value)
    if delta > _MAX_REMIND_DELAY:
        raise UserFacingError("알림은 최대 30일 후까지만 예약할 수 있어요.")
    return delta


def _has_allowed_role(interaction: discord.Interaction, allowed_role_id: int | None) -> bool:
    """Return True if no role restriction, or user has the required role."""
    if allowed_role_id is None:
        return True
    roles = getattr(interaction.user, "roles", [])
    return any(getattr(role, "id", None) == allowed_role_id for role in roles)


def _member_has_allowed_role(member: Any, allowed_role_id: int | None) -> bool:
    """역할 제한 우회 방지: member(또는 user) 객체의 roles 로 권한을 검사한다 (#24/#90).

    슬래시 명령 외의 LLM 진입점(@멘션·답장·리액션)은 interaction 이 없으므로
    ``_has_allowed_role`` 대신 message.author / payload.member 를 직접 받는다.
    제한이 없으면(allowed_role_id is None) 항상 True. member 가 None 이거나 roles
    를 알 수 없으면(권한 미확인) 보수적으로 False 를 반환해 우회를 막는다.
    """
    if allowed_role_id is None:
        return True
    if member is None:
        return False
    roles = getattr(member, "roles", None)
    if not roles:
        return False
    return any(getattr(role, "id", None) == allowed_role_id for role in roles)


_COOLDOWN_CLEANUP_INTERVAL = 300  # clean up expired entries every 5 minutes
_cooldown_last_cleanup: float = 0.0


def reset_cooldowns() -> None:
    """Clear all cooldown state. Intended for test isolation (#83)."""
    global _cooldown_last_cleanup
    _cooldowns.clear()
    _cooldown_last_cleanup = 0.0


def _clear_cooldown(guild_id: int | None, user_id: int | None) -> None:
    """방금 진입에서 기록한 쿨다운을 롤백한다 (#3).

    _check_cooldown 은 진입 시 last-used 를 기록한다. 그 직후 시도가 실패해 재시도
    버튼을 제공할 때, 같은 (guild,user) 키가 아직 쿨다운 안이라 재시도 버튼이
    'N초 후에' 안내만 띄우고 실제로 실행되지 않는다. 실패 시 이 항목을 지워 재시도
    버튼이 바로 동작하게 한다(키가 없으면 no-op).
    """
    if guild_id is None or user_id is None:
        return
    _cooldowns.pop((guild_id, user_id), None)


def _check_cooldown(guild_id: int | None, user_id: int | None) -> float | None:
    """Return remaining cooldown seconds if on cooldown, else None. Updates last-used time.

    #7: ``_cooldowns`` 는 단일 프로세스 인메모리 상태다. 함수 본문에 await 가 없어
    asyncio 단일 스레드에서는 원자적이므로 락은 불필요하다. 다만 다중 프로세스/샤드로
    수평 확장하면 프로세스마다 쿨다운이 분리돼 우회될 수 있으니, 그 경우 공유 저장소
    (예: Redis)로 옮겨야 한다.
    """
    global _cooldown_last_cleanup
    if guild_id is None or user_id is None:
        return None
    key = (guild_id, user_id)
    now = perf_counter()
    # Periodically evict stale entries to prevent unbounded growth
    if now - _cooldown_last_cleanup > _COOLDOWN_CLEANUP_INTERVAL:
        expired = [k for k, t in _cooldowns.items() if now - t > COOLDOWN_SECONDS * 10]
        for k in expired:
            del _cooldowns[k]
        _cooldown_last_cleanup = now
    last = _cooldowns.get(key)
    if last is not None:
        elapsed = now - last
        if elapsed < COOLDOWN_SECONDS:
            return COOLDOWN_SECONDS - elapsed
    _cooldowns[key] = now
    return None


async def _track_for_feedback(
    guild_id: int | None,
    msg: discord.Message | None,
    command: str,
    *,
    add_reactions: bool = True,
) -> None:
    """Register a result message for 👍/👎 feedback and seed the reactions.

    Shared by /summarize (cache + live paths) and /ask so feedback tracking is
    consistent across commands (#71). ``msg`` is only non-None when the send used
    ``wait=True``; otherwise tracking is skipped (no message id to key on).
    """
    if guild_id is None or msg is None:
        return
    guild_tracking = _tracked_messages.setdefault(guild_id, {})
    guild_tracking[msg.id] = command
    if len(guild_tracking) > _MAX_TRACKED_PER_GUILD:
        oldest = sorted(guild_tracking)[: len(guild_tracking) - _MAX_TRACKED_PER_GUILD]
        for k in oldest:
            del guild_tracking[k]
    if add_reactions:
        # #41: 두 시드 리액션을 각각 격리해, 한쪽 실패가 다른 한쪽(과 이어지는
        # _record_usage 흐름)을 깨지 않게 한다. 레이트리밋/타임아웃도 흡수한다.
        for emoji in (THUMBS_UP, THUMBS_DOWN):
            try:
                await msg.add_reaction(emoji)
            except (discord.HTTPException, asyncio.TimeoutError):
                pass


def _make_error_embed(exc: Exception) -> discord.Embed:
    """예외를 사용자용 오류 임베드로 변환한다 (#92).

    - UserFacingError: 의도된 사용자 메시지를 그대로 보여준다.
    - LLMError 계열(타임아웃/연결/권한/키만료 등): ui.error_hint 로 유형별 친절
      복구 힌트를 만들어 보여준다. status_code(401/403/429/5xx) 와 구체 예외
      타입을 함께 보고 가장 행동 가능한 안내를 고른다.
    - 그 밖의 예기치 못한 오류: 내부 detail 은 숨기고 일반 재시도 안내만 한다.
    """
    if isinstance(exc, UserFacingError):
        description = str(exc)
        color = discord.Color.red()
    elif isinstance(exc, LLMError):
        # 유형별(타임아웃/연결/권한/키만료/레이트리밋) 친절 문구로 뭉뚱그리지 않는다.
        description = error_hint(exc)
        color = discord.Color.orange()
    else:
        description = "예기치 않은 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
        color = discord.Color.red()
        logger.debug("Suppressed error detail from user: %s", exc)
    return discord.Embed(
        title="오류",
        description=description,
        color=color,
    )


def _is_retryable_error(exc: Exception) -> bool:
    """재시도 버튼을 붙일 가치가 있는 오류인지 판정한다 (#92).

    LLMError 계열(연결/타임아웃/일시 서버 오류 등)은 재시도로 회복될 수 있다.
    단, 키/권한 문제(401/403)는 재시도해도 동일하게 실패하므로 제외한다(설정
    수정이 필요). UserFacingError 등 입력성 오류도 재시도 대상이 아니다.
    """
    if not isinstance(exc, LLMError):
        return False
    return getattr(exc, "status_code", None) not in (401, 403)


async def _send_error_embed(
    interaction: discord.Interaction,
    exc: Exception,
    *,
    retry: "Callable[[discord.Interaction], Awaitable[None]] | None" = None,
) -> None:
    """오류 임베드를 보낸다. 재시도 가능한 LLM 오류면 RetryView 를 붙인다 (#92).

    ``retry`` 콜백이 주어지고 ``exc`` 가 재시도 가치가 있으면 ``ui.RetryView`` 를
    함께 보내, 버튼 클릭만으로 마지막 작업을 다시 시도할 수 있게 한다.
    """
    embed = _make_error_embed(exc)
    view: discord.ui.View | None = None
    if retry is not None and _is_retryable_error(exc):
        view = RetryView(on_retry=retry)
    kwargs: dict[str, Any] = {"embed": embed, "ephemeral": True}
    if view is not None:
        kwargs["view"] = view
    if interaction.response.is_done():
        await interaction.followup.send(**kwargs)
    else:
        await interaction.response.send_message(**kwargs)


# --- #90 온보딩 강화 ---
# 봇이 바로 쓸 수 있는 상태인지(제공자·모델 설정 완료) 판정한다. 설정이 덜 됐으면
# on_guild_join 환영 메시지에 '지금 설정하기' 체크리스트/버튼을 덧붙인다.
_EXTERNAL_PROVIDERS = (LLMProvider.OPENAI, LLMProvider.ANTHROPIC, LLMProvider.GEMINI)

# ui.COLORS 의 warning 색과 동일(직접 import 하지 않고 상수만 둔다 — 소유 파일 한정).
COLORS_WARNING = discord.Color.yellow()


def _needs_provider_setup(config: GuildConfig, *, ollama_has_model: bool) -> bool:
    """봇이 바로 응답할 수 없는(설정 미완료) 상태인지 판정한다 (#90).

    - 외부 제공자(OpenAI/Anthropic/Gemini): API 키가 없으면 설정 필요.
    - Ollama(로컬): 설치된 모델이 하나도 없으면 설정 필요.

    ``ollama_has_model`` 은 호출부가 OllamaManager.list_models 결과로 채워 넘긴다
    (네트워크 의존을 분리해 순수 판정 함수로 둔다).
    """
    if config.provider in _EXTERNAL_PROVIDERS:
        return not config.api_key_encrypted
    # Ollama: 설치된 모델이 없으면 첫 사용 시 실패하므로 설정이 필요하다.
    return not ollama_has_model


def _onboarding_embed(config: GuildConfig, *, ollama_has_model: bool) -> discord.Embed:
    """설정 미완료 안내 체크리스트 임베드를 만든다 (#90).

    제공자별로 남은 설정 단계(API 키 등록 / Ollama 모델 설치)를 체크리스트로 보여
    주고, 함께 붙는 버튼으로 바로 /settings 패널을 열 수 있게 안내한다.
    """
    embed = discord.Embed(
        title="⚙️ 시작하기 전에 — AI 설정이 필요해요",
        description=(
            "아직 AI 제공자/모델 설정이 끝나지 않아 명령이 동작하지 않을 수 있어요. "
            "아래 **지금 설정하기** 버튼으로 설정을 마무리해 주세요. (관리자 전용)"
        ),
        color=COLORS_WARNING,
    )
    if config.provider in _EXTERNAL_PROVIDERS:
        embed.add_field(
            name="체크리스트",
            value=(
                f"☐ **{config.provider.display_name()}** API 키 등록\n"
                "  → `/settings` → 제공자 변경 → 모델 선택 → **API 키 등록 / 변경**\n"
                "☑ 제공자 선택 완료"
            ),
            inline=False,
        )
    else:
        # Ollama: 모델 미설치 안내.
        embed.add_field(
            name="체크리스트",
            value=(
                "☐ **Ollama 모델 설치** (로컬 PC에 모델이 없어요)\n"
                "  → `/settings` → 모델 관리 → **새 모델 설치**\n"
                "  → 또는 다른 제공자(OpenAI/Anthropic/Gemini)로 변경 후 API 키 등록\n"
                "☑ 제공자: Ollama (로컬)"
            ),
            inline=False,
        )
    embed.set_footer(text="설정을 마치면 /summarize · /ask · /chat 을 바로 사용할 수 있어요.")
    return embed


def _split_discord_text(text: str, *, max_chars: int = MAX_DISCORD_MESSAGE_CHARS) -> list[str]:
    """Split a long bot response into Discord-safe chunks.

    Avoids breaking inside code blocks (``` fences) by closing/reopening them.
    """
    text = text.strip() or "(empty response)"
    chunks: list[str] = []
    current = ""
    in_code_block = False
    code_lang = ""

    for line in text.splitlines():
        if line.startswith("```"):
            if not in_code_block:
                in_code_block = True
                code_lang = line[3:].strip()
            else:
                in_code_block = False
                code_lang = ""

        # #118: inside a code block a single line must also fit alongside the
        # open ("```lang\n") and close ("\n```") fences; reserve that space so a
        # line that cannot fit fenced is routed to the fragment splitter below
        # instead of being emitted as an over-limit fenced chunk.
        line_over = len(line) > max_chars
        if in_code_block and not line.startswith("```"):
            fence_budget = max_chars - len(f"```{code_lang}\n") - len("\n```")
            line_over = line_over or len(line) > fence_budget
        if line_over:
            if in_code_block:
                # Flush any buffered content first, closing the fence — but drop
                # a buffer that is only the bare opening fence (no content yet)
                # to avoid emitting an empty code block.
                if current and current.rstrip() != f"```{code_lang}":
                    chunks.append((current + "\n```").rstrip())
                current = ""
                # Wrap each fragment in its own fence so the code block stays
                # valid markdown across the split (#80). Reserve room for fences.
                fence_open = f"```{code_lang}\n"
                fence_close = "\n```"
                budget = max(1, max_chars - len(fence_open) - len(fence_close))
                starts = list(range(0, len(line), budget))
                for idx, start in enumerate(starts):
                    fragment = line[start : start + budget]
                    if idx < len(starts) - 1:
                        chunks.append(f"{fence_open}{fragment}{fence_close}")
                    else:
                        # Leave the final fragment's fence open so the source's
                        # own closing ``` (or the end-of-text flush) closes it
                        # exactly once — no stray lone fence.
                        current = f"{fence_open}{fragment}"
            else:
                if current:
                    chunks.append(current.rstrip())
                    current = ""
                for start in range(0, len(line), max_chars):
                    chunks.append(line[start : start + max_chars])
            continue

        candidate = f"{current}\n{line}" if current else line
        # #118: while inside a code block the buffer is later closed with a
        # trailing "\n```" fence (line below + the end-of-text flush), so reserve
        # those 4 chars in the boundary check; otherwise the flushed chunk would
        # exceed max_chars by the fence length and break the function's contract.
        fence_reserve = len("\n```") if in_code_block else 0
        if len(candidate) + fence_reserve > max_chars:
            if in_code_block:
                current += "\n```"
            chunks.append(current.rstrip())
            current = (f"```{code_lang}\n{line}" if in_code_block else line)
        else:
            current = candidate

    if current:
        if in_code_block:
            current += "\n```"
        chunks.append(current.rstrip())
    return chunks


async def _send_interaction_chunks(
    interaction: discord.Interaction,
    text: str,
    *,
    ephemeral: bool = False,
) -> None:
    chunks = _split_discord_text(text)
    first, *rest = chunks
    if interaction.response.is_done():
        await interaction.followup.send(first, ephemeral=ephemeral)
    else:
        await interaction.response.send_message(first, ephemeral=ephemeral)
    for chunk in rest:
        await interaction.followup.send(chunk, ephemeral=ephemeral)


async def _send_channel_chunks(channel: discord.abc.Messageable, text: str) -> None:
    chunks = _split_discord_text(text)
    for i, chunk in enumerate(chunks):
        await channel.send(chunk)
        if i < len(chunks) - 1:
            await asyncio.sleep(0.5)  # avoid Discord rate limit on bulk sends


# --- #93 긴 응답 UX 통일 ---
# /chat 이 쓰던 '프리뷰 한 메시지 + DM 버튼(LongResponseView)' 방식을 헬퍼로
# 추출해 /ask·/summarize·@멘션 등 다른 응답 경로에서도 동일하게 쓴다. 임계 초과
# 시 여러 메시지로 도배하지 않고, 첫 메시지에 잘라낸 프리뷰와 'DM 으로 전체 받기'
# 버튼만 붙인다. full_text 에는 헤더를 포함한 전체 텍스트를 넘긴다(DM 도 동일 내용).


async def _send_channel_answer_with_overflow(
    channel: discord.abc.Messageable, full_text: str
) -> None:
    """채널 응답(@멘션 등)을 프리뷰 + LongResponseView(DM 버튼)로 통일해 보낸다 (#93).

    한 메시지 한도 이내면 그대로 보낸다. 초과 시 메시지 폭탄(_send_channel_chunks)
    대신 프리뷰 1개 + 'DM 으로 전체 받기' 버튼만 보내 채널 도배를 막는다.
    """
    body = full_text.strip() or "(empty response)"
    if len(body) <= MAX_DISCORD_MESSAGE_CHARS:
        await channel.send(body)
        return
    preview = body[: MAX_DISCORD_MESSAGE_CHARS - 1] + "…"
    await channel.send(preview, view=LongResponseView(full_text=body))


async def _send_answer_with_overflow(
    interaction: discord.Interaction,
    full_text: str,
    *,
    ephemeral: bool = False,
    return_message: bool = False,
) -> discord.Message | None:
    """긴 응답을 프리뷰 + LongResponseView(DM 버튼)로 통일해 전송한다 (#93).

    - 한 메시지 한도 이내면 그대로 보낸다(_send_interaction_chunks 와 동일 동작).
    - 한도를 넘으면 첫 메시지를 프리뷰로 자르고 '전체 응답 보기(DM)' 버튼을 붙여
      메시지 폭탄 대신 단일 프리뷰로 보여준다.
    - ``return_message`` 가 True 면 (피드백 추적용으로) 보낸 메시지를 반환한다.
    """
    body = full_text.strip() or "(empty response)"
    over_limit = len(body) > MAX_DISCORD_MESSAGE_CHARS
    # 한도 초과 시에만 프리뷰로 자르고 DM 버튼(LongResponseView)을 붙인다.
    if over_limit:
        # 끝부분이 잘렸음을 알리는 말줄임표를 붙여 프리뷰임을 명확히 한다.
        content = body[: MAX_DISCORD_MESSAGE_CHARS - 1] + "…"
        view: discord.ui.View | None = LongResponseView(full_text=body)
    else:
        content = body
        view = None

    if not interaction.response.is_done():
        # 아직 응답 전이면 첫 응답으로 보낸다(메시지 핸들 반환은 followup 경로만).
        if view is not None:
            await interaction.response.send_message(content, view=view, ephemeral=ephemeral)
        else:
            await interaction.response.send_message(content, ephemeral=ephemeral)
        return None

    # 이미 defer/응답 완료 → followup 으로 보낸다. 피드백 추적이 필요하면 wait=True.
    if return_message:
        if view is not None:
            return await interaction.followup.send(
                content, view=view, ephemeral=ephemeral, wait=True
            )
        return await interaction.followup.send(content, ephemeral=ephemeral, wait=True)
    if view is not None:
        await interaction.followup.send(content, view=view, ephemeral=ephemeral)
    else:
        await interaction.followup.send(content, ephemeral=ephemeral)
    return None


# --- #16 스트리밍 응답 throttle ---
# message.edit 를 너무 자주 호출하면 Discord 레이트리밋에 걸리므로, 최소 간격과
# 최소 누적 글자 수 둘 중 하나를 만족할 때만 편집한다.
_STREAM_EDIT_MIN_INTERVAL = 1.2  # seconds between edits
_STREAM_EDIT_MIN_CHARS = 60      # minimum new chars before an edit


async def _stream_to_interaction(
    interaction: discord.Interaction,
    stream: Any,
    *,
    header: str = "",
    ephemeral: bool = False,
) -> str:
    """LLM 스트림을 followup 메시지에 점진적으로 편집해 보여준다 (#16).

    - 첫 청크를 받으면 followup 메시지를 만들고, 이후 throttle 규칙(_STREAM_EDIT_*)
      을 만족할 때만 ``message.edit`` 한다.
    - 누적 길이가 Discord 한 메시지 한도를 넘으면 더 이상 편집하지 않고 누적만 한다
      (최종 확정은 호출부가 _split_discord_text 로 처리).
    - 편집 실패/레이트리밋은 조용히 무시하고 다음 기회에 다시 시도한다.
    - 스트림이 끝나면 누적된 전체 텍스트(헤더 제외)를 반환한다.

    반환된 전체 텍스트가 한 메시지 한도를 넘으면 호출부가 추가 청크를 이어
    보내야 한다(이 함수는 첫 메시지까지만 책임진다).
    """
    accumulated = ""
    message: discord.Message | None = None
    last_edit = perf_counter()
    last_len = 0

    def _display(body: str) -> str:
        text = (header + body) if header else body
        return text[:MAX_DISCORD_MESSAGE_CHARS] or "…"

    async for piece in stream:
        if not piece:
            continue
        accumulated += piece
        if message is None:
            # 첫 청크 도착 — followup 메시지 생성(응답은 이미 defer 된 상태).
            message = await interaction.followup.send(
                _display(accumulated), ephemeral=ephemeral, wait=True
            )
            last_edit = perf_counter()
            last_len = len(accumulated)
            continue
        # 이미 한 메시지 한도를 넘었으면 편집을 멈추고 누적만 한다.
        if len(header) + len(accumulated) > MAX_DISCORD_MESSAGE_CHARS:
            continue
        now = perf_counter()
        if (now - last_edit) >= _STREAM_EDIT_MIN_INTERVAL and (
            len(accumulated) - last_len
        ) >= _STREAM_EDIT_MIN_CHARS:
            try:
                await message.edit(content=_display(accumulated))
                last_edit = now
                last_len = len(accumulated)
            except discord.HTTPException:
                # 레이트리밋/일시 오류 — 다음 기회에 다시 시도.
                pass

    # 스트림 종료 후 최종 1회 확정 편집(첫 메시지 한도 내일 때만).
    if message is not None and len(header) + len(accumulated) <= MAX_DISCORD_MESSAGE_CHARS:
        try:
            await message.edit(content=_display(accumulated))
        except discord.HTTPException:
            pass
    return accumulated


def _effective_limit(limit: int | None, default: int) -> int:
    if limit is None:
        return default
    return max(1, min(int(limit), 200))


def _ui_language(config: GuildConfig) -> str:
    """UI 표면(임베드/버튼)에 쓸 길드 언어 코드를 돌려준다 (#87).

    UI 는 트랜스크립트가 없어 자동 감지를 할 수 없으므로 'auto' 는 'ko' 로 폴백한다.
    그 외는 설정값을 그대로 쓰며, 카탈로그에 번역이 없으면 messages.t 가 ko 로
    폴백한다(미지원 언어 안전).
    """
    lang = (config.language or "ko").strip().lower()
    return "ko" if lang == "auto" else lang


def _ids_from_interaction(
    interaction: discord.Interaction,
) -> tuple[int | None, int | None, int | None]:
    # #46: 명령 실행 컨텍스트에 interaction.id 를 correlation id 로 바인딩한다.
    # 거의 모든 슬래시 명령이 진입부에서 이 헬퍼를 호출하므로 자연스러운 바인딩
    # 지점이 된다. 이후 같은 컨텍스트의 로그에는 cid 가 따라붙는다.
    set_correlation_id(getattr(interaction, "id", None))
    guild_id = interaction.guild.id if interaction.guild else None
    channel_id = interaction.channel.id if interaction.channel else None  # type: ignore[union-attr]
    user_id = interaction.user.id if interaction.user else None
    return guild_id, channel_id, user_id


def _has_config_permission(interaction: discord.Interaction, admin_role_id: int | None) -> bool:
    permissions = getattr(interaction.user, "guild_permissions", None)
    if permissions and (permissions.administrator or permissions.manage_guild):
        return True
    if admin_role_id is not None:
        roles = getattr(interaction.user, "roles", [])
        return any(getattr(role, "id", None) == admin_role_id for role in roles)
    return False


# --- #91 API 키 미설정 안내 문구 ---
# 실제 /settings UI 흐름과 일치하는 안내를 한곳에서 만든다. 외부 제공자(OpenAI/
# Anthropic/Gemini)는 모두 [제공자 변경] → 모델 선택 → [API 키 등록 / 변경] 버튼
# 경로를 거친다(ui.SettingsView/ExternalModelView 의 실제 버튼 라벨과 동일).
_API_KEY_SETUP_HINT = (
    "`/settings` → **제공자 변경**에서 제공자를 고른 뒤, 모델 선택 화면의 "
    "**API 키 등록 / 변경** 버튼으로 키를 등록해 주세요."
)


def _missing_api_key_message(provider: LLMProvider) -> str:
    """제공자별 API 키 미설정 안내 문구를 실제 UI 라벨에 맞춰 만든다 (#91)."""
    return f"{provider.display_name()} API 키가 설정되지 않았습니다. {_API_KEY_SETUP_HINT}"


def _get_llm(config: GuildConfig, settings: AppSettings) -> BaseLLMClient:
    """Return the correct LLM client for the guild's provider setting."""
    if config.provider == LLMProvider.OPENAI:
        if not config.api_key_encrypted:
            raise UserFacingError(_missing_api_key_message(config.provider))
        try:
            api_key = decrypt_api_key(config.api_key_encrypted, settings.secret_key)
        except CryptoError as exc:
            raise UserFacingError(f"API 키 복호화 실패: {exc}") from exc
        return OpenAIClient(
            api_key=api_key,
            default_model=config.model,
            timeout_seconds=settings.ollama_timeout_seconds,
        )

    if config.provider == LLMProvider.ANTHROPIC:
        if not config.api_key_encrypted:
            raise UserFacingError(_missing_api_key_message(config.provider))
        try:
            api_key = decrypt_api_key(config.api_key_encrypted, settings.secret_key)
        except CryptoError as exc:
            raise UserFacingError(f"API 키 복호화 실패: {exc}") from exc
        return AnthropicClient(
            api_key=api_key,
            default_model=config.model,
            timeout_seconds=settings.ollama_timeout_seconds,
        )

    if config.provider == LLMProvider.GEMINI:
        # #15: Google Gemini — API 키 복호화 후 GeminiClient 를 구성한다.
        if not config.api_key_encrypted:
            raise UserFacingError(_missing_api_key_message(config.provider))
        try:
            api_key = decrypt_api_key(config.api_key_encrypted, settings.secret_key)
        except CryptoError as exc:
            raise UserFacingError(f"API 키 복호화 실패: {exc}") from exc
        return GeminiClient(
            api_key=api_key,
            default_model=config.model,
            timeout_seconds=settings.ollama_timeout_seconds,
            temperature=settings.gemini_temperature,
        )

    return OllamaClient(
        base_url=settings.ollama_base_url,
        default_model=config.model,
        timeout_seconds=settings.ollama_timeout_seconds,
        keep_alive=settings.ollama_keep_alive,
        temperature=settings.ollama_temperature,
        num_ctx=settings.ollama_num_ctx,
    )


# --- #19 서버별 일일 토큰 상한 ---
# LLM 호출 전에 당일(UTC) 누적 토큰이 서버 설정 상한을 넘었는지 검사한다. budget 이
# None(무제한)이면 검사 자체를 건너뛰어 기존 동작과 100% 동일하다. guild_id 가 없는
# (DM 등) 경로는 서버 단위 상한이 의미 없으므로 검사하지 않는다.
async def _enforce_token_budget(
    store: ConfigStore, config: GuildConfig, guild_id: int | None
) -> None:
    """당일 누적 토큰이 서버 일일 상한을 초과했으면 UserFacingError 로 차단한다 (#19).

    - budget 이 None(무제한)이거나 guild_id 가 None 이면 검사를 건너뛴다(기존 동작).
    - 초과 시 사용자에게 보여줄 안내 메시지와 함께 UserFacingError 를 던진다.
    """
    budget = config.daily_token_budget
    if budget is None or guild_id is None:
        return
    used = await store.get_today_token_usage(guild_id)
    if used >= budget:
        raise UserFacingError(
            f"오늘 사용량 한도를 초과했어요. (오늘 {used:,} / 한도 {budget:,} 토큰) "
            "자정(UTC) 이후 초기화되며, 서버 관리자가 `/config daily_token_budget` 로 한도를 조정할 수 있어요."
        )


# --- #20 함수/툴 호출: search_messages 툴 ---
# LLM 이 "더 많은 메시지가 필요하다"고 판단하면 search_messages(query) 를 호출하고,
# 봇이 채널 히스토리에서 키워드로 검색해 결과를 돌려준다(경량 에이전트 루프).
# OpenAI/Anthropic 만 실제 툴 루프를 돌리고, 그 외 제공자는 일반 generate 로 폴백한다.
_SEARCH_MESSAGES_TOOL = ToolSpec(
    name="search_messages",
    description=(
        "Search the current Discord channel's recent message history for messages "
        "containing a keyword or phrase. Use this when the provided transcript does "
        "not contain enough information to answer the question, and you need to look "
        "for additional related messages. Returns matching messages with author and "
        "timestamp."
    ),
    parameters={
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Keyword or phrase to search for in the channel history.",
            }
        },
        "required": ["query"],
    },
)

# search_messages 한 번에 훑는 최대 메시지 수 / 돌려주는 최대 일치 건수.
_TOOL_SEARCH_SCAN_LIMIT = 200
_TOOL_SEARCH_MAX_MATCHES = 15


def _make_search_messages_runner(
    channel: Any, *, before: datetime
) -> "Callable[[str, dict[str, Any]], Awaitable[str]]":
    """채널에 바인딩된 search_messages 툴 실행기를 만든다 (#20).

    LLM 이 호출한 search_messages(query) 를 받아 채널 히스토리를 키워드로 검색해
    일치 메시지들을 문자열로 돌려준다. 권한/오류는 모델에 관측 가능한 문자열로
    변환해 루프가 멈추지 않게 한다.
    """

    async def _runner(name: str, args: dict[str, Any]) -> str:
        if name != _SEARCH_MESSAGES_TOOL.name:
            return f"(unknown tool: {name})"
        query = str(args.get("query") or "").strip()
        if not query:
            return "(no query provided)"
        if channel is None or not hasattr(channel, "history"):
            return "(this channel does not support message history search)"
        needle = query.lower()
        matches: list[str] = []
        try:
            async for msg in channel.history(limit=_TOOL_SEARCH_SCAN_LIMIT, before=before):
                if needle in (msg.content or "").lower():
                    ts = msg.created_at.strftime("%H:%M") if msg.created_at else ""
                    author = getattr(msg.author, "display_name", "?")
                    matches.append(f"[{ts}] {author}: {msg.content[:200]}")
                    if len(matches) >= _TOOL_SEARCH_MAX_MATCHES:
                        break
        except discord.Forbidden:
            return "(no permission to read message history)"
        except discord.HTTPException as exc:
            return f"(search failed: {exc})"
        if not matches:
            return f"No messages found matching '{query}'."
        return "\n".join(matches)

    return _runner


def _require_history_channel(channel: Any) -> Any:
    """history() 를 지원하는 채널인지 확인하고 그대로 돌려준다 (#6).

    channel 이 None 이거나 history 를 지원하지 않으면 _collect_transcript 와 동일한
    친절 메시지로 UserFacingError 를 던진다. /export·/search 처럼 history 를 직접
    호출하는 경로가 None 채널에서 잡히지 않는 AttributeError 로 침묵 실패하지 않게 한다.
    """
    if channel is None or not hasattr(channel, "history"):
        raise UserFacingError("이 명령은 메시지 기록을 읽을 수 있는 채널에서만 사용할 수 있어요.")
    return channel


async def _collect_transcript(
    channel: Any,
    *,
    before: datetime,
    limit: int,
    max_context_chars: int,
    after: datetime | None = None,
) -> str:
    if channel is None or not hasattr(channel, "history"):
        raise UserFacingError("이 명령은 메시지 기록을 읽을 수 있는 채널에서만 사용할 수 있어요.")
    messages = []
    try:
        # #4/#11: history(after=..., limit=N) 은 discord.py 에서 oldest-first 로
        # 페이지네이션돼 윈도우의 *가장 오래된* N 개만 돌려준다. 그러면 활발한
        # 채널에서 since:Xd 가 기간의 초반만 요약하고 최신 활동을 통째로 누락한다.
        # before 만 넘겨 newest-first 로 받은 뒤 after 보다 오래된 메시지에서 멈춰,
        # 윈도우의 *가장 최신* N 개(요약에 가장 관련 높은 메시지)를 유지한다.
        async for message in channel.history(limit=limit, before=before):
            if after is not None:
                created = getattr(message, "created_at", None)
                if created is not None:
                    if created.tzinfo is None:
                        created = created.replace(tzinfo=timezone.utc)
                    # history 는 시간 순서이므로 윈도우를 벗어나면 이후도 모두 밖이다.
                    if created < after:
                        break
            messages.append(from_discord_message(message))
    except discord.Forbidden as exc:
        raise UserFacingError("봇에 Read Message History 권한이 없어 최근 대화를 읽을 수 없어요.") from exc
    except discord.HTTPException as exc:
        raise UserFacingError(f"Discord 메시지 기록 조회에 실패했어요: {exc}") from exc
    messages.reverse()
    return build_transcript(messages, max_chars=max_context_chars)


def _usage_tokens(llm: BaseLLMClient) -> tuple[int, int]:
    """클라이언트의 last_usage 에서 (prompt_tokens, completion_tokens) 를 읽는다 (#17).

    last_usage 가 없거나(구현 누락) 비정상이면 (0, 0) 으로 안전하게 폴백한다.
    """
    usage = getattr(llm, "last_usage", None)
    prompt = getattr(usage, "prompt_tokens", 0) or 0
    completion = getattr(usage, "completion_tokens", 0) or 0
    return int(prompt), int(completion)


def _sniff_image_mime(data: bytes) -> str | None:
    """다운로드한 바이트의 매직넘버로 실제 이미지 포맷을 판정한다 (#31).

    클라이언트가 지정한 content_type 헤더는 위조 가능하므로, 실제 바이트로
    포맷을 검증해 비이미지(또는 위장) 파일을 LLM 멀티모달 입력에서 배제한다.
    인식 가능한 이미지면 정규화된 MIME 문자열을, 아니면 None 을 반환한다.
    """
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[:2] in (b"BM",):  # BMP
        return "image/bmp"
    return None


async def _download_image_attachments(
    attachments: list[discord.Attachment],
    *,
    max_count: int = MAX_IMAGE_ATTACHMENTS,
    max_bytes: int = MAX_IMAGE_BYTES,
) -> list[ImageInput]:
    """첨부 중 이미지를 bytes 로 다운로드해 (mime, bytes) 리스트로 반환한다 (#13).

    - content_type 이 ``image/*`` 인 첨부만 대상으로 한다(MIME 검증).
    - 개별 첨부 용량이 ``max_bytes`` 를 넘으면 건너뛴다(용량 상한).
    - 최대 ``max_count`` 장까지만 다운로드한다.
    - 개별 다운로드 실패(네트워크/권한 등)는 조용히 건너뛰어 한 장 실패가 전체를
      막지 않게 한다.

    반환 리스트는 llm.generate(prompt, images=...) 에 그대로 넘길 수 있다.
    """
    images: list[ImageInput] = []
    for att in attachments:
        if len(images) >= max_count:
            break
        content_type = att.content_type
        if not content_type or not content_type.startswith("image/"):
            continue
        # discord.Attachment.size 는 바이트 단위. 다운로드 전 상한으로 거른다.
        if att.size and att.size > max_bytes:
            logger.info(
                "이미지 첨부 용량 초과로 건너뜀: %s (%d bytes)", att.filename, att.size
            )
            continue
        try:
            data = await att.read()
        except (discord.HTTPException, discord.NotFound, discord.Forbidden) as exc:
            logger.warning("이미지 첨부 다운로드 실패: %s %s", att.filename, exc)
            continue
        # 실제 바이트 길이로 한 번 더 검증(헤더상 size 와 어긋날 수 있음).
        if len(data) > max_bytes:
            logger.info(
                "이미지 첨부 실제 용량 초과로 건너뜀: %s (%d bytes)",
                att.filename,
                len(data),
            )
            continue
        # #31: content_type 헤더는 업로더가 지정하는 메타데이터라 위조 가능하다.
        # 실제 바이트의 매직넘버로 포맷을 검증해, 위장/비이미지 파일이 LLM 멀티모달
        # 입력으로 전달되는 것을 막는다. 인식 불가하면 건너뛴다.
        sniffed = _sniff_image_mime(data)
        if sniffed is None:
            logger.info(
                "이미지 매직넘버 미일치로 건너뜀: %s (content_type=%s)",
                att.filename,
                content_type,
            )
            continue
        declared = content_type.split(";", 1)[0].strip().lower()
        if declared != sniffed:
            logger.info(
                "이미지 content_type 과 실제 포맷 불일치: %s (선언=%s 실제=%s) — 실제 포맷 사용",
                att.filename,
                declared,
                sniffed,
            )
        # 실제 검증된 MIME 을 보관해 제공자별 멀티모달 규격에 정확한 타입을 싣는다.
        images.append((sniffed, data))
    return images


async def _record_usage(
    store: ConfigStore,
    *,
    guild_id: int | None,
    channel_id: int | None,
    user_id: int | None,
    command: str,
    status: str,
    started_at: float,
    error: str | None = None,
    prompt_tokens: int = 0,
    completion_tokens: int = 0,
) -> None:
    latency_ms = int((perf_counter() - started_at) * 1000)
    if latency_ms > _SLOW_RESPONSE_THRESHOLD_MS:
        # cid 는 CorrelationIdFilter 가 레코드에 주입하지만, 메시지에도 직접 실어
        # 필터 미구성 환경(테스트 등)에서도 추적 가능하게 한다 (#46).
        logger.warning(
            "느린 응답 감지: %s %dms (cid=%s)", command, latency_ms, get_correlation_id()
        )
    # #47: SQLite 기록과 병행해 Prometheus 메트릭에도 기록한다.
    # prometheus_client 미설치 시 record_command 는 no-op 이므로 안전하다.
    metrics.record_command(command, status, latency_ms)
    await store.log_usage(
        UsageLog(
            guild_id=guild_id,
            channel_id=channel_id,
            user_id=user_id,
            command=command,
            status=status,
            latency_ms=latency_ms,
            error=error,
            # #17: 토큰 정보가 없으면 0 으로 기록(누락 안전).
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
        )
    )


# --- #1/#2 리마인더 payload 직렬화 ---
# DB 에는 payload 를 JSON 문자열로 저장해, 표시 텍스트/종류/반복여부를 함께 담는다.
# 과거(비-JSON) payload 도 안전하게 평문 메시지로 취급한다(백워드 호환).
_REMIND_KIND_SUMMARY = "summary"
_REMIND_KIND_TEXT = "text"

# #2 리마인더 남용/적체 방지: 저장 payload 길이 상한과 사용자별 미발송 개수 상한.
# 길이 상한은 발송 본문(_deliver_reminder 의 text[:1800]/[:1900])과도 정합한다.
_MAX_REMIND_TEXT_CHARS = 1800
_MAX_PENDING_REMINDERS_PER_USER = 25


def _encode_remind_payload(text: str, *, kind: str, repeat: str | None = None) -> str:
    """리마인더 payload 를 JSON 문자열로 직렬화한다 (#1/#2)."""
    data: dict[str, Any] = {"v": 1, "kind": kind, "text": text}
    if repeat:
        data["repeat"] = repeat
    return json.dumps(data, ensure_ascii=False)


def _decode_remind_payload(payload: str) -> dict[str, Any]:
    """payload 를 디코딩한다. 비-JSON(레거시)은 평문 텍스트로 취급한다 (#1)."""
    try:
        data = json.loads(payload)
        if isinstance(data, dict) and "text" in data:
            return {
                "kind": str(data.get("kind", _REMIND_KIND_TEXT)),
                "text": str(data["text"]),
                "repeat": data.get("repeat"),
            }
    except (json.JSONDecodeError, TypeError):
        pass
    return {"kind": _REMIND_KIND_TEXT, "text": payload, "repeat": None}


def create_bot(settings: AppSettings) -> commands.Bot:
    """Create a configured discord.py bot instance."""

    intents = discord.Intents.default()
    intents.guilds = True
    intents.messages = True
    intents.message_content = True

    store = ConfigStore(
        settings.database_url,
        default_model=settings.ollama_model,
        default_summary_limit=settings.default_summary_limit,
        default_language=settings.default_language,
    )
    ollama_manager = OllamaManager(settings.ollama_base_url)
    view_ctx = ViewCtx(store=store, ollama_manager=ollama_manager, secret_key=settings.secret_key)

    class AssistantBot(commands.Bot):
        # #48: 헬스/메트릭 HTTP 서버. METRICS_PORT=0(기본)이면 start 가 건너뛴다.
        health_server: HealthServer
        # #13: on_ready 는 재연결마다 발화하므로 reschedule 를 최초 1회만 하도록 가드.
        _reschedule_done: bool = False
        # #25: on_ready 의 길드별 명령 동기화도 재연결마다 반복되면 강한 길드 sync
        # 레이트리밋(일일 한도)을 소진하므로, 최초 1회만 동기화하도록 가드한다.
        _guild_synced: bool = False
        # #134: graceful shutdown 중인지 표시한다. close() 가 내는 on_disconnect 가
        # 종료 중에 가짜 '끊김' 알림을 새로 예약/발송하지 못하게 막는 가드.
        _shutting_down: bool = False

        async def setup_hook(self) -> None:
            await store.initialize()
            # #88: 명령 동기화 전에 현지화 번역기를 등록한다(ko/en). 등록 실패는
            # 봇 기동을 막지 않도록 흡수하고 경고만 남긴다(현지화는 부가 기능).
            try:
                await self.tree.set_translator(CommandTranslator())
            except Exception as exc:  # pragma: no cover — 번역기 등록 방어
                logger.warning("명령 현지화 번역기 등록 실패: %s", exc)
            if settings.auto_sync_commands:
                synced = await self.tree.sync()
                logger.info("Synced %d application command(s).", len(synced))
            # #48: 헬스/메트릭 서버 기동(포트 0이면 no-op). 기동 실패는 봇 기동을
            # 막지 않도록 HealthServer 내부에서 예외를 흡수한다.
            await self.health_server.start()

        async def close(self) -> None:
            # #134: 종료 시작을 표시해, close() 가 트리거하는 on_disconnect 가
            # 가짜 끊김 알림을 새로 예약하지 못하게 한다(정상 종료 오탐 방지).
            self._shutting_down = True
            # #48: graceful shutdown 시 헬스 서버를 먼저 정리한다(멱등).
            await self.health_server.stop()
            # #50: 영속 aiosqlite 연결을 닫는다. aiosqlite 의 워커 스레드는 비데몬
            # 이라, close() 를 누락하면 인터프리터 종료 시 _thread._shutdown() 이
            # 해당 스레드 join 에서 영원히 멈춘다(프로세스가 깔끔히 종료되지 못함).
            # close() 는 멱등이며 미초기화 상태에서도 안전한 no-op 이다.
            await store.close()
            await super().close()

    bot = AssistantBot(command_prefix="!", intents=intents)
    # 봇 인스턴스를 readiness provider 로 넘긴다(bot.is_ready()).
    bot.health_server = HealthServer(bot, port=settings.metrics_port)

    # ------------------------------------------------------------------
    # Reminders — 영속 예약 전송 (#1/#2/#3)
    # ------------------------------------------------------------------
    #
    # storage 계층(add_reminder/list_due/list_by_user/delete_reminder/mark_sent)을
    # 그대로 재사용한다. 봇 재시작에도 미발송 reminder 가 살아남도록 on_ready 에서
    # 미발송 항목을 다시 예약한다.

    async def _deliver_reminder(reminder: Reminder) -> None:
        """단일 reminder 를 DM 으로 전송하고 발송 완료 표시한다 (#1).

        #12: 발송 직전에 DB 에 행이 아직 존재하고 미발송(sent=0)인지 재확인해,
        취소(/reminders cancel)됐거나 다른 태스크가 이미 보낸 항목은 보내지 않는다.
        #14: 일시 오류(429/5xx)는 mark_sent 하지 않고 남겨 다음 기동 시 재시도되게
        한다. 성공/영구 실패(DM 차단)만 발송 완료로 표시한다.
        """
        decoded = _decode_remind_payload(reminder.payload)
        text = decoded["text"]
        if decoded["kind"] == _REMIND_KIND_SUMMARY:
            body = f"⏰ 알림: 예약했던 요약 결과입니다.\n\n{text[:1800]}"
        else:
            body = f"⏰ 알림: {text[:1900]}"
        # #12: 발송 직전 DB 상태 재확인 — 취소/중복 발송을 막는다. 미발송 목록에
        # 더 이상 없으면(취소됐거나 이미 발송됨) 조용히 종료한다.
        if reminder.id is not None:
            try:
                pending = await store.list_by_user(reminder.user_id)
            except Exception as exc:  # pragma: no cover — 방어적 재확인 실패는 발송 막지 않음
                logger.warning("리마인더 발송 전 재확인 실패: id=%s %s", reminder.id, exc)
            else:
                if not any(r.id == reminder.id for r in pending):
                    logger.info(
                        "리마인더가 취소/이미발송됨 — 전송 건너뜀: id=%s", reminder.id
                    )
                    return
        try:
            user = bot.get_user(reminder.user_id) or await bot.fetch_user(reminder.user_id)
            if user is None:
                # #16: get_user/fetch_user 가 모두 None 이면 user.send 가
                # AttributeError 를 던져 어떤 except 에도 걸리지 않고, mark_sent 가
                # 누락돼 매 재기동 reschedule 마다 같은 행으로 실패하는 좀비가 된다.
                # 사용자를 찾을 수 없는 것은 영구 실패이므로 발송 완료로 표시한다.
                logger.info(
                    "리마인더 대상 사용자를 찾을 수 없어 발송 생략: user=%s id=%s",
                    reminder.user_id,
                    reminder.id,
                )
            else:
                await user.send(body)
        except discord.Forbidden:
            # DM 차단 등 영구 실패: 무한 재시도하지 않도록 발송 완료로 표시한다.
            logger.info("리마인더 DM 전송 실패(차단): user=%s id=%s", reminder.user_id, reminder.id)
        except discord.HTTPException as exc:
            # #14: 일시 오류(429/5xx)는 발송 완료로 표시하지 않고 남겨, 다음 기동의
            # reschedule 에서 재시도되게 한다(조용한 영구 유실 방지).
            logger.warning("리마인더 DM 전송 일시 실패(재시도 예정): id=%s %s", reminder.id, exc)
            return
        if reminder.id is not None:
            await store.mark_sent(reminder.id)

    async def _schedule_reminder(reminder: Reminder) -> None:
        """due_at 까지 대기한 뒤 reminder 를 전송한다 (#1).

        due_at 은 ISO8601(UTC 권장) 문자열이다. 이미 지났으면 즉시 전송한다.
        #42: 파싱 불가한 due_at 은 '즉시 전송'으로 폴백하지 않고 발송 완료로 격리한다.
        #12: 자신의 asyncio.Task 를 _reminder_tasks 에 등록해, /reminders cancel 이
        sleep 중인 이 태스크를 취소할 수 있게 한다. 종료 시 항상 등록을 해제한다.
        """
        current = asyncio.current_task()
        if reminder.id is not None and current is not None:
            _reminder_tasks[reminder.id] = current
        try:
            try:
                due = datetime.fromisoformat(reminder.due_at)
            except ValueError:
                # #42: due_at 파싱 실패를 '즉시 발송'으로 폴백하면, 손상/타임존 누락된
                # 미래 리마인더가 의도 시점이 아니라 봇 시작 즉시 사용자에게 나간다.
                # 즉시 발송 대신 발송 완료로 격리(mark_sent)해, 다음 reschedule 마다
                # 같은 손상 행으로 무한 재시도되거나 오발송되지 않게 한다.
                logger.warning(
                    "리마인더 due_at 파싱 실패 — 발송하지 않고 격리합니다: id=%s %r",
                    reminder.id,
                    reminder.due_at,
                )
                if reminder.id is not None:
                    try:
                        await store.mark_sent(reminder.id)
                    except Exception as exc:  # pragma: no cover — 격리 실패 방어
                        logger.warning("손상 리마인더 격리 실패: id=%s %s", reminder.id, exc)
                return
            if due.tzinfo is None:
                due = due.replace(tzinfo=timezone.utc)
            delay = (due - datetime.now(timezone.utc)).total_seconds()
            if delay > 0:
                await asyncio.sleep(delay)
            await _deliver_reminder(reminder)
        finally:
            # 취소/완료 어느 경로든 레지스트리에서 자신을 정리한다(중복 제거 안전).
            if reminder.id is not None and _reminder_tasks.get(reminder.id) is current:
                del _reminder_tasks[reminder.id]

    async def _reschedule_pending_reminders() -> None:
        """봇 시작 시 미발송 reminder 를 모두 다시 예약한다 (#1).

        이미 만기인 항목은 즉시 전송되며, 미래 항목은 due_at 까지 대기한다.
        """
        try:
            # 충분히 먼 미래 시각을 넘겨 '미발송' 전부를 가져온 뒤 각각 재예약한다.
            # #15: list_due 의 due_at 비교가 문자열 사전식이므로, 비교 대상도 storage
            # 가 쓰는 초 단위 포맷(timespec='seconds')으로 통일해 정밀도 불일치를 막는다.
            far_future = (
                datetime.now(timezone.utc) + _MAX_REMIND_DELAY
            ).isoformat(timespec="seconds")
            pending = await store.list_due(now=far_future)
        except Exception as exc:  # pragma: no cover — 기동 경로 방어
            logger.exception("미발송 리마인더 조회 실패: %s", exc)
            return
        scheduled = 0
        for reminder in pending:
            # #13: 이미 살아 있는 sleep 태스크가 있는 reminder 는 재예약하지 않는다
            # (on_ready 재발화/재연결 시 동일 알림 중복 발송 방지).
            if reminder.id is not None and reminder.id in _reminder_tasks:
                continue
            _track_task(_schedule_reminder(reminder), name=f"reminder-{reminder.id}")
            scheduled += 1
        if scheduled:
            logger.info("미발송 리마인더 %d건을 재예약했습니다.", scheduled)

    # ------------------------------------------------------------------
    # /settings — interactive admin panel
    # ------------------------------------------------------------------

    @bot.tree.command(name="settings", description=_loc("봇 설정 패널을 엽니다. (관리자 전용)"))
    async def settings_command(interaction: discord.Interaction) -> None:
        if interaction.guild is None:
            await interaction.response.send_message("⚠️ 이 명령은 서버 안에서만 사용할 수 있어요.", ephemeral=True)
            return
        config = await store.get_guild_config(interaction.guild.id)
        if not _has_config_permission(interaction, config.admin_role_id):
            await interaction.response.send_message(
                "⚠️ 이 명령을 사용하려면 Manage Server 또는 관리자 권한이 필요해요.", ephemeral=True
            )
            return
        embed = settings_embed(config, interaction.guild.name, _ui_language(config))
        view = SettingsView(ctx=view_ctx, guild_id=interaction.guild.id, provider=config.provider)
        await interaction.response.send_message(embed=embed, view=view, ephemeral=True)

    # ------------------------------------------------------------------
    # /summarize
    # ------------------------------------------------------------------

    async def _deliver_summary_to_thread(
        interaction: discord.Interaction, title: str, body: str
    ) -> bool:
        """요약 결과를 채널에 새 스레드를 만들어 게시한다 (#5).

        create_thread 권한이 없거나 스레드를 만들 수 없는 채널이면 False 를
        반환해 호출 측이 일반(채널) 전송으로 폴백하게 한다.
        """
        channel = interaction.channel
        # 일반 텍스트 채널에서만 새 스레드를 만든다. 스레드/포럼/DM 등은 폴백한다.
        if not isinstance(channel, discord.TextChannel):
            return False
        # 스레드 이름은 100자 제한 + 개행 불가. 안전하게 잘라 정리한다.
        thread_name = title.replace("\n", " ").strip()[:90] or "요약"
        try:
            thread = await channel.create_thread(
                name=thread_name,
                type=discord.ChannelType.public_thread,
            )
        except discord.Forbidden:
            return False
        except (discord.HTTPException, TypeError):
            # 권한이 있어도 일시적 실패가 있을 수 있어 폴백한다.
            return False
        await _send_channel_chunks(thread, body)
        return True

    async def run_summarize(
        interaction: discord.Interaction,
        limit: int | None,
        since: str | None = None,
        thread: bool = False,
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return
        await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            # Role restriction check (#49)
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요. 서버 관리자에게 문의하세요.")
            message_limit = _effective_limit(limit, config.summary_limit)

            # Parse `since` parameter (#31)
            since_dt: datetime | None = None
            if since:
                since_dt = _parse_since(since)

            # Language auto-detect support (#44)
            effective_language = config.language

            # Skip cache when since or limit is explicitly specified
            use_cache = (limit is None and since is None)
            # #132: 캐시 본문은 언어/모델/커스텀 프롬프트에 따라 달라지므로 키에 변형
            # 시그니처를 포함한다(언어/모델 변경 후 stale 응답 방지). `guild:channel:`
            # 접두는 유지해 on_message 무효화(invalidate_prefix)가 모든 변형을 한 번에
            # 지운다(cache.py 의 세그먼트 경계 매칭과 호환).
            _cache_variant = hashlib.sha1(
                f"{config.language}|{config.model}|{config.custom_summarize_prompt or ''}".encode()
            ).hexdigest()[:12]
            cache_key = f"{guild_id}:{channel_id}:{_cache_variant}"
            cached = summarize_cache.get(cache_key) if use_cache else None
            if cached is not None:
                if user_id is not None:
                    _store_last_summary(user_id, cached, guild_id)
                # #123: 캐시 헤더 언어를 본문(요약) 언어에 맞춘다. 라이브 경로는
                # effective_language(auto 면 트랜스크립트 감지 결과)로 헤더를 만드는데,
                # 캐시 경로가 _ui_language(auto→ko)만 쓰면 본문이 ja/zh 인데 헤더만
                # ko 로 어긋난다. 트랜스크립트가 없는 캐시 히트에선 본문(cached)에서
                # 직접 언어를 감지해 라이브 경로와 동일한 헤더 언어를 쓴다.
                if config.language == "auto":
                    header_language = detect_language_from_transcript(cached)
                else:
                    header_language = config.language
                header = t(
                    "summary.header.cached", header_language, count=message_limit
                )
                # #5: thread=True 면 새 스레드에 게시하고, 권한이 없으면 폴백한다.
                if thread and await _deliver_summary_to_thread(
                    interaction, f"요약: 최근 {message_limit}개 메시지", header + cached
                ):
                    await interaction.followup.send("🧵 새 스레드에 요약을 게시했어요.", ephemeral=True)
                    await _record_usage(
                        store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                        command="summarize", status="ok", started_at=started,
                    )
                    return
                if thread:
                    await interaction.followup.send(
                        "⚠️ 스레드를 만들 권한이 없어 여기에 표시할게요.", ephemeral=True
                    )
                # #93: 긴 요약은 프리뷰 1개 + 'DM 으로 전체 받기' 버튼으로 통일한다.
                msg = await _send_answer_with_overflow(
                    interaction, header + cached, return_message=True
                )
                # Track cached results too, for parity with the live path (#71)
                await _track_for_feedback(guild_id, msg, "summarize")
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="summarize", status="ok", started_at=started,
                )
                return

            transcript = await _collect_transcript(
                interaction.channel,
                before=interaction.created_at,
                limit=message_limit,
                max_context_chars=settings.max_context_chars,
                after=since_dt,
            )
            if not transcript:
                raise UserFacingError("요약할 메시지가 없어요. 채널에 대화가 있어야 합니다.")

            # Auto-detect language if set to 'auto' (#44)
            if effective_language == "auto":
                effective_language = detect_language_from_transcript(transcript)

            # Use custom prompt if set (#40)
            if config.custom_summarize_prompt:
                # #89/#116: 커스텀 프롬프트 경로도 신뢰 불가 transcript 를
                # _wrap_untrusted 로 감싸고 _INJECTION_GUARD 를 prepend 해, 기본
                # build_* 경로와 동일한 프롬프트 인젝션 방어선을 유지한다.
                prompt = (
                    _INJECTION_GUARD
                    + "\n\n"
                    + config.custom_summarize_prompt.replace(
                        "{transcript}", _wrap_untrusted(transcript, "transcript")
                    )
                )
            else:
                prompt = build_summarize_prompt(transcript, language=effective_language)

            # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
            await _enforce_token_budget(store, config, guild_id)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            # #17: 응답 직후 토큰 사용량을 읽어 둔다(이후 record 경로에서 사용).
            p_tokens, c_tokens = _usage_tokens(llm)

            # Cache the result for default queries
            if use_cache:
                summarize_cache.set(cache_key, answer)

            # Store last summary for /remind (#32). #39: 진짜 LRU 로 저장한다.
            if user_id is not None:
                _store_last_summary(user_id, answer, guild_id)

            since_label = f" (since: {since})" if since else ""
            # 요약 본문 언어(effective_language, auto면 감지 결과)와 헤더 언어를 맞춘다.
            header = t(
                "summary.header",
                effective_language,
                count=message_limit,
                since=since_label,
            )
            # #5: thread=True 면 새 스레드에 게시하고, 권한이 없으면 폴백한다.
            if thread and await _deliver_summary_to_thread(
                interaction, f"요약: 최근 {message_limit}개 메시지", header + answer
            ):
                await interaction.followup.send("🧵 새 스레드에 요약을 게시했어요.", ephemeral=True)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="summarize", status="ok", started_at=started,
                    prompt_tokens=p_tokens, completion_tokens=c_tokens,
                )
                return
            if thread:
                await interaction.followup.send(
                    "⚠️ 스레드를 만들 권한이 없어 여기에 표시할게요.", ephemeral=True
                )
            # #93: 긴 요약은 프리뷰 1개 + 'DM 으로 전체 받기' 버튼으로 통일한다.
            msg = await _send_answer_with_overflow(
                interaction, header + answer, return_message=True
            )
            # Track this message for reaction feedback (consistent with /ask)
            await _track_for_feedback(guild_id, msg, "summarize")
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="summarize", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            # #3: 재시도 가능한 오류면 진입 시 기록한 쿨다운을 롤백해, 아래 재시도
            # 버튼이 쿨다운 안내만 띄우고 실제로 동작하지 않는 문제를 막는다.
            if _is_retryable_error(exc):
                _clear_cooldown(guild_id, user_id)
            # #92: 유형별 친절 안내 임베드 + 재시도 버튼(재시도 가능한 LLM 오류일 때).
            async def _retry(retry_interaction: discord.Interaction) -> None:
                await run_summarize(retry_interaction, limit, since, thread)

            await _send_error_embed(interaction, exc, retry=_retry)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="summarize", status="error", started_at=started, error=str(exc),
            )

    @bot.tree.command(name="summarize", description=_loc("최근 채널 대화를 로컬 LLM으로 요약합니다."))
    @app_commands.describe(
        limit=_loc("최근 몇 개 메시지를 읽을지 지정합니다. 기본값은 서버 설정입니다."),
        since=_loc("시간 기반 필터. 예: 1h, 30m, 2d"),
        thread=_loc("True면 요약 결과를 채널에 새 스레드를 만들어 게시합니다."),
    )
    @app_commands.autocomplete(since=_since_autocomplete)
    async def summarize_command(
        interaction: discord.Interaction,
        limit: int | None = None,
        since: str | None = None,
        thread: bool = False,
    ) -> None:
        await run_summarize(interaction, limit, since, thread)

    # ------------------------------------------------------------------
    # /ask
    # ------------------------------------------------------------------

    async def run_ask(
        interaction: discord.Interaction,
        question: str,
        limit: int | None,
        _transcript_override: str | None = None,
        *,
        search: bool = False,
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            if not interaction.response.is_done():
                await interaction.response.send_message(
                    f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
                )
            return
        if not interaction.response.is_done():
            await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            # Role restriction check (#49)
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요.")
            message_limit = _effective_limit(limit, config.summary_limit)

            if _transcript_override is not None:
                transcript = _transcript_override
            else:
                transcript = await _collect_transcript(
                    interaction.channel,
                    before=interaction.created_at,
                    limit=message_limit,
                    max_context_chars=settings.max_context_chars,
                )
            if not transcript:
                raise UserFacingError("질문에 참고할 최근 메시지가 없어요.")

            effective_language = config.language
            if effective_language == "auto":
                effective_language = detect_language_from_transcript(transcript)

            # Use custom prompt if set (#40)
            if config.custom_ask_prompt:
                # #89/#116: 커스텀 프롬프트 경로도 신뢰 불가 transcript/question 을
                # _wrap_untrusted 로 감싸고 _INJECTION_GUARD 를 prepend 해, 기본
                # build_* 경로와 동일한 프롬프트 인젝션 방어선을 유지한다.
                prompt = (
                    _INJECTION_GUARD
                    + "\n\n"
                    + config.custom_ask_prompt
                    .replace("{transcript}", _wrap_untrusted(transcript, "transcript"))
                    .replace("{question}", _wrap_untrusted(question.strip(), "question"))
                )
            else:
                prompt = build_ask_prompt(transcript, question, language=effective_language)

            # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
            await _enforce_token_budget(store, config, guild_id)
            llm = _get_llm(config, settings)
            # #20: search=True 면 LLM 이 search_messages 툴로 채널에서 더 많은
            # 메시지를 찾아볼 수 있는 경량 에이전트 루프를 돈다. 툴 미지원 제공자
            # (Ollama/Gemini)는 generate_with_tools 가 일반 generate 로 폴백한다.
            # _transcript_override(후속질문) 경로에는 채널 검색이 의미가 없어 건너뛴다.
            if search and _transcript_override is None and interaction.channel is not None:
                runner = _make_search_messages_runner(
                    interaction.channel, before=interaction.created_at
                )
                answer = await llm.generate_with_tools(
                    prompt,
                    tools=[_SEARCH_MESSAGES_TOOL],
                    tool_runner=runner,
                    model=config.model,
                )
            else:
                answer = await llm.generate(prompt, model=config.model)
            p_tokens, c_tokens = _usage_tokens(llm)  # #17

            # Follow-up view (#36) — capture transcript for follow-up
            transcript_snapshot = transcript

            async def _handle_follow_up(follow_interaction: discord.Interaction, follow_q: str) -> None:
                await follow_interaction.response.defer(thinking=True)
                await run_ask(follow_interaction, follow_q, limit, _transcript_override=transcript_snapshot)

            follow_view = FollowUpView(on_follow_up=_handle_follow_up)

            full_text = f"**질문:** {question}\n\n{answer}"
            # run_ask always defers before reaching here (both call sites defer),
            # so the response is done — send via followup (#23: removed dead else).
            if len(full_text) > MAX_DISCORD_MESSAGE_CHARS:
                # #93: 긴 응답은 메시지 폭탄 대신 프리뷰 1개 + 후속질문 + 'DM 으로 전체
                # 받기' 버튼으로 통일한다. LongResponseView 의 DM 버튼을 후속질문 뷰에
                # 합쳐 한 메시지에서 둘 다 제공한다(기존 ui 헬퍼 재사용).
                long_view = LongResponseView(full_text=full_text)
                for child in list(long_view.children):
                    long_view.remove_item(child)
                    follow_view.add_item(child)
                preview = full_text[: MAX_DISCORD_MESSAGE_CHARS - 1] + "…"
                msg = await interaction.followup.send(preview, view=follow_view, wait=True)
            else:
                msg = await interaction.followup.send(full_text, view=follow_view, wait=True)

            # Track this message for reaction feedback (#42, #71)
            await _track_for_feedback(guild_id, msg, "ask")

            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ask", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            # #3: 진입 시 기록한 쿨다운을 롤백해, 바로 아래 재시도 버튼이 쿨다운 안내만
            # 띄우고 실제로 동작하지 않는 문제를 막는다(재시도 가능한 LLM 오류 한정).
            if _is_retryable_error(exc):
                _clear_cooldown(guild_id, user_id)
            # #92: 유형별 친절 안내 임베드 + 재시도 버튼. 같은 질문/한도로 다시 시도한다.
            async def _retry(retry_interaction: discord.Interaction) -> None:
                await run_ask(retry_interaction, question, limit, _transcript_override, search=search)

            await _send_error_embed(interaction, exc, retry=_retry)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ask", status="error", started_at=started, error=str(exc),
            )

    @bot.tree.command(name="ask", description=_loc("최근 채널 대화 맥락으로 질문에 답합니다."))
    @app_commands.describe(
        question=_loc("최근 대화에 대해 물어볼 질문입니다."),
        limit=_loc("최근 몇 개 메시지를 읽을지 지정합니다. 기본값은 서버 설정입니다."),
        search=_loc("True면 AI가 필요 시 채널에서 추가 메시지를 검색합니다. (OpenAI/Anthropic)"),
    )
    async def ask_command(
        interaction: discord.Interaction,
        question: str,
        limit: int | None = None,
        search: bool = False,
    ) -> None:
        await run_ask(interaction, question, limit, search=search)

    # ------------------------------------------------------------------
    # /translate
    # ------------------------------------------------------------------

    @bot.tree.command(name="translate", description=_loc("선택 기능: 짧은 텍스트를 지정 언어로 번역합니다."))
    @app_commands.describe(text=_loc("번역할 텍스트"), target_language=_loc("목표 언어입니다. 예: ko, en, ja"))
    @app_commands.autocomplete(target_language=_language_autocomplete)
    async def translate_command(
        interaction: discord.Interaction,
        text: str,
        target_language: str = "ko",
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return
        await interaction.response.defer(thinking=True, ephemeral=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            # #90: 역할 제한을 적용한다(캐시 적중 경로도 우회하지 못하도록 캐시 검사 전에).
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요. 서버 관리자에게 문의하세요.")
            # Translation cache check (#38)
            cached_translation = get_translation(text, target_language)
            if cached_translation is not None:
                embed = discord.Embed(color=discord.Color.from_str("#5865F2"))
                embed.add_field(name="원문", value=_truncate(text), inline=False)
                embed.add_field(name=f"번역 ({target_language}) *(캐시)*", value=_truncate(cached_translation), inline=False)
                # #8: 1024 자 초과 시 'DM 으로 전체 받기' 버튼을 붙여 잘린 분량을 복구한다.
                cached_view = _overflow_view(cached_translation)
                if cached_view is not None:
                    await interaction.followup.send(embed=embed, view=cached_view, ephemeral=True)
                else:
                    await interaction.followup.send(embed=embed, ephemeral=True)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="translate", status="ok", started_at=started,
                )
                return
            prompt = build_translate_prompt(text, target_language=target_language)
            # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
            await _enforce_token_budget(store, config, guild_id)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            p_tokens, c_tokens = _usage_tokens(llm)  # #17
            # Cache the result (#38)
            set_translation(text, target_language, answer)
            embed = discord.Embed(color=discord.Color.from_str("#5865F2"))
            embed.add_field(name="원문", value=_truncate(text), inline=False)
            embed.add_field(name=f"번역 ({target_language})", value=_truncate(answer), inline=False)
            # #8: 1024 자 초과 시 'DM 으로 전체 받기' 버튼을 붙여 잘린 분량을 복구한다.
            answer_view = _overflow_view(answer)
            if answer_view is not None:
                await interaction.followup.send(embed=embed, view=answer_view, ephemeral=True)
            else:
                await interaction.followup.send(embed=embed, ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="translate", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="translate", status="error", started_at=started, error=str(exc),
            )

    # ------------------------------------------------------------------
    # /chat — free-form AI conversation without channel context
    # ------------------------------------------------------------------

    async def _run_chat(
        interaction: discord.Interaction,
        message: str,
        public: bool = False,
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return
        ephemeral = not public
        await interaction.response.defer(thinking=True, ephemeral=ephemeral)
        try:
            config = await store.get_guild_config(guild_id or 0)
            # #90: 다른 LLM 진입점과 동일하게 역할 제한을 적용한다.
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요. 서버 관리자에게 문의하세요.")
            history: list[dict[str, str]] = []
            if user_id is not None:
                history = await store.get_chat_history(
                    user_id, guild_id=guild_id, channel_id=channel_id, limit=10
                )
            if history:
                prompt = build_chat_with_history_prompt(message, history, language=config.language)
            else:
                # Apply persona if set (#37)
                prompt = build_chat_prompt(message, language=config.language, persona=config.persona)
            # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
            await _enforce_token_budget(store, config, guild_id)
            llm = _get_llm(config, settings)
            # #16: 스트리밍 우선 — 점진 출력 후 최종 확정. 스트림이 한 글자도
            # 내지 못했거나 실패하면 기존 비스트리밍 경로로 폴백한다.
            answer = ""
            streamed = False
            try:
                stream = llm.generate_stream(prompt, model=config.model)
                answer = await _stream_to_interaction(
                    interaction, stream, ephemeral=ephemeral
                )
                streamed = bool(answer)
            except LLMError:
                # 스트림 도중/시작 시 LLM 오류 — 이미 부분 출력했을 수 있으나,
                # 아무것도 못 냈으면(answer 빈 값) 폴백 generate 를 시도한다.
                if answer:
                    # #9/#62: 부분 출력 후 실패한 경우, 사용자에겐 부분 응답이 화면에
                    # 남는데 이 턴이 chat_history 에 저장되지 않으면 다음 /chat 의 맥락에서
                    # 통째로 빠진다. re-raise 전에 user 메시지와 부분 답변을 저장해
                    # 대화 메모리를 일관되게 유지한다(저장 실패는 흡수하고 원오류 전파).
                    if user_id is not None:
                        try:
                            await store.save_chat_message(
                                user_id, "user", message,
                                guild_id=guild_id, channel_id=channel_id,
                            )
                            await store.save_chat_message(
                                user_id, "assistant", answer,
                                guild_id=guild_id, channel_id=channel_id,
                            )
                        except Exception as save_exc:  # pragma: no cover — 저장 방어
                            logger.warning(
                                "부분 스트림 응답 저장 실패(턴 누락 가능): %s", save_exc
                            )
                    raise

            if not streamed:
                # 폴백: 비스트리밍 generate (기존 경로 그대로 유지).
                answer = await llm.generate(prompt, model=config.model)
                await _send_answer_with_overflow(
                    interaction, answer, ephemeral=ephemeral
                )
            elif len(answer) > MAX_DISCORD_MESSAGE_CHARS:
                # #93: 스트리밍으로 첫 메시지(프리뷰)는 이미 표시했으니, 나머지 분량을
                # 메시지 폭탄 대신 'DM 으로 전체 받기' 버튼 한 개로 통일해 제공한다.
                await interaction.followup.send(
                    "📄 응답이 길어 일부만 표시했어요. 아래 버튼으로 전체 내용을 DM 으로 받을 수 있어요.",
                    view=LongResponseView(full_text=answer),
                    ephemeral=ephemeral,
                )
            if user_id is not None:
                await store.save_chat_message(
                    user_id, "user", message, guild_id=guild_id, channel_id=channel_id
                )
                await store.save_chat_message(
                    user_id, "assistant", answer, guild_id=guild_id, channel_id=channel_id
                )
            # #17: 스트리밍 경로는 usage 메타데이터가 없어 (0,0) 이 되며, 폴백
            # generate 경로는 last_usage 가 채워진다(누락 시 0).
            p_tokens, c_tokens = _usage_tokens(llm)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="chat", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            # #3: 재시도 가능한 오류면 진입 시 기록한 쿨다운을 롤백해, 아래 재시도
            # 버튼이 쿨다운 안내만 띄우고 실제로 동작하지 않는 문제를 막는다.
            if _is_retryable_error(exc):
                _clear_cooldown(guild_id, user_id)
            # #92: 유형별 친절 안내 임베드 + 재시도 버튼. 같은 메시지로 다시 시도한다.
            async def _retry(retry_interaction: discord.Interaction) -> None:
                await _run_chat(retry_interaction, message, public)

            await _send_error_embed(interaction, exc, retry=_retry)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="chat", status="error", started_at=started, error=str(exc),
            )

    @bot.tree.command(name="chat", description=_loc("채널 맥락 없이 AI에게 자유롭게 질문합니다."))
    @app_commands.describe(
        message=_loc("AI에게 보낼 메시지입니다."),
        public=_loc("True로 설정하면 채널에 공개 메시지로 표시됩니다. 기본값은 비공개입니다."),
    )
    async def chat_command(
        interaction: discord.Interaction,
        message: str,
        public: bool = False,
    ) -> None:
        await _run_chat(interaction, message, public)

    # ------------------------------------------------------------------
    # /help — command reference
    # ------------------------------------------------------------------

    @bot.tree.command(name="help", description=_loc("봇 명령어 사용법을 안내합니다."))
    async def help_command(interaction: discord.Interaction) -> None:
        # #87: 길드 언어로 도움말 임베드/버튼을 현지화한다(서버 밖 DM 등은 ko 폴백).
        if interaction.guild is not None:
            config = await store.get_guild_config(interaction.guild.id)
            lang = _ui_language(config)
        else:
            lang = "ko"
        embed = HelpView.main_embed(lang)
        dashboard_url = os.getenv("DASHBOARD_URL", "").strip()
        view = HelpView(lang)
        if dashboard_url:
            view.add_item(
                discord.ui.Button(
                    label=t("help.button.dashboard", lang),
                    url=dashboard_url,
                    style=discord.ButtonStyle.link,
                    emoji="🖥️",
                    row=1,
                )
            )
        await interaction.response.send_message(embed=embed, view=view, ephemeral=True)

    # ------------------------------------------------------------------
    # /config — legacy CLI-style setters (kept for backward compat)
    # ------------------------------------------------------------------

    config_group = app_commands.Group(name="config", description=_loc("서버별 봇 설정을 관리합니다."))

    async def require_guild_admin(interaction: discord.Interaction) -> int:
        if interaction.guild is None:
            raise UserFacingError("/config 명령은 서버 안에서만 사용할 수 있어요.")
        config = await store.get_guild_config(interaction.guild.id)
        if not _has_config_permission(interaction, config.admin_role_id):
            raise UserFacingError("이 설정을 바꾸려면 Manage Server 또는 관리자 권한이 필요해요.")
        return interaction.guild.id

    async def _audit_config_change(
        interaction: discord.Interaction,
        guild_id: int,
        action: str,
        before: Any,
        after: Any,
    ) -> None:
        """설정 변경을 감사 로그에 기록한다 (#39).

        before/after 는 문자열로 정규화해 저장한다. 감사 로깅 실패가 명령 자체를
        막아서는 안 되므로 예외는 삼키고 경고만 남긴다.
        """
        user_id = interaction.user.id if interaction.user else None
        try:
            await store.record_audit(
                guild_id=guild_id,
                user_id=user_id,
                action=action,
                before=None if before is None else str(before),
                after=None if after is None else str(after),
            )
        except Exception as exc:  # pragma: no cover — 감사 로깅 방어
            logger.warning("감사 로그 기록 실패(action=%s): %s", action, exc)

    @config_group.command(name="model", description=_loc("서버 기본 Ollama 모델명을 저장합니다."))
    @app_commands.describe(model=_loc("예: llama3.1:8b, qwen2.5:7b, gemma2:9b"))
    async def config_model(interaction: discord.Interaction, model: str) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            before = (await store.get_guild_config(guild_id)).model
            config = await store.set_model(guild_id, model)
            await _audit_config_change(interaction, guild_id, "set_model", before, config.model)
            await _send_interaction_chunks(
                interaction, f"✅ 기본 모델을 `{config.model}`로 저장했어요.", ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="summary_limit", description=_loc("기본 메시지 요약 범위를 저장합니다."))
    @app_commands.describe(limit=_loc("1~200 사이의 메시지 개수"))
    async def config_summary_limit(interaction: discord.Interaction, limit: int) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            before = (await store.get_guild_config(guild_id)).summary_limit
            config = await store.set_summary_limit(guild_id, limit)
            await _audit_config_change(
                interaction, guild_id, "set_summary_limit", before, config.summary_limit
            )
            await _send_interaction_chunks(
                interaction,
                f"✅ 기본 요약 범위를 최근 {config.summary_limit}개 메시지로 저장했어요.",
                ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="language", description=_loc("기본 응답 언어를 저장합니다."))
    @app_commands.describe(language=_loc("예: ko, en, ja"))
    @app_commands.autocomplete(language=_language_autocomplete)
    async def config_language(interaction: discord.Interaction, language: str) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            before = (await store.get_guild_config(guild_id)).language
            config = await store.set_language(guild_id, language)
            await _audit_config_change(
                interaction, guild_id, "set_language", before, config.language
            )
            await _send_interaction_chunks(
                interaction, f"✅ 기본 응답 언어를 `{config.language}`로 저장했어요.", ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)


    # ------------------------------------------------------------------
    # Phase 3 new commands
    # ------------------------------------------------------------------

    # --- #1/#2 /remind --- 영속화된 리마인더 (요약 결과 또는 임의 텍스트)
    @bot.tree.command(
        name="remind",
        description=_loc("지정한 시간 뒤 DM으로 알림을 보냅니다. (메시지 미지정 시 마지막 요약)"),
    )
    @app_commands.describe(
        when=_loc("언제 보낼지. 예: 30m, 2h, 1d (단위 없으면 분). 최대 30일."),
        message=_loc("알림으로 받을 임의 텍스트. 비우면 마지막 /summarize 결과를 사용합니다."),
        repeat=_loc("(선택) 반복 표시용 라벨. 예: daily, weekly (실제 반복 없이 표시만)"),
    )
    async def remind_command(
        interaction: discord.Interaction,
        when: str,
        message: str = "",
        repeat: str = "",
    ) -> None:
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        if user_id is None:
            await interaction.response.send_message(
                "⚠️ 사용자 정보를 확인할 수 없어요.", ephemeral=True
            )
            return
        # #2: 다른 LLM/예약 진입점과 동일하게 쿨다운을 적용해 스팸성 대량 예약을
        # 막는다. DM(guild_id None)은 센티넬 버킷으로 per-user 쿨다운을 건다.
        cd_guild = guild_id if guild_id is not None else _DM_COOLDOWN_GUILD
        remaining = _check_cooldown(cd_guild, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return
        try:
            delay = _parse_remind_delay(when)
        except UserFacingError as exc:
            await interaction.response.send_message(f"⚠️ {exc}", ephemeral=True)
            return

        # 메시지가 비어 있으면 마지막 요약 결과(_last_summaries)를 사용한다(#2 호환 경로).
        text = message.strip()
        if text:
            kind = _REMIND_KIND_TEXT
        else:
            cached = _last_summaries.get(user_id)
            # #21: _last_summaries 는 user_id 단일 키 캐시라 다른 길드/DM 의 요약이
            # 섞일 수 있다. 캐시에 함께 저장된 guild_id 가 현재 interaction 의
            # guild_id 와 일치할 때만 재사용해, A 길드 요약이 B 길드/DM 에서 새어
            # 나가지 않게 한다.
            cached_guild_id = cached[1] if cached is not None else None
            if cached is None or cached_guild_id != guild_id:
                await interaction.response.send_message(
                    "⚠️ 보낼 내용이 없어요. 메시지를 입력하거나 먼저 /summarize를 실행해 주세요.",
                    ephemeral=True,
                )
                return
            text, _ = cached
            kind = _REMIND_KIND_SUMMARY

        # #2: 저장 payload 길이를 제한해 DB 적체와 장기 평문 보존(요약=대화 PII)을
        # 억제한다. 발송 본문도 어차피 1800~1900자로 잘리므로 정보 손실은 없다.
        text = text[:_MAX_REMIND_TEXT_CHARS]

        # #2: 미발송 리마인더가 사용자별 상한을 넘으면 새 예약을 거절해 무한 적체를
        # 막는다(list_by_user 는 기본적으로 미발송 항목만 반환한다).
        pending = await store.list_by_user(user_id)
        if len(pending) >= _MAX_PENDING_REMINDERS_PER_USER:
            await interaction.response.send_message(
                f"⚠️ 예약 가능한 알림은 최대 {_MAX_PENDING_REMINDERS_PER_USER}개예요. "
                "`/reminders`에서 기존 알림을 취소한 뒤 다시 시도해 주세요.",
                ephemeral=True,
            )
            return

        # #15: storage 는 due_at 을 초 단위(timespec='seconds') 로 가정한다. 마이크로초를
        # 함께 저장하면 list_due 의 문자열 사전식 비교('+'<'.')가 같은 초 경계에서 만기
        # 항목을 누락한다. storage 포맷과 동일하게 초 단위로 통일한다.
        due_at = (datetime.now(timezone.utc) + delay).isoformat(timespec="seconds")
        repeat_label = repeat.strip() or None
        payload = _encode_remind_payload(text, kind=kind, repeat=repeat_label)
        reminder_id = await store.add_reminder(user_id, guild_id, channel_id, due_at, payload)

        # 방금 저장한 행을 기준으로 예약한다(봇 재시작 시에도 on_ready 가 재예약).
        scheduled = Reminder(
            user_id=user_id,
            guild_id=guild_id,
            channel_id=channel_id,
            due_at=due_at,
            payload=payload,
            id=reminder_id,
        )
        _track_task(_schedule_reminder(scheduled), name=f"reminder-{reminder_id}")

        # 사람이 읽기 좋은 지연 표기.
        total_minutes = int(delay.total_seconds() // 60)
        if total_minutes >= 1440:
            when_label = f"{total_minutes // 1440}일"
        elif total_minutes >= 60:
            when_label = f"{total_minutes // 60}시간"
        else:
            when_label = f"{max(total_minutes, 1)}분"
        # #20: repeat 라벨은 표시용일 뿐 실제 반복 재예약은 하지 않는다(1회만 발송).
        # 응답 문구에서도 이를 명확히 해 매일/매주 자동 발송으로 오해하지 않게 한다.
        repeat_note = f" (반복 표시: {repeat_label} · 실제로는 1회만 발송)" if repeat_label else ""
        await interaction.response.send_message(
            f"⏰ {when_label} 후에 DM으로 알림을 보내드릴게요!{repeat_note}", ephemeral=True
        )

    # --- #3 /reminders --- 본인 예약 목록 표시 + 취소
    @bot.tree.command(name="reminders", description=_loc("내 예약 알림 목록을 보고 취소합니다."))
    @app_commands.describe(cancel=_loc("취소할 알림의 ID. 비우면 목록만 표시합니다."))
    async def reminders_command(
        interaction: discord.Interaction, cancel: int | None = None
    ) -> None:
        user_id = interaction.user.id if interaction.user else None
        if user_id is None:
            await interaction.response.send_message(
                "⚠️ 사용자 정보를 확인할 수 없어요.", ephemeral=True
            )
            return

        # 취소 요청: 본인 소유 + 미발송 항목만 삭제 가능.
        if cancel is not None:
            mine = await store.list_by_user(user_id)
            owned = next((r for r in mine if r.id == cancel), None)
            if owned is None:
                await interaction.response.send_message(
                    "⚠️ 해당 ID의 예약 알림이 없거나 본인 것이 아니에요.", ephemeral=True
                )
                return
            await store.delete_reminder(cancel)
            # #12: DB 행 삭제만으로는 sleep 중인 in-memory 전송 태스크가 남아 due
            # 시각에 그대로 발송된다. 살아 있는 태스크를 취소해 실제로 멈춘다.
            live = _reminder_tasks.pop(cancel, None)
            if live is not None and not live.done():
                live.cancel()
            await interaction.response.send_message(
                f"✅ 예약 알림 #{cancel}을(를) 취소했어요.", ephemeral=True
            )
            return

        reminders = await store.list_by_user(user_id)
        if not reminders:
            await interaction.response.send_message(
                "예약된 알림이 없어요. `/remind`로 새 알림을 만들 수 있어요.", ephemeral=True
            )
            return

        embed = discord.Embed(
            title="내 예약 알림",
            description="취소하려면 `/reminders cancel:<ID>` 를 사용하세요.",
            color=discord.Color.from_str("#5865F2"),
        )
        for r in reminders[:20]:
            decoded = _decode_remind_payload(r.payload)
            preview = decoded["text"].replace("\n", " ")[:80]
            kind_label = "요약" if decoded["kind"] == _REMIND_KIND_SUMMARY else "메시지"
            repeat_note = f" · 반복: {decoded['repeat']}" if decoded.get("repeat") else ""
            embed.add_field(
                name=f"#{r.id} · {kind_label}{repeat_note}",
                value=f"예정: {r.due_at}\n{preview or '(내용 없음)'}",
                inline=False,
            )
        await interaction.response.send_message(embed=embed, ephemeral=True)

    # --- #40 /forget-me --- 본인 데이터 전체 삭제 (GDPR)
    @bot.tree.command(name="forget-me", description=_loc("내 데이터를 모두 삭제합니다. (되돌릴 수 없음)"))
    async def forget_me_command(interaction: discord.Interaction) -> None:
        maybe_user_id = interaction.user.id if interaction.user else None
        if maybe_user_id is None:
            await interaction.response.send_message(
                "⚠️ 사용자 정보를 확인할 수 없어요.", ephemeral=True
            )
            return
        user_id: int = maybe_user_id  # None 검사 후의 non-None 로컬(클로저 캡처용)

        # 확인 단계: 버튼으로 한 번 더 동의를 받은 뒤에만 삭제한다(#40).
        class _ForgetConfirmView(discord.ui.View):
            def __init__(self) -> None:
                super().__init__(timeout=60)
                self._owner_id = user_id

            async def interaction_check(self, inner: discord.Interaction) -> bool:
                # 명령을 실행한 본인만 버튼을 누를 수 있게 한다.
                if inner.user and inner.user.id == self._owner_id:
                    return True
                await inner.response.send_message(
                    "⚠️ 본인만 이 작업을 확인할 수 있어요.", ephemeral=True
                )
                return False

            @discord.ui.button(label="삭제 확인", style=discord.ButtonStyle.danger)
            async def confirm(
                self, btn_interaction: discord.Interaction, _button: discord.ui.Button
            ) -> None:
                deleted = await store.delete_user_data(user_id)
                total = sum(deleted.values())
                detail = ", ".join(f"{k}: {v}건" for k, v in deleted.items())
                # 인메모리 캐시(_last_summaries)에서도 흔적을 제거한다.
                _last_summaries.pop(user_id, None)
                for child in self.children:
                    if isinstance(child, discord.ui.Button):
                        child.disabled = True
                self.stop()
                await btn_interaction.response.edit_message(
                    content=f"✅ 데이터를 삭제했어요. (총 {total}건 — {detail})",
                    view=self,
                )

            @discord.ui.button(label="취소", style=discord.ButtonStyle.secondary)
            async def cancel(
                self, btn_interaction: discord.Interaction, _button: discord.ui.Button
            ) -> None:
                for child in self.children:
                    if isinstance(child, discord.ui.Button):
                        child.disabled = True
                self.stop()
                await btn_interaction.response.edit_message(
                    content="취소했어요. 데이터는 그대로 유지됩니다.", view=self
                )

        await interaction.response.send_message(
            "⚠️ 이 작업은 되돌릴 수 없어요. 당신의 채팅 기록, 피드백, 사용 기록, 예약 알림이 "
            "모두 삭제됩니다. 정말 삭제하시겠어요?",
            view=_ForgetConfirmView(),
            ephemeral=True,
        )

    # --- #34 /pin-summary --- pin the summarize result
    @bot.tree.command(name="pin-summary", description=_loc("요약을 실행하고 결과를 채널에 고정합니다."))
    @app_commands.describe(limit=_loc("최근 몇 개 메시지를 요약할지 지정합니다."))
    async def pin_summary_command(interaction: discord.Interaction, limit: int | None = None) -> None:
        if interaction.guild is None:
            await interaction.response.send_message("⚠️ 이 명령은 서버 안에서만 사용할 수 있어요.", ephemeral=True)
            return
        permissions = getattr(interaction.user, "guild_permissions", None)
        if not (permissions and (permissions.administrator or permissions.manage_messages)):
            await interaction.response.send_message(
                "⚠️ 메시지 관리 또는 관리자 권한이 필요해요.", ephemeral=True
            )
            return
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            message_limit = _effective_limit(limit, config.summary_limit)
            transcript = await _collect_transcript(
                interaction.channel,
                before=interaction.created_at,
                limit=message_limit,
                max_context_chars=settings.max_context_chars,
            )
            if not transcript:
                raise UserFacingError("요약할 메시지가 없어요.")
            prompt = build_summarize_prompt(transcript, language=config.language)
            # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
            await _enforce_token_budget(store, config, guild_id)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            p_tokens, c_tokens = _usage_tokens(llm)  # #17
            sent_msg = await interaction.followup.send(
                f"📌 **요약 (고정됨)**\n{answer}", wait=True
            )
            try:
                await sent_msg.pin()
                await interaction.followup.send("✅ 요약이 채널에 고정됐어요.", ephemeral=True)
            except discord.Forbidden:
                await interaction.followup.send("⚠️ 메시지를 고정할 권한이 없어요.", ephemeral=True)
            except discord.HTTPException as exc:
                await interaction.followup.send(f"⚠️ 고정 실패: {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="pin_summary", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="pin_summary", status="error", started_at=started, error=str(exc),
            )

    # --- #35 /summarize-channels --- multi-channel summary
    @bot.tree.command(name="summarize-channels", description=_loc("여러 채널을 선택해 통합 요약합니다."))
    async def summarize_channels_command(interaction: discord.Interaction) -> None:
        guild = interaction.guild
        if guild is None:
            await interaction.response.send_message("⚠️ 이 명령은 서버 안에서만 사용할 수 있어요.", ephemeral=True)
            return
        text_channels = [
            ch for ch in guild.text_channels
            if ch.permissions_for(guild.me).read_message_history
        ]
        if not text_channels:
            await interaction.response.send_message("⚠️ 읽기 가능한 텍스트 채널이 없어요.", ephemeral=True)
            return

        async def _on_confirm(confirm_interaction: discord.Interaction, channel_ids: list[str]) -> None:
            await confirm_interaction.response.defer(thinking=True)
            if not channel_ids:
                await confirm_interaction.followup.send("⚠️ 선택된 채널이 없습니다.", ephemeral=True)
                return
            started = perf_counter()
            guild_id, channel_id, user_id = _ids_from_interaction(confirm_interaction)
            try:
                config = await store.get_guild_config(guild_id or 0)
                # #90: 다른 LLM 진입점과 동일하게 역할 제한을 적용한다.
                if not _has_allowed_role(confirm_interaction, config.allowed_role_id):
                    raise UserFacingError("이 명령을 사용할 권한이 없어요. 서버 관리자에게 문의하세요.")
                message_limit = config.summary_limit
                # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
                await _enforce_token_budget(store, config, guild_id)
                # Build the LLM client once and reuse it across all channels (#35)
                llm = _get_llm(config, settings)

                async def _summarize_one(ch_id: str) -> tuple[str, str]:
                    ch = guild.get_channel(int(ch_id))
                    if ch is None or not hasattr(ch, "history"):
                        return ch_id, "(채널을 찾을 수 없음)"
                    try:
                        transcript = await _collect_transcript(
                            ch,
                            before=confirm_interaction.created_at,
                            limit=message_limit,
                            max_context_chars=settings.max_context_chars // len(channel_ids),
                        )
                        if not transcript:
                            return getattr(ch, "name", ch_id), "(메시지 없음)"
                        prompt = build_summarize_prompt(transcript, language=config.language)
                        answer = await llm.generate(prompt, model=config.model)
                        return getattr(ch, "name", ch_id), answer
                    except Exception as e:
                        # #1/#95: 원본 예외 문자열(제공자 4xx 본문/키 일부/내부 단서)을
                        # 임베드에 그대로 노출하지 않는다. 사용자에겐 일반 안내만 보이고
                        # 실제 detail 은 서버 로그로만 남긴다.
                        logger.warning(
                            "멀티 채널 요약 중 채널 처리 실패: ch_id=%s %s", ch_id, e
                        )
                        return getattr(ch, "name", ch_id), "(이 채널을 요약하는 중 오류가 발생했어요)"

                results = await asyncio.gather(*[_summarize_one(cid) for cid in channel_ids])

                embed = discord.Embed(
                    title="멀티 채널 통합 요약",
                    color=discord.Color.from_str("#5865F2"),
                )
                for ch_name, summary in results:
                    embed.add_field(
                        name=f"#{ch_name}",
                        value=_truncate(summary),
                        inline=False,
                    )
                await confirm_interaction.followup.send(embed=embed)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="summarize_channels", status="ok", started_at=started,
                )
            except (UserFacingError, LLMError) as exc:
                await confirm_interaction.followup.send(f"⚠️ {exc}", ephemeral=True)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="summarize_channels", status="error", started_at=started, error=str(exc),
                )

        view = ChannelSelectView(channels=text_channels, on_confirm=_on_confirm)
        await interaction.response.send_message("요약할 채널을 선택하세요:", view=view, ephemeral=True)

    # --- #41 /export --- export channel messages as markdown file
    @bot.tree.command(name="export", description=_loc("채널 메시지를 마크다운 파일로 내보내기 (DM 전송)"))
    @app_commands.describe(limit=_loc("내보낼 메시지 수 (기본값: 서버 설정)"))
    async def export_command(interaction: discord.Interaction, limit: int | None = None) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        await interaction.response.defer(thinking=True, ephemeral=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            # #90: 다른 LLM 진입점과 동일하게 역할 제한을 적용한다.
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요. 서버 관리자에게 문의하세요.")
            message_limit = _effective_limit(limit, config.summary_limit)
            # #6: interaction.channel 이 None 이면 .history 가 AttributeError 를 던져
            # 바깥 except 에 걸리지 않는다. _collect_transcript 와 동일한 가드를 둔다.
            export_channel = _require_history_channel(interaction.channel)
            messages = []
            try:
                async for msg in export_channel.history(
                    limit=message_limit, before=interaction.created_at
                ):
                    messages.append(msg)
            except discord.Forbidden as exc:
                raise UserFacingError("봇에 Read Message History 권한이 없어요.") from exc
            messages.reverse()

            lines = [
                f"# {getattr(interaction.channel, 'name', 'channel')} 내보내기",
                "",
            ]
            for msg in messages:
                ts = msg.created_at.strftime("%Y-%m-%d %H:%M") if msg.created_at else ""
                lines.append(f"**{msg.author.display_name}** [{ts}]")
                if msg.content:
                    lines.append(msg.content)
                # Include attachments and embeds, not just text (#27)
                for att in msg.attachments:
                    lines.append(f"- [첨부] {att.filename}: {att.url}")
                for emb in msg.embeds:
                    emb_title = emb.title or "(제목 없음)"
                    lines.append(f"- [임베드] {emb_title}")
                    if emb.description:
                        lines.append(f"  > {emb.description}")
                lines.append("")

            md_text = "\n".join(lines)
            md_bytes = md_text.encode("utf-8")
            if len(md_bytes) > MAX_EXPORT_BYTES:
                raise UserFacingError("파일 크기가 8MB를 초과해 전송할 수 없어요. limit을 줄여서 시도해 주세요.")

            file_obj = io.BytesIO(md_bytes)
            discord_file = discord.File(file_obj, filename="export.md")
            try:
                await interaction.user.send(
                    f"📄 {getattr(interaction.channel, 'name', 'channel')} 채널 내보내기",
                    file=discord_file,
                )
                await _send_interaction_chunks(interaction, "✅ DM으로 마크다운 파일을 전송했어요!", ephemeral=True)
            except discord.Forbidden:
                await _send_interaction_chunks(
                    interaction, "⚠️ DM을 보낼 수 없어요. 개인 메시지 설정을 확인해 주세요.", ephemeral=True
                )
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="export", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="export", status="error", started_at=started, error=str(exc),
            )

    # --- #43 /stats --- server usage statistics
    @bot.tree.command(name="stats", description=_loc("서버 봇 사용 통계를 표시합니다."))
    async def stats_command(interaction: discord.Interaction) -> None:
        if interaction.guild is None:
            await interaction.response.send_message("⚠️ 이 명령은 서버 안에서만 사용할 수 있어요.", ephemeral=True)
            return
        await interaction.response.defer(thinking=True)
        # #5: get_stats 키를 일관되게 .get(...) 으로 읽고, 스키마 변경/조회 실패 시에도
        # defer 후 침묵하지 않도록 친절 폴백을 둔다(KeyError → 개발자 DM 방지).
        try:
            stats = await store.get_stats(interaction.guild.id)
        except Exception as exc:
            logger.warning("서버 통계 조회 실패: %s", exc)
            await interaction.followup.send(
                "⚠️ 통계를 불러오지 못했어요. 잠시 후 다시 시도해주세요.", ephemeral=True
            )
            return
        now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

        # Build a human-readable date range from the actual first/last activity (#69)
        def _fmt_day(iso_ts: str | None) -> str | None:
            if not iso_ts:
                return None
            try:
                return datetime.fromisoformat(iso_ts).strftime("%Y-%m-%d")
            except ValueError:
                return iso_ts[:10]

        first_day = _fmt_day(stats.get("first_at"))
        last_day = _fmt_day(stats.get("last_at"))
        if first_day and last_day:
            period = first_day if first_day == last_day else f"{first_day} ~ {last_day}"
            description = f"집계 기간: {period} · 조회 시각: {now_str}"
        else:
            description = f"집계된 사용 기록 없음 · 조회 시각: {now_str}"

        embed = discord.Embed(
            title="서버 사용 통계",
            description=description,
            color=discord.Color.from_str("#5865F2"),
        )
        embed.add_field(name="총 사용 횟수", value=str(stats.get("total", 0)), inline=True)
        embed.add_field(name="평균 응답 시간", value=f"{stats.get('avg_latency_ms', 0)}ms", inline=True)
        embed.add_field(name="에러율", value=f"{stats.get('error_rate', 0)}%", inline=True)
        by_command = stats.get("by_command") or []
        if by_command:
            cmd_lines = [f"`{r['command']}`: {r['count']}회" for r in by_command[:10]]
            embed.add_field(name="명령어별 사용 횟수", value="\n".join(cmd_lines), inline=False)
        await interaction.followup.send(embed=embed)

    # --- #47 /search --- keyword search + LLM summary
    @bot.tree.command(name="search", description=_loc("채널에서 키워드로 메시지를 검색하고 요약합니다."))
    @app_commands.describe(
        query=_loc("검색할 키워드입니다."),
        limit=_loc("최대 몇 개 메시지를 검색할지 지정합니다. 기본값: 200"),
    )
    async def search_command(
        interaction: discord.Interaction,
        query: str,
        limit: int | None = None,
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            # #90: 다른 LLM 진입점과 동일하게 역할 제한을 적용한다.
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요. 서버 관리자에게 문의하세요.")
            search_limit = _effective_limit(limit, 200)
            query_lower = query.lower()
            # #6: None 채널에서 .history 가 AttributeError 로 침묵 실패하지 않게 가드한다.
            search_channel = _require_history_channel(interaction.channel)
            matching: list[str] = []
            try:
                async for msg in search_channel.history(
                    limit=search_limit, before=interaction.created_at
                ):
                    if query_lower in msg.content.lower():
                        ts = msg.created_at.strftime("%H:%M") if msg.created_at else ""
                        matching.append(f"[{ts}] {msg.author.display_name}: {msg.content[:200]}")
                        if len(matching) >= MAX_SEARCH_MATCHES:
                            break
            except discord.Forbidden as exc:
                raise UserFacingError("봇에 Read Message History 권한이 없어요.") from exc

            if not matching:
                await _send_interaction_chunks(
                    interaction, f"검색 결과 없음: `{query}`에 일치하는 메시지가 없어요."
                )
                return

            transcript = "\n".join(matching)
            prompt = build_search_result_prompt(transcript, query, language=config.language)
            # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
            await _enforce_token_budget(store, config, guild_id)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            p_tokens, c_tokens = _usage_tokens(llm)  # #17

            embed = discord.Embed(
                title=f"검색 결과: {query}",
                color=discord.Color.from_str("#5865F2"),
            )
            embed.add_field(name=f"일치 메시지 수 (최대 {MAX_SEARCH_MATCHES}개 표시)", value=f"{len(matching)}개", inline=True)
            embed.add_field(name="검색 범위", value=f"최근 {search_limit}개 메시지", inline=True)
            embed.add_field(name="요약", value=_truncate(answer), inline=False)
            # #8: 요약이 1024 자를 넘으면 'DM 으로 전체 받기' 버튼으로 전체를 제공한다.
            search_view = _overflow_view(answer)
            if search_view is not None:
                await interaction.followup.send(embed=embed, view=search_view)
            else:
                await interaction.followup.send(embed=embed)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="search", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="search", status="error", started_at=started, error=str(exc),
            )

    # --- #11 /digest --- 기간 기반 '오늘의 정리'
    @bot.tree.command(name="digest", description=_loc("지정한 기간의 대화를 핵심·결정·액션으로 정리합니다."))
    @app_commands.describe(since=_loc("정리할 기간. 예: 30m, 1h, 6h, 1d (기본: 1d)"))
    @app_commands.autocomplete(since=_since_autocomplete)
    async def digest_command(
        interaction: discord.Interaction,
        since: str = "1d",
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return
        await interaction.response.defer(thinking=True)
        try:
            since_dt = _parse_since(since)
            config = await store.get_guild_config(guild_id or 0)
            # 역할 제한 검사 (#49 와 동일 정책).
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요. 서버 관리자에게 문의하세요.")
            # #4: digest 는 기간 전체(since:Xd)를 정리하는 명령이므로 summary_limit
            # (기본 50)으로 묶으면 활발한 채널에서 기간의 일부만 다룬다. 기간형
            # 명령은 허용 최대치(200)까지 메시지를 모으고, 최종 프롬프트 길이는
            # max_context_chars 가 묶는다. (_collect_transcript 는 윈도우의 최신
            # 메시지부터 채우므로 가장 관련 높은 최근 활동이 우선 포함된다.)
            digest_limit = _effective_limit(200, config.summary_limit)
            transcript = await _collect_transcript(
                interaction.channel,
                before=interaction.created_at,
                limit=digest_limit,
                max_context_chars=settings.max_context_chars,
                after=since_dt,
            )
            if not transcript:
                raise UserFacingError("정리할 메시지가 없어요. 해당 기간에 대화가 있어야 합니다.")

            effective_language = config.language
            if effective_language == "auto":
                effective_language = detect_language_from_transcript(transcript)
            # build_summarize_prompt 를 재사용해 '핵심·결정·액션' 구조 요약을 만든다 (#11).
            prompt = build_summarize_prompt(transcript, language=effective_language)
            # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
            await _enforce_token_budget(store, config, guild_id)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            p_tokens, c_tokens = _usage_tokens(llm)  # #17

            header = f"📋 **오늘의 정리** (최근 {since})\n"
            first, *rest = _split_discord_text(header + answer)
            msg = await interaction.followup.send(first, wait=True)
            for chunk in rest:
                await interaction.followup.send(chunk)
            await _track_for_feedback(guild_id, msg, "digest")
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="digest", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="digest", status="error", started_at=started, error=str(exc),
            )

    # --- #94 /usage --- 본인 사용량 + 쿨다운 + 서버 한도 안내
    @bot.tree.command(name="usage", description=_loc("내 사용량과 쿨다운, 서버 한도를 확인합니다."))
    async def usage_command(interaction: discord.Interaction) -> None:
        guild_id, _channel_id, user_id = _ids_from_interaction(interaction)
        config = await store.get_guild_config(guild_id or 0)

        # 남은 쿨다운은 상태를 갱신하지 않고 조회만 한다(_check_cooldown 은 갱신하므로
        # 사용하지 않는다). _cooldowns 의 마지막 사용 시각으로 직접 계산한다.
        cooldown_note = "없음 (바로 사용 가능)"
        if guild_id is not None and user_id is not None:
            last = _cooldowns.get((guild_id, user_id))
            if last is not None:
                elapsed = perf_counter() - last
                if elapsed < COOLDOWN_SECONDS:
                    cooldown_note = f"{COOLDOWN_SECONDS - elapsed:.0f}초 남음"

        # 서버 전체 사용 통계에서 본인 명령 수를 별도 집계할 헬퍼가 없으므로,
        # get_stats(서버 단위)로 전체 사용 현황을 보여주고 서버 한도를 함께 안내한다 (#94).
        embed = discord.Embed(
            title="내 사용량 / 서버 한도",
            color=discord.Color.from_str("#5865F2"),
        )
        if guild_id is not None:
            # #5: get_stats 키를 일관되게 .get(...) 으로 읽어 스키마 변화에 견디게 한다.
            stats = await store.get_stats(guild_id)
            embed.add_field(name="서버 총 사용 횟수", value=str(stats.get("total", 0)), inline=True)
            embed.add_field(name="평균 응답 시간", value=f"{stats.get('avg_latency_ms', 0)}ms", inline=True)
            embed.add_field(name="에러율", value=f"{stats.get('error_rate', 0)}%", inline=True)
        embed.add_field(name="남은 쿨다운", value=cooldown_note, inline=True)
        embed.add_field(
            name="쿨다운 간격", value=f"{COOLDOWN_SECONDS}초", inline=True
        )
        # _effective_limit 가 200 초과를 조용히 깎으므로, 서버 요약 한도와 함께
        # 실제 적용 상한을 명시해 사용자가 혼동하지 않게 안내한다 (#94).
        applied_limit = _effective_limit(config.summary_limit, config.summary_limit)
        limit_note = f"{config.summary_limit}개"
        if config.summary_limit > 200:
            limit_note = f"{config.summary_limit}개 → 실제 {applied_limit}개로 제한 적용"
        embed.add_field(name="서버 요약 범위(summary_limit)", value=limit_note, inline=True)
        embed.set_footer(text="요약·질문 명령은 위 쿨다운 간격으로 제한됩니다.")
        await interaction.response.send_message(embed=embed, ephemeral=True)

    # --- #4 컨텍스트 메뉴 --- 우클릭 → 메시지 번역/요약/질문
    # 메시지 대상 컨텍스트 메뉴 3개. 우클릭한 메시지 내용을 기존 build_* 프롬프트에
    # 그대로 넣어(인젝션 방어 내장) ephemeral 로 응답한다. create_bot 안에서
    # bot.tree.add_command 로 등록한다.

    async def _ctx_menu_reply(interaction: discord.Interaction, text: str) -> None:
        """컨텍스트 메뉴 가드 안내를 보낸다 (#33).

        이 가드는 이제 핸들러가 defer() 로 ACK 를 먼저 확보한 뒤 호출되므로,
        응답이 끝났으면 followup, 아니면 첫 응답으로 보낸다(양쪽 안전).
        """
        if interaction.response.is_done():
            await interaction.followup.send(text, ephemeral=True)
        else:
            await interaction.response.send_message(text, ephemeral=True)

    async def _ctx_menu_guard(
        interaction: discord.Interaction, content: str
    ) -> tuple[GuildConfig, BaseLLMClient] | None:
        """컨텍스트 메뉴 공통 가드: 쿨다운·빈 메시지 확인 후 (config, llm) 반환.

        가드에 걸리면 사용자에게 ephemeral 안내를 보내고 None 을 반환한다.

        #33: 이 가드는 get_guild_config + _enforce_token_budget 로 DB 왕복을 하므로,
        ACK 시한(3초) 안전을 위해 호출부가 먼저 defer() 로 ACK 를 확보한 뒤 호출한다.
        가드 실패 안내는 _ctx_menu_reply 로 defer 여부에 맞춰 보낸다.
        """
        guild_id, _channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await _ctx_menu_reply(
                interaction, f"⏳ {remaining:.0f}초 후에 다시 시도해주세요."
            )
            return None
        if not content.strip():
            await _ctx_menu_reply(interaction, "⚠️ 대상 메시지에 처리할 텍스트가 없어요.")
            return None
        config = await store.get_guild_config(guild_id or 0)
        # #90: 컨텍스트 메뉴 3종도 LLM 을 호출하므로 다른 진입점과 동일하게 역할
        # 제한을 적용한다. 권한이 없으면 ephemeral 안내 후 None 을 반환한다.
        if not _has_allowed_role(interaction, config.allowed_role_id):
            await _ctx_menu_reply(
                interaction, "이 기능을 사용할 권한이 없어요. 서버 관리자에게 문의하세요."
            )
            return None
        # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
        await _enforce_token_budget(store, config, guild_id)
        llm = _get_llm(config, settings)
        return config, llm

    async def _translate_message_ctx(
        interaction: discord.Interaction, message: discord.Message
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        try:
            # #33: 가드의 DB 왕복(2회)이 ACK 시한을 밀지 않도록, 가장 먼저 defer 로
            # ACK 를 확보한 뒤 쿨다운/빈 메시지/역할/토큰 상한 검사를 수행한다.
            await interaction.response.defer(thinking=True, ephemeral=True)
            guard = await _ctx_menu_guard(interaction, message.content)
            if guard is None:
                return
            config, llm = guard
            # 컨텍스트 메뉴 번역은 서버 언어 설정으로 번역한다(auto 면 한국어로 폴백).
            target = config.language if config.language != "auto" else "ko"
            prompt = build_translate_prompt(message.content, target_language=target)
            answer = await llm.generate(prompt, model=config.model)
            p_tokens, c_tokens = _usage_tokens(llm)  # #17
            await _send_interaction_chunks(interaction, f"🌐 **번역 ({target})**\n{answer}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_translate", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_translate", status="error", started_at=started, error=str(exc),
            )

    async def _summarize_message_ctx(
        interaction: discord.Interaction, message: discord.Message
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        try:
            # #33: ACK 시한 안전을 위해 가드의 DB 왕복 전에 먼저 defer 한다.
            await interaction.response.defer(thinking=True, ephemeral=True)
            guard = await _ctx_menu_guard(interaction, message.content)
            if guard is None:
                return
            config, llm = guard
            language = config.language
            if language == "auto":
                language = detect_language_from_transcript(message.content)
            prompt = build_summarize_prompt(message.content, language=language)
            answer = await llm.generate(prompt, model=config.model)
            p_tokens, c_tokens = _usage_tokens(llm)  # #17
            await _send_interaction_chunks(interaction, f"📝 **메시지 요약**\n{answer}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_summarize", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_summarize", status="error", started_at=started, error=str(exc),
            )

    async def _ask_message_ctx(
        interaction: discord.Interaction, message: discord.Message
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        try:
            # #33: ACK 시한 안전을 위해 가드의 DB 왕복 전에 먼저 defer 한다.
            await interaction.response.defer(thinking=True, ephemeral=True)
            guard = await _ctx_menu_guard(interaction, message.content)
            if guard is None:
                return
            config, llm = guard
            language = config.language
            if language == "auto":
                language = detect_language_from_transcript(message.content)
            # 우클릭한 메시지를 트랜스크립트로, 고정 질문으로 build_ask_prompt 를 호출한다.
            question = "이 메시지의 핵심 내용을 설명하고, 궁금한 점에 답해줘."
            prompt = build_ask_prompt(message.content, question, language=language)
            answer = await llm.generate(prompt, model=config.model)
            p_tokens, c_tokens = _usage_tokens(llm)  # #17
            await _send_interaction_chunks(interaction, f"💬 **이 메시지로 질문**\n{answer}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_ask", status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_ask", status="error", started_at=started, error=str(exc),
            )

    bot.tree.add_command(
        app_commands.ContextMenu(name="메시지 번역", callback=_translate_message_ctx)
    )
    bot.tree.add_command(
        app_commands.ContextMenu(name="메시지 요약", callback=_summarize_message_ctx)
    )
    bot.tree.add_command(
        app_commands.ContextMenu(name="이 메시지로 질문", callback=_ask_message_ctx)
    )

    # --- Phase 3 /config subcommands ---

    @config_group.command(name="admin_role", description=_loc("봇 설정 권한을 가진 역할을 지정합니다."))
    @app_commands.describe(role=_loc("설정 권한을 부여할 역할입니다."))
    async def config_admin_role(interaction: discord.Interaction, role: discord.Role) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            before = (await store.get_guild_config(guild_id)).admin_role_id
            await store.set_admin_role(guild_id, role.id)
            await _audit_config_change(interaction, guild_id, "set_admin_role", before, role.id)
            await _send_interaction_chunks(
                interaction, f"✅ 관리 역할을 `{role.name}`으로 설정했어요.", ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    _MAX_PERSONA_CHARS = 500
    _MAX_CUSTOM_PROMPT_CHARS = 2000

    @config_group.command(name="persona", description=_loc("/chat 페르소나를 설정합니다."))
    @app_commands.describe(description=_loc("봇의 페르소나 설명입니다. 비워두면 초기화합니다."))
    async def config_persona(interaction: discord.Interaction, description: str = "") -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            persona = _sanitize_persona(description) or None
            if persona and len(persona) > _MAX_PERSONA_CHARS:
                raise UserFacingError(f"페르소나는 {_MAX_PERSONA_CHARS}자 이하여야 합니다.")
            before = (await store.get_guild_config(guild_id)).persona
            await store.set_persona(guild_id, persona)
            # 감사 로그에는 긴 본문 대신 길이가 제한된 요약만 남긴다(#39).
            await _audit_config_change(
                interaction,
                guild_id,
                "set_persona",
                None if before is None else before[:100],
                None if persona is None else persona[:100],
            )
            if persona:
                await _send_interaction_chunks(
                    interaction, f"✅ 페르소나를 설정했어요: `{persona[:100]}`", ephemeral=True,
                )
            else:
                await _send_interaction_chunks(interaction, "✅ 페르소나를 초기화했어요.", ephemeral=True)
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="auto_summary", description=_loc("자동 요약 간격을 설정합니다. (최소 5분, 0이면 비활성화)"))
    @app_commands.describe(interval=_loc("자동 요약 간격 (분, 최소 5). 0이면 비활성화."))
    async def config_auto_summary(interaction: discord.Interaction, interval: int) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            effective_interval = None if interval <= 0 else interval
            before = (await store.get_guild_config(guild_id)).auto_summary_interval
            await store.set_auto_summary_interval(guild_id, effective_interval)
            await _audit_config_change(
                interaction, guild_id, "set_auto_summary", before, effective_interval
            )
            if effective_interval:
                await _send_interaction_chunks(
                    interaction, f"✅ 자동 요약 간격을 {effective_interval}분으로 설정했어요.", ephemeral=True,
                )
            else:
                await _send_interaction_chunks(interaction, "✅ 자동 요약을 비활성화했어요.", ephemeral=True)
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="custom_prompt", description=_loc("커스텀 프롬프트를 설정합니다."))
    @app_commands.describe(
        prompt_type=_loc("프롬프트 유형: summarize 또는 ask"),
        text=_loc("커스텀 프롬프트 내용. 비워두면 초기화."),
    )
    @app_commands.autocomplete(prompt_type=_prompt_type_autocomplete)
    async def config_custom_prompt(
        interaction: discord.Interaction, prompt_type: str, text: str = ""
    ) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            effective_text = text.strip() or None
            if effective_text and len(effective_text) > _MAX_CUSTOM_PROMPT_CHARS:
                raise UserFacingError(f"커스텀 프롬프트는 {_MAX_CUSTOM_PROMPT_CHARS}자 이하여야 합니다.")
            prev_config = await store.get_guild_config(guild_id)
            before = (
                prev_config.custom_summarize_prompt
                if prompt_type == "summarize"
                else prev_config.custom_ask_prompt
            )
            await store.set_custom_prompt(guild_id, prompt_type, effective_text)
            # 긴 프롬프트 본문 대신 길이 제한 요약만 감사 로그에 남긴다(#39).
            await _audit_config_change(
                interaction,
                guild_id,
                f"set_custom_prompt_{prompt_type}",
                None if before is None else before[:100],
                None if effective_text is None else effective_text[:100],
            )
            if effective_text:
                await _send_interaction_chunks(
                    interaction, f"✅ `{prompt_type}` 커스텀 프롬프트를 저장했어요.", ephemeral=True,
                )
            else:
                await _send_interaction_chunks(
                    interaction, f"✅ `{prompt_type}` 커스텀 프롬프트를 초기화했어요.", ephemeral=True
                )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="allowed_role", description=_loc("명령어 사용 가능 역할을 설정합니다."))
    @app_commands.describe(role=_loc("명령어를 사용할 수 있는 역할입니다."))
    async def config_allowed_role(interaction: discord.Interaction, role: discord.Role | None = None) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            role_id = role.id if role else None
            before = (await store.get_guild_config(guild_id)).allowed_role_id
            await store.set_allowed_role(guild_id, role_id)
            await _audit_config_change(
                interaction, guild_id, "set_allowed_role", before, role_id
            )
            if role:
                await _send_interaction_chunks(
                    interaction, f"✅ `{role.name}` 역할만 명령어를 사용할 수 있어요.", ephemeral=True,
                )
            else:
                await _send_interaction_chunks(interaction, "✅ 역할 제한을 해제했어요.", ephemeral=True)
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    # --- #19 /config daily_token_budget --- 서버별 일일 토큰 상한
    @config_group.command(
        name="daily_token_budget",
        description=_loc("서버의 일일 토큰 사용 상한을 설정합니다. (0이면 무제한)"),
    )
    @app_commands.describe(budget=_loc("하루 동안 사용할 수 있는 최대 토큰 수. 0이면 무제한."))
    async def config_daily_token_budget(
        interaction: discord.Interaction, budget: int
    ) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            if budget < 0:
                raise UserFacingError("일일 토큰 상한은 0 이상이어야 해요. (0이면 무제한)")
            # 0 은 '무제한'(None)으로 해석한다 — 자동 요약 간격(0=비활성)과 동일한 관용.
            effective_budget = None if budget == 0 else budget
            before = (await store.get_guild_config(guild_id)).daily_token_budget
            await store.set_daily_token_budget(guild_id, effective_budget)
            await _audit_config_change(
                interaction, guild_id, "set_daily_token_budget", before, effective_budget
            )
            if effective_budget is not None:
                await _send_interaction_chunks(
                    interaction,
                    f"✅ 일일 토큰 상한을 {effective_budget:,} 토큰으로 설정했어요. "
                    "(매일 자정 UTC 기준 초기화)",
                    ephemeral=True,
                )
            else:
                await _send_interaction_chunks(
                    interaction, "✅ 일일 토큰 상한을 해제했어요. (무제한)", ephemeral=True
                )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    bot.tree.add_command(config_group)

    # ------------------------------------------------------------------
    # Events
    # ------------------------------------------------------------------

    @bot.event
    async def on_ready() -> None:
        assert bot.user is not None
        logger.info("Logged in as %s (id=%s)", bot.user, bot.user.id)
        # #25: on_ready 는 재연결(RESUME)마다 발화한다. 길드별 copy_global_to+sync 는
        # 강한 레이트리밋 대상이므로 최초 1회만 동기화해 중복 PUT 을 막는다.
        if not getattr(bot, "_guild_synced", False):
            bot._guild_synced = True
            for guild in bot.guilds:
                bot.tree.copy_global_to(guild=guild)
                synced = await bot.tree.sync(guild=guild)
                logger.info("Guild-synced %d command(s) to %s", len(synced), guild.name)
        # fire-and-forget 태스크는 _track_task 로 추적해 조용한 소실/예외 삼킴 방지 (#51).
        _track_task(_memory_monitor(), name="memory-monitor")
        # 봇 재시작에도 미발송 reminder 가 살아남도록 다시 예약한다 (#1).
        # #13: on_ready 는 재연결(RESUME)마다 발화하므로, reschedule 는 최초 1회만
        # 실행해 동일 reminder 의 중복 sleep 태스크 생성을 막는다(중복 DM 방지).
        if not getattr(bot, "_reschedule_done", False):
            bot._reschedule_done = True
            _track_task(_reschedule_pending_reminders(), name="reschedule-reminders")
        # Start auto-summary background task (#33)
        if not auto_summary_task.is_running():
            auto_summary_task.start()
        # Start retention 정리 백그라운드 태스크 (#27)
        if not retention_task.is_running():
            retention_task.start()
        await bot.change_presence(
            activity=discord.Activity(
                type=discord.ActivityType.watching,
                name=f"{len(bot.guilds)}개 서버",
            )
        )

    async def _memory_monitor() -> None:
        """Log process memory usage every hour."""
        try:
            import psutil  # type: ignore[import-untyped]
            while True:
                await asyncio.sleep(3600)
                proc = psutil.Process(os.getpid())
                mem_mb = proc.memory_info().rss / 1024 / 1024
                logger.info("메모리 사용량: %.1f MB", mem_mb)
        except ImportError:
            logger.debug("psutil not installed; memory monitoring disabled.")

    @bot.event
    async def on_message(message: discord.Message) -> None:
        if message.author.bot or bot.user is None:
            await bot.process_commands(message)
            return

        # Invalidate summarize cache for this channel on every new non-bot message
        guild_id_raw = message.guild.id if message.guild else 0
        channel_id_raw = message.channel.id if hasattr(message.channel, "id") else 0
        summarize_cache.invalidate_prefix(f"{guild_id_raw}:{channel_id_raw}")

        await bot.process_commands(message)

        # --- DM support (#48) ---
        if message.guild is None:
            # DM mode: treat as /chat without channel context
            # #40: 빈/공백 메시지나 접두 명령(!...)은 LLM 폴백 대상이 아니다. 명령은
            # 위 process_commands 가 이미 처리했고, 빈 입력은 엉뚱한 프롬프트가 되므로
            # 불필요한 LLM 호출/토큰 소비 전에 건너뛴다.
            if not message.content.strip() or message.content.startswith("!"):
                return
            user_id = message.author.id
            dm_remaining = _check_cooldown(_DM_COOLDOWN_GUILD, user_id)
            if dm_remaining is not None:
                try:
                    await message.channel.send(f"⏳ {dm_remaining:.0f}초 후에 다시 시도해주세요.")
                except discord.DiscordException as exc:
                    # #30: 광의의 except 로 제어 예외(CancelledError 등)까지 삼키지
                    # 않는다. 전송 실패 사유는 debug 로 남겨 추적 가능하게 한다.
                    logger.debug("DM 쿨다운 안내 전송 실패: %s", exc)
                return
            started = perf_counter()
            # #92: DM 대화 기억을 user 전역(guild_id=None)으로 저장/조회하면
            # storage 가 `WHERE user_id=?` 전역 분기로 떨어져 같은 사용자의 다른
            # 길드 대화가 DM 응답 맥락으로 섞여 들어온다(컨텍스트 누수). DM 전용
            # 센티넬 guild_id + 실제 DM 채널 id 로 명시 스코프해 격리한다.
            dm_scope_guild = _DM_COOLDOWN_GUILD
            dm_channel_id = message.channel.id if hasattr(message.channel, "id") else None
            try:
                config = await store.get_guild_config(0)  # use default config
                # #10/#92: 직전 DM 대화를 DM 전용 스코프로만 이어 붙인다.
                history = await store.get_chat_history(
                    user_id, guild_id=dm_scope_guild, channel_id=dm_channel_id, limit=10
                )
                if history:
                    prompt = build_chat_with_history_prompt(
                        message.content, history, language=config.language
                    )
                else:
                    prompt = build_chat_prompt(
                        message.content, language=config.language, persona=config.persona
                    )
                # #29: DM 은 guild_id=None 이라 그동안 서버 일일 토큰 상한을 전혀
                # 받지 않아 0번 설정의 외부 키로 비용이 무제한 노출됐다. DM 사용량을
                # 0번(센티넬) 버킷에 기록하고 동일 버킷의 상한을 검사해, 관리자가
                # `/config daily_token_budget`(0번 설정)으로 DM 비용을 캡할 수 있게
                # 한다. 상한 미설정(None)이면 기존 동작과 동일(무제한, 검사 no-op).
                await _enforce_token_budget(store, config, dm_scope_guild)
                llm = _get_llm(config, settings)
                async with message.channel.typing():
                    answer = await llm.generate(prompt, model=config.model)
                p_tokens, c_tokens = _usage_tokens(llm)  # #17
                await _send_channel_chunks(message.channel, answer)
                # 대화를 DM 전용 스코프로 저장해 다음 DM 에서만 맥락을 이어간다 (#10/#92).
                await store.save_chat_message(
                    user_id, "user", message.content,
                    guild_id=dm_scope_guild, channel_id=dm_channel_id,
                )
                await store.save_chat_message(
                    user_id, "assistant", answer,
                    guild_id=dm_scope_guild, channel_id=dm_channel_id,
                )
                # #29: DM 토큰 사용량을 0번(센티넬) 버킷에 기록해 일일 상한 집계에
                # 반영한다(get_today_token_usage 는 guild_id 별 집계).
                await _record_usage(
                    store, guild_id=dm_scope_guild, channel_id=dm_channel_id, user_id=user_id,
                    command="dm_chat", status="ok", started_at=started,
                    prompt_tokens=p_tokens, completion_tokens=c_tokens,
                )
            except Exception as exc:
                # Mirror the guild path: surface only user-facing/LLM detail,
                # keep internal exceptions generic (consistent with #64).
                if isinstance(exc, (UserFacingError, LLMError)):
                    user_msg = f"⚠️ {exc}"
                else:
                    logger.exception("DM chat handler error: %s", exc)
                    user_msg = "⚠️ 예기치 않은 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
                try:
                    await message.channel.send(user_msg)
                except discord.DiscordException as send_exc:
                    # #30: 안내 전송 실패 사유를 남기고, 제어 예외는 삼키지 않는다.
                    logger.debug("DM 오류 안내 전송 실패: %s", send_exc)
                await _record_usage(
                    store, guild_id=dm_scope_guild, channel_id=dm_channel_id, user_id=user_id,
                    command="dm_chat", status="error", started_at=started, error=str(exc),
                )
            return

        # --- #8 답장 맥락 --- 봇의 이전 메시지에 답장하면 대화를 이어간다.
        # message.reference 가 봇이 보낸 메시지를 가리키면, 그 내용을 직전
        # assistant 턴으로 삼아 build_chat_with_history_prompt 로 이어 대화한다.
        # (message.author.bot 가드는 상단에서 이미 처리되어 자기 답장 루프는 없다.)
        ref = message.reference
        replied_to_bot = False
        referenced_text = ""
        if ref is not None:
            resolved = ref.resolved
            if isinstance(resolved, discord.Message):
                referenced = resolved
            elif ref.message_id is not None and hasattr(message.channel, "fetch_message"):
                try:
                    referenced = await message.channel.fetch_message(ref.message_id)
                except (discord.HTTPException, discord.NotFound, discord.Forbidden):
                    referenced = None
            else:
                referenced = None
            if (
                referenced is not None
                and bot.user is not None
                and referenced.author.id == bot.user.id
            ):
                replied_to_bot = True
                referenced_text = referenced.content

        if replied_to_bot:
            started = perf_counter()
            guild_id = message.guild.id if message.guild else None
            channel_id = message.channel.id if hasattr(message.channel, "id") else None
            user_id = message.author.id
            reply_remaining = _check_cooldown(guild_id, user_id)
            if reply_remaining is not None:
                # #32: DM/컨텍스트 메뉴 경로처럼 남은 쿨다운을 안내한다(완전 무반응으로
                # 봇이 멈춘 것처럼 보이는 혼란 방지). 안내 전송 실패는 조용히 흡수한다.
                try:
                    await message.channel.send(
                        f"⏳ {reply_remaining:.0f}초 후에 다시 시도해주세요."
                    )
                except discord.DiscordException as exc:
                    logger.debug("답장 쿨다운 안내 전송 실패: %s", exc)
                return
            # 멘션 토큰은 질문 본문에서 제거한다(답장은 자동 멘션을 포함할 수 있음).
            cleaned = message.content.replace(f"<@{bot.user.id}>", "").replace(
                f"<@!{bot.user.id}>", ""
            )
            follow_question = normalize_content(cleaned)
            if not follow_question:
                return
            try:
                config = await store.get_guild_config(guild_id or 0)
                # #24/#90: 답장 이어가기도 LLM 을 호출하므로 역할 제한을 적용한다.
                # 권한이 없으면 조용히 종료(슬래시 명령처럼 안내 없이 무시)한다.
                if not _member_has_allowed_role(
                    message.author, config.allowed_role_id
                ):
                    return
                # 봇 직전 응답을 assistant 턴으로 넣어 맥락을 잇는다 (#8).
                history = [{"role": "assistant", "content": referenced_text}]
                prompt = build_chat_with_history_prompt(
                    follow_question, history, language=config.language
                )
                # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
                await _enforce_token_budget(store, config, guild_id)
                llm = _get_llm(config, settings)
                async with message.channel.typing():
                    answer = await llm.generate(prompt, model=config.model)
                p_tokens, c_tokens = _usage_tokens(llm)  # #17
                await _send_channel_chunks(message.channel, answer)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="reply_chat", status="ok", started_at=started,
                    prompt_tokens=p_tokens, completion_tokens=c_tokens,
                )
            except (UserFacingError, LLMError) as exc:
                await _send_channel_chunks(message.channel, f"⚠️ {exc}")
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="reply_chat", status="error", started_at=started, error=str(exc),
                )
            return

        if bot.user not in message.mentions:
            return

        started = perf_counter()
        guild_id = message.guild.id if message.guild else None
        channel_id = message.channel.id if hasattr(message.channel, "id") else None
        user_id = message.author.id
        command_name = "mention_ask"

        # #23: 멘션 응답은 가장 비싼 경로(트랜스크립트 수집 + 비전 분석)를 탈 수 있어
        # 다른 모든 LLM 진입점과 동일하게 쿨다운을 적용한다(비용 폭증/스팸 방지).
        cd_guild = guild_id if guild_id is not None else _DM_COOLDOWN_GUILD
        if _check_cooldown(cd_guild, user_id) is not None:
            return

        raw_query = message.content
        raw_query = raw_query.replace(f"<@{bot.user.id}>", "").replace(f"<@!{bot.user.id}>", "")
        question = normalize_content(raw_query)

        try:
            config = await store.get_guild_config(guild_id or 0)
            # #24/#90: @멘션 응답(이미지 분석/질문/요약)도 슬래시 명령과 동일한 역할
            # 제한을 적용한다. 권한이 없으면 조용히 종료한다.
            if not _member_has_allowed_role(message.author, config.allowed_role_id):
                return
            # #19: 멘션 응답(이미지 분석/질문/요약) 모두 LLM 을 호출하므로, 분기 전에
            # 한 번 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사한다.
            await _enforce_token_budget(store, config, guild_id)

            # 이미지 분석 (#12/#13) — 첨부에 이미지가 있고, 현재 제공자·모델이
            # 비전을 지원하면 첨부 bytes 를 실제 멀티모달 입력으로 전달한다.
            # 모델명 하드코딩(llava/bakllava) 대신 supports_vision 으로 판정한다.
            has_image_attachment = any(
                att.content_type and att.content_type.startswith("image/")
                for att in message.attachments
            )
            if has_image_attachment:
                if supports_vision(config.provider, config.model):
                    images = await _download_image_attachments(message.attachments)
                    if images:
                        llm = _get_llm(config, settings)
                        # 사용자가 함께 적은 질문이 있으면 그 지시를, 없으면 기본
                        # 이미지 설명 지시문을 사용한다(텍스트 지시문만, URL 미포함).
                        img_prompt = (
                            build_ask_prompt(
                                "(image attached)", question, language=config.language
                            )
                            if question
                            else build_image_analysis_prompt(language=config.language)
                        )
                        async with message.channel.typing():
                            img_answer = await llm.generate(
                                img_prompt, model=config.model, images=images
                            )
                        p_tokens, c_tokens = _usage_tokens(llm)
                        # #93: 긴 분석 결과도 프리뷰 + DM 버튼으로 통일한다.
                        await _send_channel_answer_with_overflow(
                            message.channel, f"**이미지 분석**\n{img_answer}"
                        )
                        await _record_usage(
                            store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                            command="image_analysis", status="ok", started_at=started,
                            prompt_tokens=p_tokens, completion_tokens=c_tokens,
                        )
                        return
                else:
                    # 비지원 모델: 이미지는 분석하지 않고 안내 후 텍스트 경로로 진행한다.
                    await _send_channel_chunks(
                        message.channel,
                        "ℹ️ 현재 모델은 이미지 분석을 지원하지 않아요. "
                        "텍스트 기준으로 답변할게요. (비전 지원 모델로 변경하려면 `/settings`)",
                    )

            # #6 스레드 맥락: 멘션이 스레드 안에서 발생하면 그 스레드의 메시지만
            # transcript 로 모은다. message.channel 은 스레드일 때 스레드 자신을
            # 가리키므로 thread.history 만 읽혀 부모 채널이 섞이지 않는다.
            context_channel = message.channel
            in_thread = isinstance(context_channel, discord.Thread)
            transcript = await _collect_transcript(
                context_channel,
                before=message.created_at,
                limit=config.summary_limit,
                max_context_chars=settings.max_context_chars,
            )
            if not transcript:
                raise UserFacingError("참고할 최근 메시지가 없어요.")

            llm = _get_llm(config, settings)

            if question:
                prompt = build_ask_prompt(transcript, question, language=config.language)
                heading = f"**질문:** {question}\n\n"
            else:
                command_name = "mention_summarize"
                prompt = build_summarize_prompt(transcript, language=config.language)
                heading = "🧵 **스레드 대화 요약**\n" if in_thread else "**최근 대화 요약**\n"

            async with message.channel.typing():
                answer = await llm.generate(prompt, model=config.model)
            p_tokens, c_tokens = _usage_tokens(llm)  # #17
            # #93: 긴 @멘션 응답도 프리뷰 + 'DM 으로 전체 받기' 버튼으로 통일한다.
            await _send_channel_answer_with_overflow(message.channel, heading + answer)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command=command_name, status="ok", started_at=started,
                prompt_tokens=p_tokens, completion_tokens=c_tokens,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_channel_chunks(message.channel, f"⚠️ {exc}")
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command=command_name, status="error", started_at=started, error=str(exc),
            )

    # ------------------------------------------------------------------
    # Developer notifications — on_disconnect and on_error
    # ------------------------------------------------------------------

    # #54: on_disconnect 오탐 제거.
    # discord.py 는 일상적인 재연결(게이트웨이 리밸런싱 등)에도 on_disconnect 를
    # 자주 발생시킨다. 즉시 DM 을 보내면 도배가 되므로, 끊김을 감지하면 유예 시간
    # 동안 기다렸다가 그동안 재연결(on_resumed/on_connect)되지 않은 경우에만 알린다.
    _DISCONNECT_GRACE_SECONDS = 30.0
    _disconnect_state: dict[str, asyncio.Task[Any] | None] = {"pending": None}

    async def _delayed_disconnect_alert() -> None:
        """유예 시간 대기 후에도 재연결이 없으면 개발자에게 알린다 (#54)."""
        # #36: notify_developer 의 await 경계 동안 재연결→새 끊김으로 다른 알림
        # 태스크가 pending 에 등록될 수 있다. 끝에서 무조건 None 으로 덮어쓰면 그
        # 새 태스크 참조를 잃어 _cancel_pending_disconnect_alert 가 취소하지 못한다.
        # 자신이 아직 등록된 pending 일 때만 비운다.
        me = asyncio.current_task()
        try:
            await asyncio.sleep(_DISCONNECT_GRACE_SECONDS)
        except asyncio.CancelledError:
            # 유예 시간 내 재연결 → 알림 취소(정상 동작).
            return
        # #134: 정상 종료(graceful shutdown) 중이면 알리지 않는다. 종료 시 bot.close()
        # 가 내는 끊김은 오탐이므로, is_closed()/is_ready() 가 종료 중에도 끊김으로
        # 보이는 점을 shutting_down 플래그로 보정한다.
        if getattr(bot, "_shutting_down", False):
            if _disconnect_state.get("pending") is me:
                _disconnect_state["pending"] = None
            return
        # 여전히 연결되지 않은 경우에만 알린다.
        if bot.is_closed() or not bot.is_ready():
            logger.warning(
                "Bot still disconnected after %.0fs grace; notifying developer.",
                _DISCONNECT_GRACE_SECONDS,
            )
            msg = format_disconnect_message(shard_id=None)
            await notify_developer(msg, bot)
        if _disconnect_state.get("pending") is me:
            _disconnect_state["pending"] = None

    def _cancel_pending_disconnect_alert() -> None:
        """대기 중인 끊김 알림을 취소한다(재연결 시 호출) (#54)."""
        pending = _disconnect_state.get("pending")
        if pending is not None and not pending.done():
            pending.cancel()
        _disconnect_state["pending"] = None

    @bot.event
    async def on_disconnect() -> None:
        # #134: graceful shutdown 중이면 close() 가 내는 끊김이므로 알림을 예약하지
        # 않는다(정상 종료를 가짜 '끊김' DM 으로 알리는 오탐 방지).
        if getattr(bot, "_shutting_down", False):
            return
        logger.warning("Bot disconnected from Discord (grace period before alert).")
        # 이미 대기 중인 알림이 있으면 중복 예약하지 않는다.
        pending = _disconnect_state.get("pending")
        if pending is not None and not pending.done():
            return
        _disconnect_state["pending"] = _track_task(
            _delayed_disconnect_alert(), name="disconnect-alert"
        )

    @bot.event
    async def on_resumed() -> None:
        # 세션 재개 → 진행 중인 끊김 알림이 있으면 취소해 DM 도배를 막는다 (#54).
        logger.info("Bot session resumed.")
        _cancel_pending_disconnect_alert()

    @bot.event
    async def on_connect() -> None:
        # 재연결(신규 세션) 시에도 대기 중인 끊김 알림을 취소한다 (#54).
        _cancel_pending_disconnect_alert()

    @bot.event
    async def on_error(event: str, *args: object, **kwargs: object) -> None:  # type: ignore[override]
        exc_info = sys.exc_info()
        exc = exc_info[1]
        if exc is not None:
            logger.exception("Unhandled error in event '%s'.", event, exc_info=exc_info)
            msg = format_error_message(event, exc)
            # #55: Sentry 가 활성화돼 있으면 예외를 함께 수집한다(미설정/미설치면 no-op).
            observability.capture_exception(exc)
        else:
            logger.error("Unhandled error in event '%s' (no exception info).", event)
            msg = f"[discord-assistant] Unhandled error in event `{event}` (no exception details)."
        await notify_developer(msg, bot)


    # ------------------------------------------------------------------
    # Phase 3 — Auto summary background task (#33)
    # ------------------------------------------------------------------

    @tasks.loop(minutes=1)
    async def auto_summary_task() -> None:
        """Check each guild for pending auto-summary and post if interval elapsed."""
        try:
            # Only consider guilds that actually enabled auto-summary (#25)
            configured = await store.get_guilds_with_auto_summary()
            # #35: 자동 요약이 꺼졌거나 봇이 나간 길드의 last_run 항목을 정리해
            # _auto_summary_last_run 이 무한히 커지는 것을 막는다(on_guild_remove 부재 보완).
            configured_gids = {gid for gid, _ in configured}
            stale = [gid for gid in _auto_summary_last_run if gid not in configured_gids]
            for gid in stale:
                del _auto_summary_last_run[gid]
            if not configured:
                return
            now = datetime.now(timezone.utc)
            for gid, interval in configured:
                last_run = _auto_summary_last_run.get(gid)
                if last_run is not None:
                    elapsed_minutes = (now - last_run).total_seconds() / 60
                    if elapsed_minutes < interval:
                        continue

                config = await store.get_guild_config(gid)
                guild = bot.get_guild(gid)
                if guild is None:
                    continue
                # Find the first text channel we can post to.
                for ch in guild.text_channels:
                    if not (
                        ch.permissions_for(guild.me).send_messages
                        and ch.permissions_for(guild.me).read_message_history
                    ):
                        continue
                    try:
                        transcript = await _collect_transcript(
                            ch,
                            before=now,
                            limit=config.summary_limit,
                            max_context_chars=settings.max_context_chars,
                        )
                        # #27: 빈 채널이면 break 대신 continue 로 다음 채널을 시도해,
                        # 권한상 첫 채널이 비어 있어도 활발한 다른 채널을 요약한다.
                        if not transcript:
                            continue
                        prompt = build_summarize_prompt(transcript, language=config.language)
                        # #19: 자동 요약도 토큰을 소비하므로 서버 일일 상한을 검사.
                        await _enforce_token_budget(store, config, gid)
                        llm = _get_llm(config, settings)
                        answer = await llm.generate(prompt, model=config.model)
                        await ch.send(f"**자동 요약** (매 {config.auto_summary_interval}분)\n{answer[:1800]}")
                    except UserFacingError as e:
                        # 예산 초과 등 의도된 skip — 이번 주기는 정상 소진한다.
                        logger.info("Auto summary skipped for guild %d: %s", gid, e)
                        _auto_summary_last_run[gid] = now
                        break
                    except Exception as e:
                        # #37: 일시적 LLM/네트워크 오류는 last_run 을 갱신하지 않아
                        # 다음 주기에 재시도되게 한다(실패로 한 주기를 소진하지 않음).
                        logger.warning("Auto summary failed for guild %d: %s", gid, e)
                        break
                    else:
                        # #37: 실제 전송에 성공한 뒤에만 last_run 을 갱신한다.
                        _auto_summary_last_run[gid] = now
                        break
        except Exception as e:
            logger.exception("auto_summary_task error: %s", e)

    # ------------------------------------------------------------------
    # #27 — Retention 정리 백그라운드 태스크 (하루 1회)
    # ------------------------------------------------------------------

    @tasks.loop(hours=24)
    async def retention_task() -> None:
        """오래된 usage_log/chat_history 를 주기적으로 정리한다 (#27).

        보존일은 상수(RETENTION_USAGE_DAYS / RETENTION_CHAT_DAYS)를 사용한다.
        purge 후 VACUUM 으로 디스크 사용을 회수한다(인메모리 DB 는 자동 skip).
        """
        try:
            deleted = await store.purge_old(
                usage_days=RETENTION_USAGE_DAYS, chat_days=RETENTION_CHAT_DAYS
            )
            if deleted.get("usage_log") or deleted.get("chat_history"):
                logger.info(
                    "Retention 정리 완료: usage_log %d건, chat_history %d건 삭제.",
                    deleted.get("usage_log", 0),
                    deleted.get("chat_history", 0),
                )
                await store.vacuum()
        except Exception as exc:
            logger.exception("retention_task error: %s", exc)

    # ------------------------------------------------------------------
    # Phase 3 — Reaction feedback tracker (#42)
    # ------------------------------------------------------------------

    @bot.event
    async def on_reaction_add(reaction: discord.Reaction, user: discord.User | discord.Member) -> None:
        if user.bot:
            return
        msg = reaction.message
        guild_id = msg.guild.id if msg.guild else None
        if guild_id is None:
            return
        command_name = _tracked_messages.get(guild_id, {}).get(msg.id)
        if command_name is None:
            return
        emoji_str = str(reaction.emoji)
        if emoji_str == THUMBS_UP:
            rating = 1
        elif emoji_str == THUMBS_DOWN:
            rating = -1
        else:
            return
        try:
            await store.save_feedback(
                guild_id=guild_id,
                message_id=msg.id,
                user_id=user.id,
                rating=rating,
                command=command_name,
            )
        except Exception as e:
            logger.warning("Failed to save feedback: %s", e)

    # ------------------------------------------------------------------
    # #9 — Reaction-triggered summarize/translate (📝 / 🌐)
    # ------------------------------------------------------------------

    @bot.event
    async def on_raw_reaction_add(payload: discord.RawReactionActionEvent) -> None:
        """📝/🌐 리액션을 메시지에 달면 그 메시지를 요약/번역해 답장한다 (#9).

        on_raw 를 쓰는 이유: 캐시되지 않은(오래된) 메시지에도 동작해야 하기
        때문이다. 봇 자신/쿨다운 가드를 두고, 👍/👎 피드백 경로와 공존한다.
        """
        emoji_str = str(payload.emoji)
        if emoji_str not in (REACTION_SUMMARIZE, REACTION_TRANSLATE):
            return
        if bot.user is not None and payload.user_id == bot.user.id:
            return  # 봇 자신의 리액션은 무시
        guild_id = payload.guild_id
        # 쿨다운: 리액션을 단 사용자 기준. DM(guild_id None)은 센티넬 버킷 사용.
        cd_guild = guild_id if guild_id is not None else _DM_COOLDOWN_GUILD
        if _check_cooldown(cd_guild, payload.user_id) is not None:
            return

        channel = bot.get_channel(payload.channel_id)
        if channel is None or not hasattr(channel, "fetch_message"):
            return
        try:
            target = await channel.fetch_message(payload.message_id)  # type: ignore[union-attr]
        except (discord.HTTPException, discord.NotFound, discord.Forbidden):
            return
        if not target.content.strip():
            return  # 처리할 텍스트가 없는 메시지(첨부만 등)는 건너뛴다.

        started = perf_counter()
        config = await store.get_guild_config(guild_id or 0)
        # #24/#90: 리액션 트리거(📝/🌐)도 LLM 을 호출하므로 역할 제한을 적용한다.
        # 리액션을 단 사용자의 member(payload.member 또는 guild.get_member)로 검사하고,
        # 권한이 없으면 조용히 종료한다.
        if config.allowed_role_id is not None:
            reactor = payload.member
            if reactor is None:
                guild_obj = bot.get_guild(guild_id) if guild_id is not None else None
                reactor = guild_obj.get_member(payload.user_id) if guild_obj else None
            if not _member_has_allowed_role(reactor, config.allowed_role_id):
                return
        # #28: 봇이 이 채널에 답장(send)할 수 없으면 비싼 LLM 호출 전에 조용히
        # 종료한다(읽기만 가능한 채널에서 토큰만 소비하는 것을 막는다). 권한 객체를
        # 알 수 없는 채널(DM 등)은 검사를 건너뛰고 기존 동작을 유지한다.
        guild_for_perms = bot.get_guild(guild_id) if guild_id is not None else None
        if guild_for_perms is not None and hasattr(channel, "permissions_for"):
            me = guild_for_perms.me
            if me is not None and not channel.permissions_for(me).send_messages:
                return
        command_name = "reaction_summarize" if emoji_str == REACTION_SUMMARIZE else "reaction_translate"
        try:
            # #19: LLM 호출 전 당일 누적 토큰이 서버 일일 상한을 넘었는지 검사.
            await _enforce_token_budget(store, config, guild_id)
            llm = _get_llm(config, settings)
            if emoji_str == REACTION_SUMMARIZE:
                language = config.language
                if language == "auto":
                    language = detect_language_from_transcript(target.content)
                prompt = build_summarize_prompt(target.content, language=language)
                heading = "📝 **메시지 요약**\n"
            else:
                target_lang = config.language if config.language != "auto" else "ko"
                prompt = build_translate_prompt(target.content, target_language=target_lang)
                heading = f"🌐 **번역 ({target_lang})**\n"
            answer = await llm.generate(prompt, model=config.model)
            # #28: 성공 경로의 reply/send 가 던지는 discord.Forbidden(HTTPException
            # 서브클래스)은 LLMError/UserFacingError 가 아니라 아래 except 에 걸리지
            # 않아 on_error → 개발자 DM 으로 새어나간다. 여기서 흡수한다.
            try:
                for i, chunk in enumerate(_split_discord_text(heading + answer)):
                    if i == 0:
                        await target.reply(chunk, mention_author=False)
                    else:
                        await _send_channel_chunks(channel, chunk)  # type: ignore[arg-type]
            except discord.HTTPException as exc:
                logger.info("리액션 응답 전송 실패(권한/일시 오류): %s", exc)
            await _record_usage(
                store, guild_id=guild_id, channel_id=payload.channel_id, user_id=payload.user_id,
                command=command_name, status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            try:
                await target.reply(f"⚠️ {exc}", mention_author=False)
            except discord.HTTPException:
                pass
            await _record_usage(
                store, guild_id=guild_id, channel_id=payload.channel_id, user_id=payload.user_id,
                command=command_name, status="error", started_at=started, error=str(exc),
            )

    # ------------------------------------------------------------------
    # Phase 3 — Guild join welcome message (#50)
    # ------------------------------------------------------------------

    class _OnboardingView(discord.ui.View):
        """온보딩 환영 메시지에 붙는 '지금 설정하기' 버튼 (#90).

        버튼을 누른 사용자가 관리자(또는 설정 권한 역할)면 실제 /settings 패널
        (SettingsView)을 ephemeral 로 열어 곧바로 제공자/모델/키를 설정하게 한다.
        권한이 없으면 안내만 하고 패널은 열지 않는다.
        """

        def __init__(self) -> None:
            super().__init__(timeout=900)

        @discord.ui.button(label="지금 설정하기", style=discord.ButtonStyle.success, emoji="⚙️")
        async def open_settings(
            self, btn_interaction: discord.Interaction, _button: discord.ui.Button
        ) -> None:
            if btn_interaction.guild is None:
                await btn_interaction.response.send_message(
                    "⚠️ 서버 안에서만 사용할 수 있어요.", ephemeral=True
                )
                return
            config = await store.get_guild_config(btn_interaction.guild.id)
            if not _has_config_permission(btn_interaction, config.admin_role_id):
                await btn_interaction.response.send_message(
                    "⚠️ 설정을 변경하려면 Manage Server 또는 관리자 권한이 필요해요.",
                    ephemeral=True,
                )
                return
            embed = settings_embed(config, btn_interaction.guild.name, _ui_language(config))
            view = SettingsView(
                ctx=view_ctx, guild_id=btn_interaction.guild.id, provider=config.provider
            )
            await btn_interaction.response.send_message(
                embed=embed, view=view, ephemeral=True
            )

    @bot.event
    async def on_guild_join(guild: discord.Guild) -> None:
        logger.info("Joined guild: %s (id=%s, members=%s)", guild.name, guild.id, guild.member_count)

        # #87: 환영 임베드를 길드 언어로 현지화한다. 설정 조회 실패 시 ko 로 폴백한다.
        lang = "ko"
        config: GuildConfig | None = None
        ollama_has_model = True
        try:
            config = await store.get_guild_config(guild.id)
            lang = _ui_language(config)
            if config.provider == LLMProvider.OLLAMA:
                # Ollama 는 설치된 모델 유무를 확인(외부 제공자는 키 유무만 본다).
                installed = await ollama_manager.list_models()
                ollama_has_model = bool(installed)
        except Exception as exc:  # pragma: no cover — 온보딩 판정 실패는 환영 메시지를 막지 않는다
            logger.warning("온보딩 설정 점검 실패(guild=%s): %s", guild.id, exc)

        help_embed = discord.Embed(
            title=t("welcome.title", lang),
            description=t("welcome.description", lang),
            color=discord.Color.from_str("#5865F2"),
        )
        help_embed.add_field(name="/summarize", value=t("welcome.field.summarize", lang), inline=True)
        help_embed.add_field(
            name="/ask question:...", value=t("welcome.field.ask", lang), inline=True
        )
        help_embed.add_field(name="/chat message:...", value=t("welcome.field.chat", lang), inline=True)
        help_embed.add_field(name="/settings", value=t("welcome.field.settings", lang), inline=False)
        help_embed.set_footer(text=t("welcome.footer", lang))

        # #90: 제공자/모델 설정이 덜 된 경우 '지금 설정하기' 체크리스트/버튼을 함께 보낸다.
        embeds = [help_embed]
        onboarding_view: discord.ui.View | None = None
        if config is not None and _needs_provider_setup(
            config, ollama_has_model=ollama_has_model
        ):
            embeds.append(_onboarding_embed(config, ollama_has_model=ollama_has_model))
            onboarding_view = _OnboardingView()

        sent = False
        for channel in guild.text_channels:
            if channel.permissions_for(guild.me).send_messages:
                try:
                    if onboarding_view is not None:
                        await channel.send(embeds=embeds, view=onboarding_view)
                    else:
                        await channel.send(embeds=embeds)
                    sent = True
                except Exception:
                    pass
                break
        if not sent:
            logger.warning("Could not send welcome message to guild %s — no accessible text channel", guild.id)

    return bot


async def _cancel_background_tasks() -> None:
    """추적 중인 fire-and-forget 태스크를 모두 취소하고 정리를 기다린다 (#49/#51)."""
    pending = [t for t in list(_background_tasks) if not t.done()]
    for task in pending:
        task.cancel()
    if pending:
        # 취소 예외를 모두 흡수하며 정리가 끝날 때까지 대기한다.
        await asyncio.gather(*pending, return_exceptions=True)


async def run_bot(settings: AppSettings, bot: commands.Bot | None = None) -> None:
    """SIGTERM/SIGINT 에 반응하는 graceful shutdown 기반 봇 실행 루틴 (#49).

    기존 ``bot.run`` 과 동등하게 봇을 기동하되, 종료 시그널을 받으면 추적 중인
    백그라운드 태스크를 취소하고 ``bot.close()`` 로 깔끔하게 정리한다.
    """
    if bot is None:
        bot = create_bot(settings)

    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()

    def _request_stop() -> None:
        logger.info("종료 시그널 수신 — graceful shutdown 시작.")
        stop_event.set()

    # 일부 플랫폼(Windows 등)은 loop.add_signal_handler 를 지원하지 않으므로 방어한다.
    # #38: 등록에 성공한 시그널만 모아 두고, 종료 시 finally 에서 해제해 같은 루프를
    # 재사용하는 경우 이전 _request_stop 클로저가 잔존하지 않게 한다.
    registered_signals: list[signal.Signals] = []
    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            loop.add_signal_handler(sig, _request_stop)
            registered_signals.append(sig)
        except (NotImplementedError, RuntimeError):  # pragma: no cover — 플랫폼 의존
            pass

    # 봇 시작과 종료 시그널 대기를 동시에 돌린다. 둘 중 하나가 끝나면 정리한다.
    start_task = asyncio.ensure_future(bot.start(settings.discord_bot_token))
    stop_task = asyncio.ensure_future(stop_event.wait())
    try:
        done, _ = await asyncio.wait(
            {start_task, stop_task}, return_when=asyncio.FIRST_COMPLETED
        )
        # start_task 가 예외로 끝났다면 그대로 표면화한다.
        if start_task in done:
            start_task.result()
    finally:
        stop_task.cancel()
        # #38: 등록한 시그널 핸들러를 해제한다(루프 재사용 시 클로저 잔존 방지).
        for sig in registered_signals:
            try:
                loop.remove_signal_handler(sig)
            except (NotImplementedError, RuntimeError, ValueError):  # pragma: no cover
                pass
        # 백그라운드 추적 태스크(리마인더/모니터 등) 취소 (#51).
        await _cancel_background_tasks()
        if not bot.is_closed():
            await bot.close()
        if not start_task.done():
            start_task.cancel()
        await asyncio.gather(start_task, return_exceptions=True)


def main() -> None:
    settings = AppSettings.from_env()
    # #55: SENTRY_DSN 이 설정돼 있으면 에러 트래킹을 초기화한다(미설정/미설치면 no-op).
    # 환경(production/staging 등) 태그는 ENVIRONMENT/APP_ENV 환경 변수에서 읽는다.
    observability.init_sentry(
        settings.sentry_dsn,
        environment=os.getenv("ENVIRONMENT") or os.getenv("APP_ENV"),
    )
    bot = create_bot(settings)
    # 기존 ``bot.run`` 동작과 동등하되, SIGTERM/SIGINT 시 graceful shutdown 한다 (#49).
    try:
        asyncio.run(run_bot(settings, bot))
    except KeyboardInterrupt:  # pragma: no cover — Ctrl-C 보조 처리
        logger.info("KeyboardInterrupt — 종료합니다.")
