"""monitor.py — Developer alert notifications via Discord DM.

Usage
-----
Call `notify_developer(message, bot)` from bot events (on_disconnect, on_error, etc.)
to send a DM to the Discord user whose ID is set in DEVELOPER_USER_ID.

레이트리밋·중복 억제(#53)
-----------------------
폭주하는 에러로 인해 개발자 DM이 도배되는 것을 막기 위해 `notify_developer`는
모듈 전역 `AlertRateLimiter`를 통과한다. 동일/유사 에러는 메시지에서 추출한
"시그니처"로 묶이며, 다음 정책을 적용한다.

* dedup 윈도우: 같은 시그니처가 윈도우(기본 60초) 안에 다시 오면 억제한다.
* 분당/시간당 상한: 전체 알림 발송 건수에 분당·시간당 상한을 둔다.
* 억제된 건수는 카운팅되어, 다음으로 통과하는 알림 뒤에 요약 문구로 덧붙고
  로깅된다. 따라서 폭주 상황에서도 개발자는 "무슨 일이 몇 건 억제됐는지"를
  알 수 있다.

테스트 결정성을 위해 `time_fn`(현재 시각[초]을 돌려주는 호출 가능 객체)을 주입할
수 있다. 기본값은 `time.monotonic`이다.

Environment variable
--------------------
DEVELOPER_USER_ID  — numeric Discord user ID of the person who should receive alerts.
                     If unset or invalid, the notification is skipped and a warning is logged.
"""
from __future__ import annotations

import logging
import os
import re
import threading
import time
import traceback
from collections import deque
from typing import TYPE_CHECKING, Callable

if TYPE_CHECKING:
    from discord.ext import commands

logger = logging.getLogger(__name__)

# 레이트리밋 기본 설정값. 운영 중 폭주를 막되 정상적인 산발 알림은 통과시킨다.
DEFAULT_DEDUP_WINDOW_SECONDS = 60.0
DEFAULT_MAX_PER_MINUTE = 5
DEFAULT_MAX_PER_HOUR = 30


def _developer_user_id() -> int | None:
    """Read DEVELOPER_USER_ID from the environment. Returns None if unset or invalid."""
    raw = os.getenv("DEVELOPER_USER_ID", "").strip()
    if not raw:
        return None
    try:
        return int(raw)
    except ValueError:
        logger.warning(
            "DEVELOPER_USER_ID='%s' is not a valid integer — notifications disabled.", raw
        )
        return None


# 시그니처 정규화에 사용할 패턴들. 숫자/메모리 주소/공백 등 매 발생마다 달라지는
# 부분을 지워, "본질적으로 같은" 에러가 하나의 시그니처로 묶이도록 한다.
_HEX_ADDR_RE = re.compile(r"0x[0-9a-fA-F]+")
_NUMBER_RE = re.compile(r"\d+")
_WHITESPACE_RE = re.compile(r"\s+")


def compute_signature(message: str) -> str:
    """메시지에서 레이트리밋용 시그니처를 추출한다.

    가변적인 부분(메모리 주소, 숫자, 잉여 공백)을 정규화해, 동일하거나 유사한
    에러 메시지가 같은 시그니처로 묶이도록 한다. 트레이스백처럼 여러 줄인 경우
    의미 있는 마지막 줄(예: 예외 타입/메시지)을 우선 사용한다.
    """
    text = message.strip()

    # 코드 블록(```) 안의 트레이스백이 있으면 그 안에서 마지막 비어있지 않은
    # 줄을 골라 시그니처의 핵심으로 삼는다 — 보통 "ExceptionType: detail" 줄이다.
    lines = [ln.strip() for ln in text.splitlines() if ln.strip() and ln.strip() != "```"]
    if lines:
        # 마지막 의미 있는 줄을 핵심으로, 첫 줄(헤더)도 함께 묶어 맥락을 유지한다.
        core = lines[-1]
        header = lines[0]
        if header != core:
            text = f"{header} | {core}"
        else:
            text = core

    text = _HEX_ADDR_RE.sub("0xADDR", text)
    text = _NUMBER_RE.sub("N", text)
    text = _WHITESPACE_RE.sub(" ", text)
    return text.strip().lower()


