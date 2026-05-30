"""버전 체크 (차수 9 #108).

중앙 서버가 알려주는 최신/최소 버전과 에이전트 버전을 비교해 구버전이면 경고한다.
자동 업데이트 *다운로드* 는 패키징 채널(pip/Docker/PyInstaller)에 위임하고, 여기서는 안내만 한다.
"""
from __future__ import annotations

import re

_NUM = re.compile(r"\d+")


def parse_version(v: str) -> tuple[int, ...]:
    """'1.2.3' → (1,2,3). 숫자 토큰만 취해 비교 가능하게 만든다."""
    parts = [int(x) for x in _NUM.findall(v or "")]
    return tuple(parts) if parts else (0,)


def compare(a: str, b: str) -> int:
    """a<b → -1, a==b → 0, a>b → 1 (자리수 다르면 0 패딩)."""
    pa, pb = parse_version(a), parse_version(b)
    n = max(len(pa), len(pb))
    pa += (0,) * (n - len(pa))
    pb += (0,) * (n - len(pb))
    return (pa > pb) - (pa < pb)


def is_outdated(current: str, latest: str) -> bool:
    """current 가 latest 보다 낮으면 True."""
    return compare(current, latest) < 0


def update_hint(current: str, latest: str) -> str | None:
    """구버전이면 업데이트 안내 문구, 아니면 None."""
    if is_outdated(current, latest):
        return f"새 버전이 있습니다: {current} → {latest}. pip/Docker/배포물로 업데이트하세요."
    return None
