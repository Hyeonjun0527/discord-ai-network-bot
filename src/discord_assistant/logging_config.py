"""구조화 로깅 설정 (#45).

운영 환경에서는 JSON 로그(시간/레벨/로거/메시지/예외)를, 개발 환경에서는
사람이 읽기 편한 텍스트 로그를 사용한다. 출력 포맷은 환경변수 ``LOG_FORMAT``
(``json`` 또는 ``text``, 기본값 ``text``)으로 선택한다.

``setup_logging()`` 은 멱등(idempotent)하다 — 여러 번 호출해도 핸들러가 중복으로
추가되지 않으므로 테스트나 재초기화 시에도 안전하다.
"""
from __future__ import annotations

import json
import logging
import os
import sys
from typing import TextIO

# 사람용 텍스트 포맷 — 기존 __main__.py의 basicConfig 포맷과 동일하게 유지해
# 백워드 호환성을 보장한다.
_TEXT_FORMAT = "%(asctime)s %(levelname)-8s %(name)s %(message)s"
_TEXT_DATEFMT = "%Y-%m-%dT%H:%M:%S"

# 우리 핸들러를 식별하기 위한 마커. setup_logging이 자신이 부착한 핸들러를
# 정확히 구분해 멱등성을 유지하는 데 쓰인다.
_OUR_HANDLER_ATTR = "_discord_assistant_logging_handler"


class JsonFormatter(logging.Formatter):
    """로그 레코드를 한 줄 JSON으로 직렬화하는 포맷터.

    각 줄은 ``time``, ``level``, ``logger``, ``message`` 키를 포함하며,
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
    포맷터/레벨만 갱신한다. 따라서 반복 호출해도 핸들러가 중복되지 않는다.
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
        return existing

    handler = logging.StreamHandler(stream)
    handler.setFormatter(formatter)
    handler.setLevel(level)
    # 우리 핸들러임을 표시해 다음 호출에서 식별할 수 있게 한다.
    setattr(handler, _OUR_HANDLER_ATTR, True)
    root.addHandler(handler)
    return handler
