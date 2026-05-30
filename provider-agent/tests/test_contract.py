"""크로스언어 컨트랙트 테스트 (LAUNCH 차수 17).

Python 에이전트와 Kotlin 중앙 서버가 **동일한 와이어 픽스처**를 공유 검증한다.
같은 JSON 파일을 central-server 의 Kotlin 컨트랙트 테스트도 읽는다 → 와이어 드리프트 방지.
"""
from __future__ import annotations

import json
import pathlib

from provider_agent import protocol as p

_FIXTURES = (
    pathlib.Path(__file__).resolve().parents[2]
    / "central-server/src/test/resources/wire-fixtures.json"
)


def test_wire_fixtures_round_trip():
    fixtures = json.loads(_FIXTURES.read_text(encoding="utf-8"))
    assert len(fixtures) >= 12, "픽스처 누락"
    for name, expected in fixtures.items():
        # 1) 서버가 보낸 형태(JSON)를 파싱할 수 있어야 한다.
        frame = p.loads_frame(json.dumps(expected, ensure_ascii=False))
        # 2) 다시 직렬화한 dict 가 정확히 같아야 한다(camelCase 와이어 일치).
        assert p.frame_to_dict(frame) == expected, f"와이어 불일치: {name}"
