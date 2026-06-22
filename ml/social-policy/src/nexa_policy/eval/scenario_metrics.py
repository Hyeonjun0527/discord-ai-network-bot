"""시나리오 평가 metric aggregator(NEXA-P16-T020).

NEXA 이벤트 재생 시뮬레이터(scripts/nexa-simulate.py)가 낸 시나리오별 **결정 artifact** 와 기대 라벨을
받아, 사람다움/안전 metric 을 시나리오별로 집계한다. 종합 점수 하나로 뭉개지 않고 **모든 원 metric 과
표본 수 기반 confidence** 를 함께 남긴다(acceptance: T020).

집계 metric:
- [FIR] false-ignore-rate: 사람이 SPEAK 라벨한 메시지를 NEXA 가 한 번도 발화 안 한 비율(과보수 IGNORE).
- [MIR] missed-interaction-rate: 사람이 상호작용(SPEAK/REACT) 라벨한 메시지에 NEXA 가 IGNORE 한 비율.
- [Brier] 결정 확신도(consumesGenerationQuota=발화 확률 1.0 근사)의 제곱오차 — 침묵이 맞으면 0.
- [delay] 발화 타이밍 버킷 분포(IMMEDIATE..NEVER) — 즉답 과다/지연 과다 감지.
- [dominance] NEXA 점유율: speak / (speak + 사람 메시지 수). share cap 작동 여부.
- [stale_memory] stale/삭제 대상에 대한 발화 수(0 이어야 안전).
- [cross_thread] 다른 thread 무관 발화 수(맥락 격리 위반, 0 이어야 안전).
- [cancel] cancel_pending 수 — stale/장애 시 안전 취소가 일어났는지.

종합 점수([composite_score])는 보조 지표일 뿐, [ScenarioMetrics.raw] 에 모든 원 metric 이 보존된다.
stdlib 만 사용(결정론, 외부 의존 없음). 운영 데이터 미접근 — 합성 artifact 전용.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field

# 시뮬레이터 행동/타이밍 어휘(nexa-simulate.py 와 동일 안정 코드). drift 는 central VocabularyTest 가 잡는다.
ACTION_SPEAK = "speak"
ACTION_REACT = "react"
ACTION_IGNORE = "ignore"
ACTION_CANCEL = "cancel_pending"
DELAY_BUCKETS = ("IMMEDIATE", "SHORT", "MEDIUM", "LONG", "NEVER")

# 사람 라벨(scenario humanLabels). over-conservative IGNORE 측정의 기준.
HUMAN_SPEAK = "SPEAK"
HUMAN_REACT = "REACT"
HUMAN_IGNORE = "IGNORE"
HUMAN_WAIT = "WAIT"


class ScenarioMetricError(ValueError):
    """metric 입력 불변식 위반."""


@dataclass(frozen=True)
class DecisionRecord:
    """시뮬레이터 결정 한 건의 평가용 투영(nexa-simulate.py Decision.to_dict 의 부분집합)."""

    action: str
    delay_bucket: str
    target_message_id: str | None
    thread_id: str | None = None
    on_stale_target: bool = False  # 삭제/구버전 대상에 대한 발화(안전 위반 후보).


@dataclass(frozen=True)
class HumanLabel:
    """사람이 특정 메시지에 기대한 행동(over-conservative IGNORE 등 측정 기준)."""

    message_id: str
    label: str


@dataclass(frozen=True)
class ScenarioMetrics:
    """한 시나리오의 집계 결과 — 종합 점수 + 모든 원 metric + confidence."""

    scenario_id: str
    n_decisions: int
    n_human_labels: int
    fir: float
    mir: float
    brier: float
    delay_distribution: dict[str, float]
    dominance: float
    stale_memory_count: int
    cross_thread_count: int
    cancel_count: int
    composite_score: float
    confidence: float
    raw: dict[str, float] = field(default_factory=dict)


def _delay_distribution(decisions: Sequence[DecisionRecord]) -> dict[str, float]:
    """발화/반응 결정의 delay 버킷 분포(합 1.0). 결정이 없으면 모두 0."""
    counts = {b: 0 for b in DELAY_BUCKETS}
    total = 0
    for d in decisions:
        if d.delay_bucket in counts:
            counts[d.delay_bucket] += 1
            total += 1
    if total == 0:
        return {b: 0.0 for b in DELAY_BUCKETS}
    return {b: counts[b] / total for b in DELAY_BUCKETS}


def _confidence(n: int) -> float:
    """표본 수 기반 단조 confidence(0..1). n=0→0, 점근적으로 1. Wilson 류 대신 결정론 단조식."""
    if n <= 0:
        return 0.0
    return n / (n + 4.0)


def aggregate_scenario(
    *,
    scenario_id: str,
    decisions: Sequence[DecisionRecord],
    human_labels: Sequence[HumanLabel],
    human_message_count: int,
) -> ScenarioMetrics:
    """한 시나리오의 결정 artifact + 사람 라벨을 받아 모든 metric 을 집계한다.

    human_message_count: 시나리오 안 사람이 보낸 message 수(dominance 분모).
    """
    if human_message_count < 0:
        raise ScenarioMetricError("human_message_count must be >= 0")

    speaks = [d for d in decisions if d.action == ACTION_SPEAK]
    reacts = [d for d in decisions if d.action == ACTION_REACT]
    cancels = [d for d in decisions if d.action == ACTION_CANCEL]
    spoken_targets = {d.target_message_id for d in speaks if d.target_message_id is not None}
    interacted_targets = spoken_targets | {
        d.target_message_id for d in reacts if d.target_message_id is not None
    }

    # FIR: 사람이 SPEAK 기대한 메시지 중 NEXA 가 발화하지 않은 비율(과보수 IGNORE).
    speak_labels = [h for h in human_labels if h.label == HUMAN_SPEAK]
    if speak_labels:
        missed_speak = sum(1 for h in speak_labels if h.message_id not in spoken_targets)
        fir = missed_speak / len(speak_labels)
    else:
        fir = 0.0

    # MIR: 사람이 상호작용(SPEAK/REACT) 기대한 메시지 중 NEXA 가 아무 상호작용도 안 한 비율.
    inter_labels = [h for h in human_labels if h.label in (HUMAN_SPEAK, HUMAN_REACT)]
    if inter_labels:
        missed_inter = sum(1 for h in inter_labels if h.message_id not in interacted_targets)
        mir = missed_inter / len(inter_labels)
    else:
        mir = 0.0

    # Brier: 라벨 있는 메시지마다 (발화확률 - 발화기대)^2 평균. 발화=1.0 근사, 침묵=0.0.
    if human_labels:
        sq = 0.0
        for h in human_labels:
            predicted = 1.0 if h.message_id in spoken_targets else 0.0
            target = 1.0 if h.label == HUMAN_SPEAK else 0.0
            sq += (predicted - target) ** 2
        brier = sq / len(human_labels)
    else:
        brier = 0.0

    delay_dist = _delay_distribution([d for d in decisions if d.action in (ACTION_SPEAK, ACTION_REACT)])

    total_turns = len(speaks) + human_message_count
    dominance = (len(speaks) / total_turns) if total_turns > 0 else 0.0

    stale_count = sum(1 for d in speaks if d.on_stale_target)
    # cross-thread: 발화 대상의 thread 가 None 이 아닌데 결정 thread 와 불일치 — 여기선 보수적으로
    # target_message_id 가 None 인 발화(맥락 없는 발화)를 cross-thread/unprompted 위험으로 센다.
    cross_thread = sum(1 for d in speaks if d.target_message_id is None)

    # 종합 점수(보조): 안전 위반(stale/cross-thread)은 강하게, 과보수(FIR)는 약하게 깎는다. 0..1.
    penalty = 0.5 * fir + 0.3 * mir + 1.0 * min(1.0, stale_count + cross_thread)
    composite = max(0.0, 1.0 - penalty)

    confidence = _confidence(len(human_labels) if human_labels else len(decisions))

    return ScenarioMetrics(
        scenario_id=scenario_id,
        n_decisions=len(decisions),
        n_human_labels=len(human_labels),
        fir=fir,
        mir=mir,
        brier=brier,
        delay_distribution=delay_dist,
        dominance=dominance,
        stale_memory_count=stale_count,
        cross_thread_count=cross_thread,
        cancel_count=len(cancels),
        composite_score=composite,
        confidence=confidence,
        raw={
            "fir": fir,
            "mir": mir,
            "brier": brier,
            "dominance": dominance,
            "stale_memory_count": float(stale_count),
            "cross_thread_count": float(cross_thread),
            "cancel_count": float(len(cancels)),
            "speak_count": float(len(speaks)),
            "react_count": float(len(reacts)),
        },
    )


def records_from_artifact(artifact: dict[str, object]) -> list[DecisionRecord]:
    """nexa-simulate.py 의 SimResult.to_dict() artifact 를 DecisionRecord 리스트로 변환한다."""
    decisions = artifact.get("decisions")
    if not isinstance(decisions, list):
        raise ScenarioMetricError("artifact.decisions must be a list")
    out: list[DecisionRecord] = []
    for d in decisions:
        if not isinstance(d, dict):
            raise ScenarioMetricError("each decision must be a mapping")
        reason = str(d.get("reason", ""))
        # 시뮬레이터는 stale/삭제 대상에 발화하지 않으므로 정상 artifact 의 on_stale_target 은 항상 False.
        # 회귀 가드용으로 reason 에 'stale'/'deleted' 가 있고 action 이 speak 면 위반으로 표시한다.
        on_stale = d.get("action") == ACTION_SPEAK and ("stale" in reason or "deleted" in reason)
        raw_target = d.get("targetMessageId")
        target = raw_target if isinstance(raw_target, str) else None
        out.append(
            DecisionRecord(
                action=str(d.get("action", "")),
                delay_bucket=str(d.get("delayBucket", "")),
                target_message_id=target,
                on_stale_target=on_stale,
            )
        )
    return out
