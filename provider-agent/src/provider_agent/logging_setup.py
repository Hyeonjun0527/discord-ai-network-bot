"""에이전트 로깅 설정 (차수 1).

토큰/프롬프트 등 민감 내용을 로그에 남기지 않는다. 코드 전반에서 프롬프트 원문을 절대
로깅하지 않는 것이 1차 방어선이고, 여기 ``RedactingFilter`` 는 실수로 토큰/비밀번호/API 키
형태의 문자열이 메시지에 섞여도 ``***`` 로 가리는 2차 방어선이다.
"""
from __future__ import annotations

import logging
import re

# key=value / key: value 형태의 시크릿을 가린다(token, password, api key, secret, authorization).
_SECRET_RE = re.compile(
    r"(?i)\b(token|password|passwd|secret|api[\s_-]?key|authorization|bearer)\b\s*[:=]?\s*\S+"
)


class RedactingFilter(logging.Filter):
    """로그 레코드의 시크릿 패턴을 ``***`` 로 마스킹하는 안전망."""

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            message = record.getMessage()
        except Exception:  # noqa: BLE001 - 포맷 실패 시 원본 유지
            return True
        redacted = _SECRET_RE.sub(lambda m: f"{m.group(1)}=***", message)
        if redacted != message:
            record.msg = redacted
            record.args = ()
        return True


def setup_logging(verbose: bool = False, log_file: str = "") -> None:
    redactor = RedactingFilter()
    stream = logging.StreamHandler()
    stream.addFilter(redactor)
    handlers: list[logging.Handler] = [stream]
    if log_file:
        from logging.handlers import RotatingFileHandler

        file_handler = RotatingFileHandler(
            log_file, maxBytes=5_000_000, backupCount=3, encoding="utf-8"
        )
        file_handler.addFilter(redactor)
        handlers.append(file_handler)
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s | %(message)s",
        datefmt="%H:%M:%S",
        handlers=handlers,
    )
