"""에이전트 설정 & CLI (차수 1).

우선순위: CLI 인자 > 환경변수 > 기본값. 토큰은 ``--token`` 또는 ``AGENT_TOKEN``.
"""
from __future__ import annotations

import argparse
import os
import platform as _platform
from dataclasses import dataclass, field

from .constants import AGENT_VERSION, DEFAULT_DAILY_LIMIT


@dataclass(frozen=True, slots=True)
class AgentConfig:
    token: str
    relay_url: str = "ws://localhost:8080/agent"
    ollama_url: str = "http://localhost:11434"
    models: tuple[str, ...] = ()
    max_concurrency: int = 1
    daily_limit: int = DEFAULT_DAILY_LIMIT  # 0 = 무제한(--allow-unlimited 필요)
    request_timeout: float = 120.0
    heartbeat_seconds: float = 30.0
    reconnect_max_seconds: float = 30.0
    log_file: str = ""  # 비면 콘솔만
    self_test: bool = False  # 연결 없이 Ollama 자가 점검
    telemetry: bool = False  # 익명 텔레메트리 opt-in(기본 꺼짐, 차수 10 #130)
    allow_remote_ollama: bool = False  # 기본 localhost 전용; True 면 원격 Ollama 허용(위험)
    pause_on_battery: bool = True  # 배터리(방전) 중에는 자동 pause(자원 보호)
    pause_on_high_load: bool = True  # CPU 고부하 시 자동 pause(자원 보호)
    enable_image: bool = False  # 로컬 SD 이미지 생성 capability 광고(opt-in, SD Phase 1)
    sd_url: str = "http://127.0.0.1:7860"  # 로컬 Stable Diffusion(A1111) 주소
    allow_remote_sd: bool = False  # 기본 localhost 전용; True 면 원격 SD 허용(위험)
    assume_yes: bool = False  # 첫 실행 동의 자동 승인(--yes, 저장하지 않음)
    service: bool = False  # 헤드리스 자동시작 모드(--service): launchd·업데이터 재실행이 창 없이 무인 구동
    install_service: bool = False  # 자동 시작 서비스 등록 후 종료(--install-service, 저장 안 함)
    gui: bool = False  # 브라우저 설정 UI(--gui, 토큰 없이 가능, 저장 안 함)
    tray: bool = False  # 실행 중 시스템 트레이 아이콘(라이브 상태·중지·설정 열기, 데스크톱 전용)
    auto_update: bool = True  # 시작 시 새 버전이 있으면 자동으로 받아 교체·재실행(기본 ON, 고급에서 토글)
    agent_version: str = AGENT_VERSION
    platform: str = field(default_factory=lambda: _platform.platform())

    def masked(self) -> str:
        """토큰을 가린 요약(로그용)."""
        limit = "무제한" if self.daily_limit == 0 else str(self.daily_limit)
        return (
            f"AgentConfig(relay_url={self.relay_url!r}, ollama_url={self.ollama_url!r}, "
            f"models={self.models}, max_concurrency={self.max_concurrency}, "
            f"daily_limit={limit}, allow_remote_ollama={self.allow_remote_ollama}, "
            f"token={'***' if self.token else '(없음)'})"
        )


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="nexa",
        description="내 PC의 로컬 Ollama 를 NEXA 커뮤니티 AI 네트워크에 연결하는 프로바이더 에이전트",
    )
    p.add_argument("--token", help="중앙 서버에서 발급받은 일회용 토큰 (또는 AGENT_TOKEN)")
    p.add_argument("--relay-url", help="중앙 서버 WS 주소 (또는 RELAY_URL)")
    p.add_argument("--ollama-url", help="로컬 Ollama 주소 (또는 OLLAMA_BASE_URL)")
    p.add_argument("--model", action="append", dest="models", help="제공 모델(여러 번 지정 가능)")
    p.add_argument("--max-concurrency", type=int, help="동시 처리 요청 수 (기본 1)")
    p.add_argument("--daily-limit", type=int, help=f"하루 처리 한도 (기본 {DEFAULT_DAILY_LIMIT}, 0=무제한은 --allow-unlimited 필요)")
    p.add_argument("--allow-unlimited", action="store_true", help="일일 한도 무제한(0)을 명시적으로 허용(위험)")
    p.add_argument("--allow-remote-ollama", action="store_true", help="원격 Ollama 주소 허용(기본 localhost 전용, 위험 확인 옵션)")
    p.add_argument("--enable-image", action="store_true", help="로컬 Stable Diffusion 이미지 생성 제공(opt-in)")
    p.add_argument("--sd-url", help="로컬 Stable Diffusion(A1111) 주소 (기본 http://127.0.0.1:7860)")
    p.add_argument("--allow-remote-sd", action="store_true", help="원격 SD 주소 허용(기본 localhost 전용, 위험 확인 옵션)")
    p.add_argument("--run-on-battery", action="store_true", help="배터리(방전) 중에도 처리 계속(기본은 자동 pause)")
    p.add_argument("--request-timeout", type=float, help="요청당 타임아웃 초 (기본 120)")
    p.add_argument("--heartbeat", type=float, dest="heartbeat_seconds", help="heartbeat 주기 초 (기본 30)")
    p.add_argument("--log-file", help="로그를 파일에도 기록(회전)")
    p.add_argument("--self-test", action="store_true", help="연결 없이 Ollama 자가 점검 후 종료")
    p.add_argument("--save-config", action="store_true", help="현재 설정을 ~/.config 에 저장(시크릿 0600)")
    p.add_argument("--install-service", action="store_true", help="로그인 시 자동 실행되는 사용자 서비스 등록 후 종료(관리자 불필요)")
    p.add_argument("--service", action="store_true", help="헤드리스 자동시작 모드로 실행(창·동의 프롬프트 없이 저장된 설정으로 연결). launchd/업데이터가 사용")
    p.add_argument("--gui", action="store_true", help="브라우저 설정 UI 를 띄운다(토큰·풀 설정·자동시작을 클릭으로)")
    p.add_argument("--tray", action="store_true", help="실행 중 시스템 트레이 아이콘 표시(라이브 상태·중지, 데스크톱 전용)")
    p.add_argument("--telemetry", action="store_true", help="익명 텔레메트리 opt-in(기본 꺼짐)")
    p.add_argument("--yes", action="store_true", help="첫 실행 동의 화면을 자동 승인(스크립트/서비스용)")
    p.add_argument("-v", "--verbose", action="store_true", help="디버그 로그")
    p.add_argument("--version", action="version", version=f"%(prog)s {AGENT_VERSION}")
    return p


