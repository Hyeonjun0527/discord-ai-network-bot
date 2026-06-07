"""데스크톱 프로토타입 ↔ 실구현(webui) 계약 드리프트 가드 — provider-agent CI 에서 강제.

`scripts/check_desktop_contract.py` 의 경로 일치 검사를 pytest 로 묶어, adapter 가 호출하는
엔드포인트가 webui 라우트와 갈라지면(예: /api/servers/{g}/pause 누락) CI 가 즉시 실패하게 한다.
이것이 "프로토타입과 실구현이 달라지지 않음" 의 자동 보증이다.
"""
from __future__ import annotations

import importlib.util
import pathlib

import pytest

_ROOT = pathlib.Path(__file__).resolve().parents[2]
_SCRIPT = _ROOT / "scripts" / "check_desktop_contract.py"


def _load_checker():
    if not _SCRIPT.is_file():
        pytest.skip("check_desktop_contract.py 없음")
    spec = importlib.util.spec_from_file_location("check_desktop_contract", _SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(mod)
    return mod


def test_adapter_endpoints_exist_in_webui() -> None:
    """adapter 가 호출하는 모든 ENDPOINTS 경로가 webui 라우트에 존재(세그먼트 패턴)."""
    checker = _load_checker()
    problems = checker.check_contract()
    assert not problems, "데스크톱 계약 드리프트:\n" + "\n".join(problems)
