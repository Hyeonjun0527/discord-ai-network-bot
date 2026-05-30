"""CLI 엔트리포인트. ``discord-ai-provider-agent`` 콘솔 스크립트가 이 main() 을 호출한다.

차수 1 에서는 설정 파싱까지. 실제 연결/실행은 차수 2~3 의 agent.run 으로 연결된다.
"""
from __future__ import annotations

import logging

from .config import config_from_args
from .logging_setup import setup_logging


def main(argv: list[str] | None = None) -> int:
    cfg, verbose = config_from_args(argv)
    setup_logging(verbose, cfg.log_file)
    log = logging.getLogger("provider_agent")
    log.info("Provider Agent %s", cfg.agent_version)
    if cfg.self_test:
        from .agent import self_test
        return self_test(cfg)
    log.info("설정: %s", cfg.masked())
    from .agent import run_agent
    exit_code: int = run_agent(cfg)
    return exit_code


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