def config_from_args(argv: list[str] | None = None) -> tuple[AgentConfig, bool]:
    """CLI/env/저장파일 로부터 (config, verbose) 를 만든다. 토큰이 없으면 SystemExit.

    우선순위: CLI 인자 > 환경변수 > 저장 설정파일(#113) > 기본값.
    """
    from .config_file import load_config, save_config
    from .netguard import RemoteOllamaBlocked, ensure_ollama_allowed

    parser = build_parser()
    args = parser.parse_args(argv)
    saved = load_config()  # 저장된 설정(없으면 빈 dict)

    token = (args.token or _env("AGENT_TOKEN") or saved.get("token", "")).strip()
    # self-test·gui 는 토큰 없이 가능(점검/설정 UI 만).
    if not token and not args.self_test and not args.gui:
        parser.error("토큰이 필요합니다: --token 또는 AGENT_TOKEN. 처음이면 --gui 로 브라우저 설정창을 여세요.")

    relay_url = (args.relay_url or _env("RELAY_URL") or saved.get("relay_url") or "ws://localhost:8080/agent").rstrip("/")
    ollama_url = (args.ollama_url or _env("OLLAMA_BASE_URL") or saved.get("ollama_url") or "http://localhost:11434").rstrip("/")
    models = tuple(args.models) if args.models else tuple(saved.get("models") or ())

    allow_remote_ollama = bool(args.allow_remote_ollama) or bool(saved.get("allow_remote_ollama"))
    # 안전 기본값: 원격 Ollama 주소는 명시 허용이 없으면 차단(localhost 전용).
    try:
        ensure_ollama_allowed(ollama_url, allow_remote_ollama)
    except RemoteOllamaBlocked as exc:
        parser.error(str(exc))

    enable_image = bool(args.enable_image) or bool(saved.get("enable_image"))
    sd_url = (args.sd_url or _env("SD_BASE_URL") or saved.get("sd_url") or "http://127.0.0.1:7860").rstrip("/")
    allow_remote_sd = bool(args.allow_remote_sd) or bool(saved.get("allow_remote_sd"))
    if enable_image:
        # SD 도 기본 localhost 전용(netguard 재사용). 원격은 --allow-remote-sd 에서만.
        try:
            ensure_ollama_allowed(sd_url, allow_remote_sd)
        except RemoteOllamaBlocked:
            parser.error(
                f"원격 SD 주소가 차단되었습니다: {sd_url!r}. 기본값은 localhost 만 허용합니다. "
                "원격 SD 를 정말 쓰려면 --allow-remote-sd 옵션을 명시하세요."
            )

    # 일일 한도: 기본 DEFAULT_DAILY_LIMIT. 0(무제한)은 --allow-unlimited 가 있을 때만 허용.
    if args.daily_limit is not None:
        requested_limit = args.daily_limit
        limit_from_saved = False
    elif saved.get("daily_limit") is not None:
        requested_limit = int(saved.get("daily_limit") or 0)
        limit_from_saved = True  # 저장된 설정은 저장 시점에 이미 동의를 거쳤다.
    else:
        requested_limit = DEFAULT_DAILY_LIMIT
        limit_from_saved = False
    if requested_limit <= 0:
        if args.allow_unlimited or saved.get("allow_unlimited") or limit_from_saved:
            daily_limit = 0
        else:
            parser.error(
                "일일 한도 0(무제한)은 안전을 위해 기본 차단됩니다. 정말 무제한으로 쓰려면 "
                "--allow-unlimited 를 함께 지정하세요. (권장: --daily-limit 10~20)"
            )
    else:
        daily_limit = requested_limit

    cfg = AgentConfig(
        token=token,
        relay_url=relay_url,
        ollama_url=ollama_url,
        models=models,
        max_concurrency=max(1, args.max_concurrency if args.max_concurrency is not None else int(saved.get("max_concurrency") or 1)),
        daily_limit=daily_limit,
        request_timeout=args.request_timeout if args.request_timeout is not None else 120.0,
        heartbeat_seconds=args.heartbeat_seconds if args.heartbeat_seconds is not None else 30.0,
        log_file=(args.log_file or "").strip(),
        self_test=bool(args.self_test),
        telemetry=bool(args.telemetry),
        allow_remote_ollama=allow_remote_ollama,
        pause_on_battery=not bool(args.run_on_battery),
        enable_image=enable_image,
        sd_url=sd_url,
        allow_remote_sd=allow_remote_sd,
        # --service(헤드리스 자동시작)는 동의 프롬프트를 띄울 수 없으므로 자동 승인(--yes)을 포함한다.
        assume_yes=bool(args.yes) or bool(args.service),
        service=bool(args.service),
        install_service=bool(args.install_service),
        gui=bool(args.gui),
        tray=bool(args.tray) or bool(saved.get("tray")),
        auto_update=bool(saved.get("auto_update", True)),
    )
    # 서비스 등록은 저장된 설정으로 무인자 실행하므로 설정 저장이 전제다.
    if args.save_config or args.install_service:
        save_config(cfg)
    return cfg, bool(args.verbose)
