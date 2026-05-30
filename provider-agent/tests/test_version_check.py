"""버전 체크(#108) 테스트."""
from __future__ import annotations

from provider_agent.version_check import compare, is_outdated, parse_version, update_hint


def test_parse_and_compare():
    assert parse_version("1.2.3") == (1, 2, 3)
    assert compare("1.0.0", "1.0.1") == -1
    assert compare("1.2.0", "1.2") == 0  # 0 패딩
    assert compare("2.0", "1.9.9") == 1


def test_is_outdated_and_hint():
    assert is_outdated("0.1.0", "0.2.0") is True
    assert is_outdated("1.0.0", "1.0.0") is False
    assert update_hint("0.1.0", "0.2.0") is not None
    assert "0.2.0" in update_hint("0.1.0", "0.2.0")
    assert update_hint("1.0.0", "1.0.0") is None