class AlertRateLimiter:
    """알림 레이트리밋·중복 억제기.

    스레드 안전하며, `time_fn` 주입으로 테스트 결정성을 확보한다. 단조 증가하는
    초 단위 시각을 돌려주는 호출 가능 객체를 기대한다(기본 `time.monotonic`).
    """

    def __init__(
        self,
        *,
        dedup_window_seconds: float = DEFAULT_DEDUP_WINDOW_SECONDS,
        max_per_minute: int = DEFAULT_MAX_PER_MINUTE,
        max_per_hour: int = DEFAULT_MAX_PER_HOUR,
        time_fn: Callable[[], float] = time.monotonic,
    ) -> None:
        self._dedup_window = float(dedup_window_seconds)
        self._max_per_minute = int(max_per_minute)
        self._max_per_hour = int(max_per_hour)
        self._time_fn = time_fn
        self._lock = threading.Lock()

        # 시그니처 -> 마지막으로 "통과"시킨 시각. dedup 윈도우 판정에 사용.
        self._last_sent: dict[str, float] = {}
        # 전역 발송 타임스탬프(분/시간 상한 판정용 슬라이딩 윈도우).
        self._sent_times: deque[float] = deque()
        # 억제된 누적 건수. 다음 통과 알림에 요약으로 실어 보낸 뒤 0으로 리셋.
        self._suppressed_count = 0
        # 억제된 시그니처별 건수(요약 문구 구성용).
        self._suppressed_signatures: dict[str, int] = {}

    def _prune(self, now: float) -> None:
        """1시간(3600초)보다 오래된 발송 타임스탬프를 제거한다."""
        cutoff = now - 3600.0
        while self._sent_times and self._sent_times[0] < cutoff:
            self._sent_times.popleft()

    def _count_within(self, now: float, window_seconds: float) -> int:
        """최근 `window_seconds` 안의 발송 건수를 센다."""
        cutoff = now - window_seconds
        return sum(1 for t in self._sent_times if t >= cutoff)

    def check(self, message: str) -> tuple[bool, str]:
        """알림을 보낼지 결정한다.

        Returns
        -------
        (allowed, outgoing_message)
            allowed가 True이면 `outgoing_message`(억제 요약이 덧붙을 수 있음)를
            실제로 전송한다. False이면 호출 측은 전송을 건너뛴다.
        """
        signature = compute_signature(message)
        with self._lock:
            now = self._time_fn()
            self._prune(now)

            # 1) dedup 윈도우: 같은 시그니처가 윈도우 안에 다시 오면 억제.
            last = self._last_sent.get(signature)
            if last is not None and (now - last) < self._dedup_window:
                self._record_suppressed(signature)
                return False, message

            # 2) 분당/시간당 상한.
            if self._count_within(now, 60.0) >= self._max_per_minute:
                self._record_suppressed(signature)
                return False, message
            if self._count_within(now, 3600.0) >= self._max_per_hour:
                self._record_suppressed(signature)
                return False, message

            # 통과: 상태 갱신.
            self._last_sent[signature] = now
            self._sent_times.append(now)

            outgoing = message
            summary = self._drain_suppressed_summary()
            if summary:
                outgoing = f"{message}\n\n{summary}"
            return True, outgoing

    def _record_suppressed(self, signature: str) -> None:
        """억제된 건을 카운팅하고 로깅한다(락 보유 상태에서 호출)."""
        self._suppressed_count += 1
        self._suppressed_signatures[signature] = (
            self._suppressed_signatures.get(signature, 0) + 1
        )
        logger.info(
            "Developer notification suppressed (rate limit/dedup); signature=%r, "
            "total suppressed pending=%d.",
            signature,
            self._suppressed_count,
        )

    def _drain_suppressed_summary(self) -> str:
        """억제된 건수 요약 문구를 만들고 카운터를 리셋한다(락 보유 상태에서 호출)."""
        if self._suppressed_count <= 0:
            return ""
        total = self._suppressed_count
        # 가장 많이 억제된 시그니처 상위 3개를 함께 보여준다.
        top = sorted(
            self._suppressed_signatures.items(), key=lambda kv: kv[1], reverse=True
        )[:3]
        detail = ", ".join(f"{cnt}x {sig[:60]}" for sig, cnt in top)
        self._suppressed_count = 0
        self._suppressed_signatures = {}
        summary = (
            f"[discord-assistant] (이전 알림 이후 {total}건의 알림이 레이트리밋/중복으로 "
            f"억제되었습니다.)"
        )
        if detail:
            summary += f"\n억제된 주요 시그니처: {detail}"
        return summary

    def pending_suppressed(self) -> int:
        """현재 대기 중인(아직 요약으로 내보내지 않은) 억제 건수."""
        with self._lock:
            return self._suppressed_count


