"""에이전트 로깅 설정 (차수 1). 토큰/프롬프트 내용을 로그에 남기지 않는다."""
from __future__ import annotations

import logging


def setup_logging(verbose: bool = False, log_file: str = "") -> None:
    handlers: list[logging.Handler] = [logging.StreamHandler()]
    if log_file:
        from logging.handlers import RotatingFileHandler

        handlers.append(
            RotatingFileHandler(log_file, maxBytes=5_000_000, backupCount=3, encoding="utf-8")
        )
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s | %(message)s",
        datefmt="%H:%M:%S",
        handlers=handlers,
    )
