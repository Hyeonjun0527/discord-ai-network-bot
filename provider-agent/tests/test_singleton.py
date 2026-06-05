"""단일 인스턴스 락 테스트 — 획득/해제 + 비획득 프로브(is_held/held_by_other).

머신 독립: 기본 포트(48569)는 개발 머신에서 실제 에이전트가 점유 중일 수 있으므로,
테스트마다 빈 포트로 ``_LOCK_PORT`` 를 바꿔 외부 상태에 영향받지 않게 한다.
"""
from __future__ import annotations

import socket

import provider_agent.singleton as sg


def _free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def test_acquire_isheld_release_roundtrip(monkeypatch):
    monkeypatch.setattr(sg, "_LOCK_PORT", _free_port())
    sg.release()
    assert sg.is_held() is False
    assert sg.held_by_other() is False
    assert sg.acquire() is True
    try:
        assert sg.is_held() is True  # 누군가(=우리) 잡고 있다
        assert sg.held_by_other() is False  # 우리가 잡았으니 '다른' 인스턴스는 아니다
    finally:
        sg.release()
    assert sg.is_held() is False
    assert sg.held_by_other() is False


def test_acquire_is_idempotent_in_same_process(monkeypatch):
    monkeypatch.setattr(sg, "_LOCK_PORT", _free_port())
    sg.release()
    try:
        assert sg.acquire() is True
        assert sg.acquire() is True  # 같은 프로세스에서 다시 호출해도 보유로 True
    finally:
        sg.release()


def test_held_by_other_when_external_holds(monkeypatch):
    """다른 프로세스가 락을 잡은 상황: _sock 은 None 이지만 포트는 잡혀 있어야 held_by_other=True."""
    port = _free_port()
    monkeypatch.setattr(sg, "_LOCK_PORT", port)
    sg.release()  # 이 프로세스는 잡지 않는다
    ext = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ext.bind(("127.0.0.1", port))
    ext.listen(1)
    try:
        assert sg.is_held() is True
        assert sg.held_by_other() is True
    finally:
        ext.close()
        sg.release()
