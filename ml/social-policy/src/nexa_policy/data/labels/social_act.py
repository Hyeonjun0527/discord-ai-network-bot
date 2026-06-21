"""social act 약지도 라벨러(NEXA-P10-T009).

reply/reaction/lexical 신호와 제한된 GLM 보조로 social act 후보를 만든다. social act 코드 집합은 central
SocialAct(wireName)과 일치한다(acknowledge/agree/disagree/tease/ask/correct/self_disclose/change_topic/unknown).

**acceptance(T009) — 약지도 confidence 와 model version 을 저장하고 gold label 로 오인하지 않는다**:
- 결과 [WeakSocialActLabel] 은 항상 `is_weak=True`, `confidence`(0~1), `source`(규칙/모델), `model_version` 을 담는다.
- gold 와 구분되는 별도 타입이라 학습 파이프라인이 약지도임을 잊을 수 없다.
- 신호가 모호하면 UNKNOWN(낮은 confidence) — 자유 텍스트 라벨을 만들지 않는다(central SocialAct.UNKNOWN 규칙).

GLM 보조는 **선택**이며 운영 데이터에 호출하지 않는다. 주입 가능한 [GlmActClassifier] 프로토콜로 받되,
없으면(기본) 규칙 신호만 쓴다 — 합성 fixture 로 동작/테스트한다.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Protocol

from nexa_policy.data.schema import EventRecord

SOCIAL_ACT_CATALOG_VERSION = 1


class SocialActCode(Enum):
    """central SocialAct.wireName 미러(드리프트 금지)."""

    ACKNOWLEDGE = "acknowledge"
    AGREE = "agree"
    DISAGREE = "disagree"
    TEASE = "tease"
    ASK = "ask"
    CORRECT = "correct"
    SELF_DISCLOSE = "self_disclose"
    CHANGE_TOPIC = "change_topic"
    UNKNOWN = "unknown"

    @classmethod
    def from_wire(cls, wire: str) -> SocialActCode:
        """미지 코드는 자유 텍스트로 보존하지 않고 UNKNOWN 으로 정규화(central fromWireName 규칙)."""
        for member in cls:
            if member.value == wire:
                return member
        return cls.UNKNOWN


@dataclass(frozen=True)
class WeakSocialActLabel:
    """약지도 social act 라벨. 항상 weak — gold 로 오인 불가."""

    act: SocialActCode
    confidence: float
    source: str  # "rule" 또는 "glm".
    model_version: str
    catalog_version: int = SOCIAL_ACT_CATALOG_VERSION
    is_weak: bool = True

    def __post_init__(self) -> None:
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("confidence 는 0~1 이어야 한다.")
        if not self.is_weak:
            raise ValueError("이 라벨러는 약지도 전용이다(is_weak 는 항상 True).")

    def to_dict(self) -> dict[str, object]:
        return {
            "act": self.act.value,
            "confidence": self.confidence,
            "source": self.source,
            "model_version": self.model_version,
            "catalog_version": self.catalog_version,
            "is_weak": self.is_weak,
        }


class GlmActClassifier(Protocol):
    """제한된 GLM 보조 분류기(선택 주입). 운영 데이터 호출 금지 — 합성/익명 신호만 받는다."""

    @property
    def model_version(self) -> str: ...

    def classify(self, signals: dict[str, object]) -> tuple[str, float]:
        """신호 dict 로 (act wireName, confidence) 를 반환한다. 원문 미전달."""
        ...


def label_social_act(
    *,
    action_event: EventRecord,
    glm: GlmActClassifier | None = None,
) -> WeakSocialActLabel:
    """reply/reaction/lexical 규칙으로 약지도 social act 를 만들고, 모호하면 GLM 보조(있으면)로 보강한다.

    규칙(고confidence 우선):
    - reaction 이벤트 → ACKNOWLEDGE.
    - is_question 신호 → ASK.
    - reply 인데 신호 부족 → 낮은 confidence ACKNOWLEDGE.
    그 외 모호 → GLM 보조(있으면) 또는 UNKNOWN(낮은 confidence).
    """
    features = action_event.features or {}

    if action_event.event_kind == "reaction":
        return WeakSocialActLabel(SocialActCode.ACKNOWLEDGE, 0.7, "rule", "rule-v1")
    if features.get("is_question") is True:
        return WeakSocialActLabel(SocialActCode.ASK, 0.8, "rule", "rule-v1")
    if action_event.event_kind == "reply":
        # 답장인데 어휘 신호가 약함 → 약한 맞장구 추정.
        weak = WeakSocialActLabel(SocialActCode.ACKNOWLEDGE, 0.4, "rule", "rule-v1")
        if glm is None:
            return weak
        wire, conf = glm.classify(_glm_signals(action_event))
        if conf > weak.confidence:
            return WeakSocialActLabel(
                SocialActCode.from_wire(wire), conf, "glm", glm.model_version
            )
        return weak

    if glm is not None:
        wire, conf = glm.classify(_glm_signals(action_event))
        return WeakSocialActLabel(SocialActCode.from_wire(wire), conf, "glm", glm.model_version)
    return WeakSocialActLabel(SocialActCode.UNKNOWN, 0.2, "rule", "rule-v1")


def _glm_signals(event: EventRecord) -> dict[str, object]:
    """GLM 보조에 넘길 **원문 없는** 신호 dict(데이터 카테고리 준수)."""
    features = event.features or {}
    return {
        "event_kind": event.event_kind,
        "is_question": features.get("is_question"),
        "char_len_bucket": features.get("char_len_bucket"),
        "has_reply": features.get("reply_to_event_id") is not None,
    }
