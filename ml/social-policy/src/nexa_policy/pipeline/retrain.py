"""정기 재학습 파이프라인 오케스트레이션(NEXA-P19-T019). 운영 데이터 미접근 — 합성·결정론. torch 미사용.

dataset 승인→train→eval→model card→signature→shadow 등록을 한 흐름으로 묶는다(deliverable T019). 핵심 안전
불변식: **평가(eval)에 실패한 모델은 registry ACTIVE 로 승격되지 않는다**(acceptance T019). 파이프라인은 각
stage 의 게이트를 강제하고, 마지막 등록은 **SHADOW 까지만** 한다 — ACTIVE 승격은 human approval(P19-T020)·독립
리뷰(P19-T023)를 거치는 별도 경로다(자동 승격 없음, ADR 0014 일관).

**acceptance(T019) — 평가 실패 모델이 registry ACTIVE 로 승격되지 않는다**:
- [run_retrain_pipeline] 은 [EvalGate.passed] 가 False 면 [PipelineOutcome] 을 REJECTED 로 끝낸다 — 등록조차 하지
  않는다(SHADOW 도 안 됨).
- 평가를 통과해도 결과 status 는 최대 SHADOW 다. ACTIVE 로 가는 stage 는 이 파이프라인에 **존재하지 않는다**
  (human gate 뒤). [PipelineOutcome.promoted_to_active] 는 항상 False.

이 모듈은 순수 오케스트레이션이다 — 실제 학습/서명은 주입된 콜러블(또는 fixture)이 수행하고, 여기서는 stage
순서·게이트·결과 status 만 강제한다(결정론·CI 친화).
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from enum import StrEnum


class PipelineStage(StrEnum):
    """재학습 파이프라인 stage(순서 고정)."""

    DATASET_APPROVAL = "dataset_approval"
    TRAIN = "train"
    EVAL = "eval"
    MODEL_CARD = "model_card"
    SIGNATURE = "signature"
    SHADOW_REGISTER = "shadow_register"


class PipelineStatus(StrEnum):
    """파이프라인 종료 status. ACTIVE 는 없다(human gate 뒤)."""

    REJECTED = "rejected"            # dataset 미승인·eval 실패 등 — 등록 안 됨.
    REGISTERED_SHADOW = "registered_shadow"  # 통과 — SHADOW 까지만 등록(ACTIVE 아님).


@dataclass(frozen=True)
class EvalGate:
    """평가 게이트 결과. [passed]=False 면 등록 차단(acceptance T019)."""

    passed: bool
    metrics: dict[str, float] = field(default_factory=dict)
    reason: str = ""


@dataclass(frozen=True)
class PipelineInputs:
    """파이프라인 입력(주입 — 결정론·테스트 친화). 콜러블은 fixture 산출물을 돌려준다.

    - [dataset_approved]: dataset 승인 게이트(사람/거버넌스). False 면 시작 안 함.
    - [train_fn]: 학습 콜러블 → model_id.
    - [eval_fn]: 평가 콜러블 → [EvalGate].
    - [model_card_fn]: model card 생성 콜러블(model_id → card 텍스트).
    - [sign_fn]: 서명 콜러블(model_id → signature hex).
    - [shadow_register_fn]: SHADOW 등록 콜러블(model_id → None). 평가 통과 시에만 호출된다.
    """

    dataset_approved: bool
    train_fn: Callable[[], str]
    eval_fn: Callable[[str], EvalGate]
    model_card_fn: Callable[[str], str]
    sign_fn: Callable[[str], str]
    shadow_register_fn: Callable[[str], None]


@dataclass(frozen=True)
class PipelineOutcome:
    """파이프라인 결과. ACTIVE 승격은 절대 일어나지 않는다(promoted_to_active=False)."""

    status: PipelineStatus
    completed_stages: list[PipelineStage]
    model_id: str | None
    eval_gate: EvalGate | None
    rejected_reason: str = ""

    @property
    def promoted_to_active(self) -> bool:
        """ACTIVE 승격 여부 — 항상 False(자동 승격 없음, acceptance T019)."""
        return False

    def to_dict(self) -> dict[str, object]:
        return {
            "status": self.status.value,
            "completed_stages": [s.value for s in self.completed_stages],
            "model_id": self.model_id,
            "eval_passed": self.eval_gate.passed if self.eval_gate else None,
            "promoted_to_active": self.promoted_to_active,
            "rejected_reason": self.rejected_reason,
        }


def run_retrain_pipeline(inputs: PipelineInputs) -> PipelineOutcome:
    """재학습 파이프라인을 stage 순서대로 실행한다(게이트 강제).

    dataset 미승인 또는 eval 실패면 REJECTED 로 끝내고 등록하지 않는다. 통과하면 model card→signature→SHADOW
    등록까지 하고 REGISTERED_SHADOW 로 끝낸다 — **ACTIVE 승격은 이 파이프라인 밖**(human gate, T020/T023).
    """
    completed: list[PipelineStage] = []

    if not inputs.dataset_approved:
        return PipelineOutcome(
            status=PipelineStatus.REJECTED,
            completed_stages=completed,
            model_id=None,
            eval_gate=None,
            rejected_reason="dataset 미승인 — 재학습을 시작하지 않는다",
        )
    completed.append(PipelineStage.DATASET_APPROVAL)

    model_id = inputs.train_fn()
    completed.append(PipelineStage.TRAIN)

    gate = inputs.eval_fn(model_id)
    completed.append(PipelineStage.EVAL)
    if not gate.passed:
        # 평가 실패 — 등록(SHADOW 조차) 금지. ACTIVE 는 더더욱 없다.
        return PipelineOutcome(
            status=PipelineStatus.REJECTED,
            completed_stages=completed,
            model_id=model_id,
            eval_gate=gate,
            rejected_reason=gate.reason or "평가 실패 — registry 등록 차단",
        )

    inputs.model_card_fn(model_id)
    completed.append(PipelineStage.MODEL_CARD)

    inputs.sign_fn(model_id)
    completed.append(PipelineStage.SIGNATURE)

    # 평가를 통과한 모델만 SHADOW 로 등록(ACTIVE 아님 — human gate 뒤).
    inputs.shadow_register_fn(model_id)
    completed.append(PipelineStage.SHADOW_REGISTER)

    return PipelineOutcome(
        status=PipelineStatus.REGISTERED_SHADOW,
        completed_stages=completed,
        model_id=model_id,
        eval_gate=gate,
    )
