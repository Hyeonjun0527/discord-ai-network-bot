"""첫 실행 동의 테스트."""
from __future__ import annotations

import io

from provider_agent.config import AgentConfig
from provider_agent.consent import ensure_consent, has_consented


def _cfg(**kw):
    return AgentConfig(token="T", **kw)


def test_assume_yes_records_consent(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    assert has_consented() is False
    out = io.StringIO()
    assert ensure_consent(_cfg(assume_yes=True), stream=out) is True
    assert has_consented() is True  # 표식 기록됨


def test_env_accept_terms(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    monkeypatch.setenv("AGENT_ACCEPT_TERMS", "1")
    out = io.StringIO()
    assert ensure_consent(_cfg(), stream=out) is True


def test_interactive_yes(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    monkeypatch.setattr("sys.stdin.isatty", lambda: True)
    out = io.StringIO()
    ok = ensure_consent(_cfg(), stream=out, input_fn=lambda _p: "yes")
    assert ok is True
    # 안내에 핵심 4요소가 노출되어야 한다.
    text = out.getvalue()
    assert "사용량 제한" in text
    assert "중앙 서버" in text
    assert "Ollama" in text
    assert "개인정보" in text


def test_interactive_no(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    monkeypatch.setattr("sys.stdin.isatty", lambda: True)
    out = io.StringIO()
    ok = ensure_consent(_cfg(), stream=out, input_fn=lambda _p: "no")
    assert ok is False
    assert has_consented() is False


def test_noninteractive_without_flag_refused(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    out = io.StringIO()
    assert ensure_consent(_cfg(), stream=out) is False


def test_already_consented_skips(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    out = io.StringIO()
    ensure_consent(_cfg(assume_yes=True), stream=out)
    # 두 번째 호출은 입력 없이 통과해야 한다(input_fn 호출 시 실패).
    def _boom(_p):
        raise AssertionError("이미 동의했는데 다시 물었다")

    assert ensure_consent(_cfg(), stream=out, input_fn=_boom) is True
