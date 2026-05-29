"""구조화 로깅 설정 (#45, #46).

운영 환경에서는 JSON 로그(시간/레벨/로거/메시지/cid/예외)를, 개발 환경에서는
사람이 읽기 편한 텍스트 로그를 사용한다. 출력 포맷은 환경변수 ``LOG_FORMAT``
(``json`` 또는 ``text``, 기본값 ``text``)으로 선택한다.

``setup_logging()`` 은 멱등(idempotent)하다 — 여러 번 호출해도 핸들러가 중복으로
추가되지 않으므로 테스트나 재초기화 시에도 안전하다.

correlation id(#46): 명령 핸들러마다 ``set_correlation_id`` 로 컨텍스트에 cid 를
바인딩하면, ``setup_logging`` 이 부착한 ``CorrelationIdFilter`` 가 모든 로그
레코드에 ``cid`` 속성을 주입한다. 텍스트 포맷은 ``(cid=...)`` 로, JSON 포맷은
``cid`` 키로 직렬화한다. contextvars 는 asyncio 태스크 경계를 넘어도 값을 안전하게
전파하므로 명령 단위로 격리된다. (이 모듈에 두는 이유: 로깅 저수준 모듈이라
``bot`` 과의 순환참조를 피한다.)
"""
from __future__ import annotations

import contextvars
import json
import logging
import os
import sys
from typing import TextIO

# 사람용 텍스트 포맷 — 기존 __main__.py의 basicConfig 포맷과 동일하게 유지하되
# correlation id(cid)를 끝에 덧붙인다.
_TEXT_FORMAT = "%(asctime)s %(levelname)-8s %(name)s %(message)s (cid=%(cid)s)"
_TEXT_DATEFMT = "%Y-%m-%dT%H:%M:%S"

# 우리 핸들러를 식별하기 위한 마커. setup_logging이 자신이 부착한 핸들러를
# 정확히 구분해 멱등성을 유지하는 데 쓰인다.
_OUR_HANDLER_ATTR = "_discord_assistant_logging_handler"

# --- #46 correlation id ---
# 명령마다 interaction.id 등을 바인딩해 로그에 cid 를 끼워 넣는다.
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


class JsonFormatter(logging.Formatter):
    """로그 레코드를 한 줄 JSON으로 직렬화하는 포맷터.

    각 줄은 ``time``, ``level``, ``logger``, ``message``, ``cid`` 키를 포함하며,
    예외 정보가 있으면 ``exception`` 키에 traceback 문자열을 담는다.
    한 줄 = 하나의 JSON 객체이므로 로그 수집기에서 파싱하기 쉽다.
    """

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            # ISO 8601 형태의 시각. asctime을 직접 만들지 않고 formatTime을 사용해
            # 표준 동작을 따른다.
            "time": self.formatTime(record, _TEXT_DATEFMT),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            # CorrelationIdFilter 가 cid 를 주입하지만, 필터 없이 직접 포맷할
            # 수도 있으므로 getattr 기본값('-')으로 KeyError 를 막는다.
            "cid": getattr(record, "cid", "-"),
        }
        if record.exc_info:
            # 예외가 첨부된 경우 traceback 문자열을 함께 기록한다.
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def _make_formatter(log_format: str) -> logging.Formatter:
    """``log_format`` 값에 맞는 포맷터를 반환한다 (json 외에는 텍스트)."""
    if log_format == "json":
        return JsonFormatter()
    return logging.Formatter(fmt=_TEXT_FORMAT, datefmt=_TEXT_DATEFMT)


def _ensure_correlation_filter(handler: logging.Handler) -> None:
    """핸들러에 CorrelationIdFilter 가 한 개만 부착되도록 보장한다(멱등)."""
    if not any(isinstance(f, CorrelationIdFilter) for f in handler.filters):
        handler.addFilter(CorrelationIdFilter())


def setup_logging(
    *,
    level: int = logging.INFO,
    log_format: str | None = None,
    stream: TextIO | None = None,
) -> logging.Handler:
    """루트 로거에 구조화 로깅을 설정한다 (멱등).

    Args:
        level: 루트 로거 레벨. 기본값 ``logging.INFO``.
        log_format: ``"json"`` 또는 ``"text"``. ``None`` 이면 환경변수
            ``LOG_FORMAT`` 을 읽고, 그것도 없으면 ``"text"`` 를 쓴다.
        stream: 로그를 보낼 스트림. 기본값은 ``sys.stderr``.

    Returns:
        부착(또는 갱신)된 핸들러. 호출자가 추가 설정에 쓸 수 있다.

    멱등성: 이 함수가 이전에 부착한 핸들러가 이미 있으면 새로 추가하지 않고
    포맷터/레벨/필터만 갱신한다. 따라서 반복 호출해도 핸들러가 중복되지 않는다.
    """
    if log_format is None:
        log_format = os.getenv("LOG_FORMAT", "text").strip().lower() or "text"
    if stream is None:
        stream = sys.stderr

    formatter = _make_formatter(log_format)
    root = logging.getLogger()
    root.setLevel(level)

    # 이미 우리가 부착한 핸들러가 있으면 재사용해 중복 핸들러를 방지한다.
    existing = next(
        (h for h in root.handlers if getattr(h, _OUR_HANDLER_ATTR, False)),
        None,
    )
    if existing is not None:
        existing.setFormatter(formatter)
        existing.setLevel(level)
        # 필터가 빠진 핸들러(이전 버전)도 안전하게 보강한다.
        _ensure_correlation_filter(existing)
        return existing

    handler = logging.StreamHandler(stream)
    handler.setFormatter(formatter)
    handler.setLevel(level)
    # cid 를 모든 레코드에 주입해 텍스트/JSON 포맷 모두에서 correlation id 가
    # 실제로 출력되도록 한다 (#46).
    _ensure_correlation_filter(handler)
    # 우리 핸들러임을 표시해 다음 호출에서 식별할 수 있게 한다.
    setattr(handler, _OUR_HANDLER_ATTR, True)
    root.addHandler(handler)
    return handler
