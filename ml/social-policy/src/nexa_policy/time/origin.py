"""정책 이벤트 시간 원점 계약(NEXA-P12-T001)의 코드 미러.

생존분석 시간축의 단일 출처다. 학습·Kotlin runtime·평가가 같은 원점 `t = 0` 과 같은 단위(초)를
쓰도록 변환 함수와 상수를 한곳에 못박는다(docs/nexa/policy/time-origin.md SSOT).

**acceptance(T001) — 학습·Kotlin runtime·평가가 같은 원점을 사용한다**:
- opportunity(burst finalize / scene update / 직접 호출)의 `event_time_ms` 가 `t = 0`.
- 시간축 `t` 는 **초(float)**: `t = (event_time_ms - origin_ms) / 1000`. ms 스케일은 hazard 에서 너무 작아
  초로 통일한다.
- origin 이전(`t < 0`) 사건은 시간축의 사건이 아니다([to_relative_seconds] 가 거부).

torch 비의존 — 순수 파이썬 산술. numpy 도 불필요(스칼라 변환).
"""

from __future__ import annotations

from enum import Enum

# ms → s 변환 상수. 모델·metric 의 시간축은 초(seconds)다(docs/nexa/policy/time-origin.md).
MS_PER_SECOND = 1000


class OpportunityKind(Enum):
    """`t = 0` 을 정하는 opportunity 이벤트 종류(time-origin.md). 동시 구간이면 가장 최근 시각을 쓴다."""

    BURST_FINALIZE = "burst_finalize"
    SCENE_UPDATE = "scene_update"
    DIRECT_ADDRESS = "direct_address"


class TimeOriginError(ValueError):
    """시간 원점 계약 위반(fail-closed)."""


def resolve_origin_ms(opportunity_times_ms: dict[OpportunityKind, int]) -> int:
    """동시 구간의 opportunity 후보들 중 **가장 늦은(최근) 시각**을 `t = 0` 으로 고른다(time-origin.md).

    가장 신선한 맥락이 타이밍 기준이다. 후보가 없으면 거부(opportunity 없이는 원점이 없다).
    """
    if not opportunity_times_ms:
        raise TimeOriginError("opportunity 후보가 비어 있어 시간 원점을 정할 수 없다.")
    return max(opportunity_times_ms.values())


def to_relative_seconds(*, event_time_ms: int, origin_ms: int) -> float:
    """절대 epoch ms 를 원점 기준 상대 초(float)로 변환한다(time-origin.md 단일 변환).

    `t = (event_time_ms - origin_ms) / 1000`. `t < 0`(원점 이전)은 시간축의 사건이 아니라 거부한다.
    """
    if event_time_ms < origin_ms:
        raise TimeOriginError(
            f"event_time_ms({event_time_ms}) 가 origin_ms({origin_ms}) 보다 이르다 — "
            "원점 이전 사건은 생존 시간축의 사건이 아니다."
        )
    return (event_time_ms - origin_ms) / MS_PER_SECOND
