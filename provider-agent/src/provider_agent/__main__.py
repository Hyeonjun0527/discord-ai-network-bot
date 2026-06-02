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
        # 자가 점검은 연결/처리 없이 Ollama 만 확인하므로 동의 화면 없이 진행한다.
        from .agent import self_test
        return self_test(cfg)
    # 첫 실행 동의(사용량 제한·서버/Ollama 주소·개인정보 안내). 미동의면 종료.
    from .consent import ensure_consent
    if not ensure_consent(cfg):
        return 2
    log.info("설정: %s", cfg.masked())
    from .agent import run_agent
    exit_code: int = run_agent(cfg)
    return exit_code


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
