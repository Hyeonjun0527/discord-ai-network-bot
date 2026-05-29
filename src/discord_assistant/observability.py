"""Sentry 기반 에러 트래킹 — 선택적 관측성 계층 (#55).

sentry_sdk 가 설치되고 SENTRY_DSN 이 설정된 경우에만 활성화된다. 둘 중 하나라도
없으면 ``init_sentry`` 와 ``capture_exception`` 은 안전한 no-op 으로 동작한다
(import 가드). 운영 환경에서 on_error/notify 경로의 예외를 자동 수집하는 데 쓴다.
"""
from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger(__name__)

# --- 선택 의존성 import 가드 ---
try:  # pragma: no cover - 설치 여부에 따라 분기(미설치 환경이 기본)
    import sentry_sdk

    _HAVE_SENTRY = True
except ImportError:  # pragma: no cover - sentry_sdk 미설치
    sentry_sdk = None  # type: ignore[assignment]
    _HAVE_SENTRY = False

# init_sentry 가 실제로 초기화에 성공했는지 추적한다(중복 init 방지 + capture 게이트).
_initialized = False

# before_send 에서 마스킹할 민감 키 토큰(소문자 부분일치). 사용자 콘텐츠/시크릿이
# 예외 프레임 로컬 변수·extra·request 등을 통해 외부 Sentry 로 새어 나가는 것을 막는다.
_SENSITIVE_KEY_TOKENS = (
    "message",
    "content",
    "authorization",
    "token",
    "api_key",
    "apikey",
    "secret",
    "password",
    "prompt",
)
_SCRUBBED_PLACEHOLDER = "[scrubbed]"


def _is_sensitive_key(key: object) -> bool:
    """키 이름에 민감 토큰이 부분일치로 포함되면 True."""
    if not isinstance(key, str):
        return False
    lowered = key.lower()
    return any(token in lowered for token in _SENSITIVE_KEY_TOKENS)


def _scrub_value(value: Any) -> Any:
    """dict/list 를 재귀적으로 순회하며 민감 키의 값을 마스킹한다."""
    if isinstance(value, dict):
        scrubbed: dict[Any, Any] = {}
        for k, v in value.items():
            if _is_sensitive_key(k):
                scrubbed[k] = _SCRUBBED_PLACEHOLDER
            else:
                scrubbed[k] = _scrub_value(v)
        return scrubbed
    if isinstance(value, list):
        return [_scrub_value(item) for item in value]
    if isinstance(value, tuple):
        return tuple(_scrub_value(item) for item in value)
    return value


def _before_send(event: Any, hint: Any = None) -> Any:  # noqa: ARG001 - hint 는 Sentry 시그니처 요구
    """Sentry 전송 직전 이벤트의 민감 키를 마스킹하는 콜백 (#55 PII 스크럽).

    예외 프레임 로컬 변수(``exception.values[].stacktrace.frames[].vars``),
    request 데이터, extra/contexts 등에 섞여 들어올 수 있는 사용자 메시지·토큰·
    API 키를 ``[scrubbed]`` 로 치환한다. 어떤 예외도 새지 않게 방어한다(스크럽
    실패가 전송 자체를 막지 않도록 — 단, 실패 시에는 원본 대신 None 을 반환해
    민감정보 유출보다 이벤트 드롭을 택한다).
    """
    try:
        if isinstance(event, dict):
            return _scrub_value(event)
        return event
    except Exception:  # pragma: no cover - 방어적: 스크럽 실패 시 유출 방지로 드롭
        return None


def init_sentry(dsn: str | None, *, environment: str | None = None) -> bool:
    """SENTRY_DSN 이 주어지고 sentry_sdk 가 설치된 경우에만 Sentry 를 초기화한다 (#55).

    Returns
    -------
    bool
        실제로 초기화됐으면 True, (DSN 미설정/미설치 등으로) 비활성이면 False.

    멱등하게 동작한다: 이미 초기화됐으면 다시 init 하지 않고 True 를 반환한다.
    어떤 예외도 호출부로 새지 않게 방어해, 관측성 초기화 실패가 봇 기동을 막지
    않도록 한다.
    """
    global _initialized
    dsn = (dsn or "").strip()
    if not dsn:
        logger.debug("init_sentry: SENTRY_DSN 미설정 — Sentry 비활성.")
        return False
    if not _HAVE_SENTRY:
        logger.debug("init_sentry: sentry_sdk 미설치 — Sentry 비활성.")
        return False
    if _initialized:
        return True
    try:
        kwargs: dict[str, Any] = {
            "dsn": dsn,
            # 사용자/요청 PII 자동 첨부 비활성화 (#55).
            "send_default_pii": False,
            # 전송 직전 민감 키(message/content/token/api_key 등) 마스킹.
            "before_send": _before_send,
        }
        if environment:
            kwargs["environment"] = environment
        sentry_sdk.init(**kwargs)
        _initialized = True
        logger.info("Sentry 에러 트래킹을 활성화했습니다.")
        return True
    except Exception as exc:  # pragma: no cover - 방어적
        logger.warning("Sentry 초기화 실패(무시): %s", exc)
        return False


def is_enabled() -> bool:
    """Sentry 가 활성화(설치 + init 성공)돼 있으면 True."""
    return _HAVE_SENTRY and _initialized


def capture_exception(exc: BaseException | None = None) -> None:
    """예외를 Sentry 로 전송한다 (#55).

    Sentry 가 비활성(미설치/미초기화)이면 no-op. ``exc`` 가 None 이면 현재 처리
    중인 예외(sys.exc_info)를 캡처한다. 어떤 예외도 호출부로 새지 않게 방어한다.
    """
    if not is_enabled():
        return
    try:
        if exc is not None:
            sentry_sdk.capture_exception(exc)
        else:
            sentry_sdk.capture_exception()
    except Exception as inner:  # pragma: no cover - 방어적
        logger.debug("Sentry capture 실패(무시): %s", inner)


def reset_for_tests() -> None:
    """초기화 상태 플래그를 리셋한다. 테스트 격리 전용."""
    global _initialized
    _initialized = False
