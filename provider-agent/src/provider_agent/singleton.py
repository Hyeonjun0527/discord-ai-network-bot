"""단일 인스턴스 락 — 한 머신에서 에이전트가 **하나만** 중앙 서버에 연결되게 한다.

두 에이전트(예: GUI 안 + 자동실행 서비스)가 같은 프로바이더 신원으로 동시에 연결하면, 서버가
한쪽을 교체→끊고 끊긴 쪽이 재연결→다시 교체… 무한 핑퐁(연결완료↔연결중)이 발생한다. 이를
막기 위해 프로세스 간 락(루프백 포트 바인드)을 잡는다. 이미 잡혀 있으면 두 번째 인스턴스는
연결하지 않는다. 포트 바인드는 OS 가 프로세스 종료 시 자동 해제하므로 죽은 락이 남지 않는다.
"""
from __future__ import annotations

import logging
import socket

logger = logging.getLogger("provider_agent.singleton")

# 앱 전용 루프백 포트(다른 소프트웨어와 충돌 가능성이 낮은 비표준 포트).
_LOCK_PORT = 48569
_sock: socket.socket | None = None


def acquire() -> bool:
    """단일 인스턴스 락을 잡는다. 성공하면 True, 이미 다른 인스턴스가 잡고 있으면 False."""
    global _sock
    if _sock is not None:
        return True  # 같은 프로세스에서 이미 보유
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("127.0.0.1", _LOCK_PORT))
        s.listen(1)
    except OSError:
        s.close()
        return False
    _sock = s
    return True


def release() -> None:
    """락을 해제한다(소켓 닫기). 중지 후 재시작을 위해."""
    global _sock
    if _sock is not None:
        try:
            _sock.close()
        except OSError:  # pragma: no cover
            pass
        _sock = None
