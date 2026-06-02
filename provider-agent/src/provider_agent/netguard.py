"""Ollama 접근 제한 (안전 기본값).

기본적으로 **localhost / 127.0.0.1 / ::1(루프백) 만 허용**한다. LAN IP·public IP·외부
호스트명·외부 URL 은 기본 차단한다. 원격 Ollama 는 사용자가 위험을 인지하고
``--allow-remote-ollama`` 를 명시했을 때만 허용한다.

이 게이트는 "내 PC 의 로컬 LLM 만 풀에 연결한다"는 제품 불변식을 코드로 강제한다.
"""
from __future__ import annotations

import ipaddress
from urllib.parse import urlparse

# 루프백으로 취급하는 호스트명(IP 가 아니라서 ip_address 로 못 잡는 것들).
LOCAL_HOSTNAMES: frozenset[str] = frozenset(
    {"localhost", "ip6-localhost", "ip6-loopback"}
)


class RemoteOllamaBlocked(Exception):
    """원격 Ollama 주소가 안전 기본값(localhost 전용)에 의해 차단됨."""


def is_local_ollama(url: str) -> bool:
    """``url`` 의 호스트가 루프백(localhost/127.0.0.0-8/::1)이면 True.

    호스트명을 DNS 로 해석하지 않는다(해석 결과가 루프백이 아닐 수 있고, 공격 표면이 됨).
    따라서 알려진 루프백 호스트명과 루프백 IP 리터럴만 통과시키고, 그 외(LAN/public/임의
    호스트명)는 모두 False(차단)로 본다 — **기본 거부(deny-by-default)**.
    """
    host = urlparse(url).hostname or ""
    if not host:
        return False
    h = host.strip("[]").lower()
    if h in LOCAL_HOSTNAMES:
        return True
    try:
        ip = ipaddress.ip_address(h)
    except ValueError:
        # 루프백이 아닌 호스트명(예: my-nas.local, example.com) → 기본 차단.
        return False
    return ip.is_loopback


def ensure_ollama_allowed(url: str, allow_remote: bool) -> None:
    """원격 Ollama 가 차단 대상이면 ``RemoteOllamaBlocked`` 를 던진다.

    ``allow_remote`` 가 True 면(사용자가 ``--allow-remote-ollama`` 로 위험 확인) 통과시킨다.
    """
    if allow_remote:
        return
    if not is_local_ollama(url):
        host = urlparse(url).hostname or url
        raise RemoteOllamaBlocked(
            f"원격 Ollama 주소가 차단되었습니다: {host!r}. 기본값은 localhost(127.0.0.1/::1)만 "
            "허용합니다. 원격 Ollama 를 정말 쓰려면 --allow-remote-ollama 옵션을 명시하세요."
        )
