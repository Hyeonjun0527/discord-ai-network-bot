"""Ollama 접근 제한(localhost 전용 기본값) 테스트."""
from __future__ import annotations

import pytest

from provider_agent.netguard import (
    RemoteOllamaBlocked,
    ensure_ollama_allowed,
    is_local_ollama,
)


@pytest.mark.parametrize(
    "url",
    [
        "http://localhost:11434",
        "http://127.0.0.1:11434",
        "http://127.0.0.5:11434",  # 127.0.0.0/8 루프백
        "http://[::1]:11434",
        "https://localhost",
    ],
)
def test_local_urls_allowed(url):
    assert is_local_ollama(url) is True
    ensure_ollama_allowed(url, allow_remote=False)  # 예외 없어야 함


@pytest.mark.parametrize(
    "url",
    [
        "http://192.168.0.10:11434",  # LAN
        "http://10.0.0.5:11434",  # LAN
        "http://172.16.3.4:11434",  # LAN
        "http://203.0.113.7:11434",  # public
        "http://my-nas.local:11434",  # 외부 호스트명
        "http://example.com:11434",  # 외부 URL
        "http://0.0.0.0:11434",  # 와일드카드(루프백 아님)
    ],
)
def test_remote_urls_blocked_by_default(url):
    assert is_local_ollama(url) is False
    with pytest.raises(RemoteOllamaBlocked):
        ensure_ollama_allowed(url, allow_remote=False)


def test_remote_allowed_when_opted_in():
    # 위험 확인 옵션이 있으면 통과.
    ensure_ollama_allowed("http://192.168.0.10:11434", allow_remote=True)