# 모듈 전역 레이트리밋 인스턴스. notify_developer가 기본으로 사용한다.
_default_rate_limiter = AlertRateLimiter()


async def notify_developer(
    message: str,
    bot: "commands.Bot",
    *,
    rate_limiter: AlertRateLimiter | None = None,
) -> None:
    """Send a DM alert to the developer identified by DEVELOPER_USER_ID.

    Parameters
    ----------
    message:
        Plain-text message to send (will be truncated to 1900 chars to stay within
        Discord's message length limit).
    bot:
        The running discord.py Bot instance, used to fetch the user and send the DM.
    rate_limiter:
        선택적 `AlertRateLimiter`. 미지정 시 모듈 전역 인스턴스를 사용한다. 동일/유사
        에러가 폭주할 때 DM 도배를 막고, 억제된 건수를 다음 알림에 요약으로 싣는다.
    """
    user_id = _developer_user_id()
    if user_id is None:
        logger.debug("notify_developer: DEVELOPER_USER_ID not configured, skipping DM.")
        return

    limiter = rate_limiter if rate_limiter is not None else _default_rate_limiter
    allowed, outgoing = limiter.check(message)
    if not allowed:
        # 억제된 건은 check() 내부에서 이미 로깅됨.
        return

    # Truncate to safe Discord length
    MAX_CHARS = 1900
    if len(outgoing) > MAX_CHARS:
        outgoing = outgoing[: MAX_CHARS - 3] + "..."

    try:
        user = bot.get_user(user_id) or await bot.fetch_user(user_id)
        await user.send(outgoing)
        logger.info("Developer notification sent to user %s.", user_id)
    except Exception as exc:  # pragma: no cover — network errors at runtime
        logger.warning(
            "Failed to send developer notification to user %s: %s", user_id, exc
        )


def format_disconnect_message(shard_id: int | None = None) -> str:
    """Return a formatted message for a bot disconnect event."""
    shard_info = f" (shard {shard_id})" if shard_id is not None else ""
    return f"[discord-assistant] Bot disconnected from Discord{shard_info}."


# 트레이스백/에러 메시지에서 마스킹할 시크릿·PII 패턴. 예외 메시지에는 사용자
# 입력·이메일·토큰 단편이 섞여 들어올 수 있으므로, DM(서드파티 채널, 영구 저장)으로
# 내보내기 전에 알려진 민감 패턴을 가린다.
_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
# Discord 봇 토큰: 보통 base64 세 조각이 점으로 이어진 형태.
_DISCORD_TOKEN_RE = re.compile(
    r"\b[A-Za-z0-9_\-]{20,}\.[A-Za-z0-9_\-]{4,}\.[A-Za-z0-9_\-]{20,}\b"
)
# OpenAI/Anthropic 스타일 API 키 (sk-..., sk-ant-...) 및 일반 Bearer 토큰.
_API_KEY_RE = re.compile(r"\bsk-(?:ant-)?[A-Za-z0-9_\-]{16,}\b")
_BEARER_RE = re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._\-]{16,}")


def scrub_sensitive(text: str) -> str:
    """알려진 시크릿/PII 패턴(이메일·토큰·API 키)을 마스킹한다.

    개발자 DM 으로 트레이스백을 내보내기 전에 호출해, 예외 메시지에 우발적으로
    섞여 들어온 민감정보가 평문으로 남지 않도록 한다.
    """
    # 토큰류 먼저 마스킹(이메일보다 구체적 패턴). Bearer 는 접두사를 보존한다.
    text = _DISCORD_TOKEN_RE.sub("[REDACTED-TOKEN]", text)
    text = _API_KEY_RE.sub("[REDACTED-KEY]", text)
    text = _BEARER_RE.sub(r"\1[REDACTED]", text)
    text = _EMAIL_RE.sub("[REDACTED-EMAIL]", text)
    return text


def format_error_message(event: str, exc: BaseException) -> str:
    """Return a formatted message for an unhandled error event.

    트레이스백에 시크릿/PII 가 섞여 있을 수 있으므로, 개발자 DM 으로 보내기 전에
    알려진 민감 패턴을 마스킹한다(#130).
    """
    tb = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    tb = scrub_sensitive(tb)
    # Keep traceback concise for the DM
    if len(tb) > 1200:
        tb = tb[-1200:]
    return (
        f"[discord-assistant] Unhandled error in event `{event}`:\n"
        f"```\n{tb}\n```"
    )
