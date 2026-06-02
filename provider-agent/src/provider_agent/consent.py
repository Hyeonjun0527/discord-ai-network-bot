"""첫 실행 동의 (안전 기본값, 차수: 일반 사용자 배포).

에이전트를 처음 실행할 때 **사용량 제한·중앙 서버 주소·Ollama 주소·개인정보 주의사항**을
보여주고 사용자 동의를 받는다. 동의하면 설정 디렉터리에 표식 파일을 남겨 다음부터는 묻지 않는다.

비대화형(서비스/스크립트)에서는 ``--yes`` 또는 ``AGENT_ACCEPT_TERMS=1`` 로 사전 동의해야 한다.
"""
from __future__ import annotations

import os
import pathlib
import sys

from .config import AgentConfig
from .config_file import config_dir


def consent_marker_path() -> pathlib.Path:
    return config_dir() / ".consent-accepted"


def has_consented() -> bool:
    return consent_marker_path().exists()


def record_consent() -> None:
    path = consent_marker_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("accepted\n", encoding="utf-8")
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def _notice(cfg: AgentConfig) -> str:
    limit = "무제한(주의)" if cfg.daily_limit == 0 else f"하루 {cfg.daily_limit}건"
    ollama_scope = "원격 허용(주의)" if cfg.allow_remote_ollama else "localhost 전용"
    return (
        "\n"
        "════════════════════════════════════════════════════════════\n"
        "  커뮤니티 Provider Agent — 첫 실행 안내 (한 번만 표시)\n"
        "════════════════════════════════════════════════════════════\n"
        "  이 프로그램은 내 PC의 로컬 Ollama를 커뮤니티 풀에 연결해,\n"
        "  다른 사용자의 /ask 질문을 내 PC에서 처리하도록 돕습니다.\n"
        "  관리자 권한은 필요하지 않습니다(일반 사용자 권한으로 동작).\n"
        "\n"
        f"   • 사용량 제한 : {limit}, 동시 처리 {cfg.max_concurrency}건\n"
        f"   • 중앙 서버   : {cfg.relay_url}\n"
        f"   • Ollama 주소 : {cfg.ollama_url}  ({ollama_scope})\n"
        "\n"
        "  [개인정보 주의]\n"
        "   - 다른 사용자의 질문 내용이 내 PC(Ollama)로 전송되어 처리됩니다.\n"
        "   - 에이전트는 프롬프트 원문을 로그/파일에 저장하지 않습니다.\n"
        "   - 내 PC의 비밀번호·API 키·개인정보가 외부로 전송되지는 않습니다.\n"
        "   - 설정/로그는 내 홈 디렉터리 아래에만 저장됩니다(시스템 폴더 미사용).\n"
        "════════════════════════════════════════════════════════════\n"
    )


def ensure_consent(cfg: AgentConfig, *, stream=None, input_fn=input) -> bool:
    """첫 실행 동의를 확인/획득한다. 동의되어 있으면 True.

    우선순위: 이미 동의함 / ``--yes`` / ``AGENT_ACCEPT_TERMS`` → 즉시 True.
    그 외에는 안내를 출력하고 대화형으로 'yes' 입력을 받는다. 비대화형이면 False.
    """
    out = stream or sys.stderr
    if has_consented():
        return True
    if cfg.assume_yes or os.getenv("AGENT_ACCEPT_TERMS", "").strip().lower() in {"1", "true", "yes"}:
        record_consent()
        return True
    print(_notice(cfg), file=out)
    if not sys.stdin.isatty():
        print(
            "비대화형 환경입니다. 위 내용에 동의하면 --yes 또는 AGENT_ACCEPT_TERMS=1 로 다시 실행하세요.",
            file=out,
        )
        return False
    try:
        answer = input_fn("위 내용에 동의하면 'yes' 를 입력하세요 [yes/no]: ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        return False
    if answer in {"y", "yes", "동의"}:
        record_consent()
        print("동의가 기록되었습니다. 다음 실행부터는 표시되지 않습니다.", file=out)
        return True
    print("동의하지 않아 종료합니다.", file=out)
    return False
