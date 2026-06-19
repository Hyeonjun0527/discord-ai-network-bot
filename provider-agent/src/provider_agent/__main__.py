"""CLI 엔트리포인트. ``nexa`` 콘솔 스크립트가 이 main() 을 호출한다.

차수 1 에서는 설정 파싱까지. 실제 연결/실행은 차수 2~3 의 agent.run 으로 연결된다.
"""
from __future__ import annotations

import logging

from .config import config_from_args
from .logging_setup import setup_logging


def _warn_risky_config(cfg, log: logging.Logger) -> None:
    """안전 기본값을 끈 위험 옵션이 켜져 있으면 눈에 띄게 경고한다."""
    if cfg.allow_remote_ollama:
        log.warning("⚠️ 원격 Ollama 허용됨(--allow-remote-ollama): localhost 외 주소로 요청이 나갑니다.")
    if cfg.daily_limit == 0:
        log.warning("⚠️ 일일 한도 무제한(--allow-unlimited): 처리량 상한이 없습니다.")
    if not cfg.pause_on_battery:
        log.warning("⚠️ 배터리 중에도 처리(--run-on-battery): 방전 중에도 자동 일시중지하지 않습니다.")


def main(argv: list[str] | None = None) -> int:
    cfg, verbose = config_from_args(argv)
    setup_logging(verbose, cfg.log_file)
    from .bugsink import init_bugsink

    init_bugsink()
    log = logging.getLogger("provider_agent")
    log.info("Provider Agent %s", cfg.agent_version)
    _warn_risky_config(cfg, log)
    if cfg.gui:
        # 브라우저 설정 UI(토큰·풀 설정·자동시작을 클릭으로). 127.0.0.1 전용.
        from .webui import run_gui
        run_gui()
        return 0
    if cfg.install_service:
        # 설정은 이미 저장됨(config_from_args). 자동 시작 서비스만 등록하고 종료.
        from .service import install_service
        where = install_service()
        log.info("✅ 자동 시작 서비스 등록: %s — 이제 로그인 시 자동 연결됩니다.", where)
        return 0
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
