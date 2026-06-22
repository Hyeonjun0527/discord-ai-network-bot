"""NEXA-P19-T019: 정기 재학습 파이프라인 — 평가 실패 모델은 registry ACTIVE 로 승격되지 않는다."""

from __future__ import annotations

from nexa_policy.pipeline.retrain import (
    EvalGate,
    PipelineInputs,
    PipelineStage,
    PipelineStatus,
    run_retrain_pipeline,
)


def _inputs(*, dataset_approved=True, eval_passed=True, registered: list[str] | None = None):
    reg = registered if registered is not None else []
    return PipelineInputs(
        dataset_approved=dataset_approved,
        train_fn=lambda: "model-candidate-1",
        eval_fn=lambda mid: EvalGate(
            passed=eval_passed,
            metrics={"balanced_accuracy": 0.8 if eval_passed else 0.4},
            reason="" if eval_passed else "balanced accuracy floor 미달",
        ),
        model_card_fn=lambda mid: f"card-for-{mid}",
        sign_fn=lambda mid: f"sig-{mid}",
        shadow_register_fn=lambda mid: reg.append(mid),
    )


def test_happy_path_registers_shadow_only():
    registered: list[str] = []
    outcome = run_retrain_pipeline(_inputs(registered=registered))
    assert outcome.status == PipelineStatus.REGISTERED_SHADOW
    assert outcome.model_id == "model-candidate-1"
    # SHADOW 까지만 — ACTIVE 승격 없음.
    assert registered == ["model-candidate-1"]
    assert outcome.promoted_to_active is False
    assert PipelineStage.SHADOW_REGISTER in outcome.completed_stages


def test_acceptance_eval_failure_blocks_registration():
    registered: list[str] = []
    outcome = run_retrain_pipeline(_inputs(eval_passed=False, registered=registered))
    # 평가 실패 → REJECTED, 등록(SHADOW 조차) 안 됨, ACTIVE 절대 아님.
    assert outcome.status == PipelineStatus.REJECTED
    assert registered == []
    assert outcome.promoted_to_active is False
    assert PipelineStage.SHADOW_REGISTER not in outcome.completed_stages
    assert "미달" in outcome.rejected_reason


def test_dataset_not_approved_does_not_start():
    registered: list[str] = []
    outcome = run_retrain_pipeline(_inputs(dataset_approved=False, registered=registered))
    assert outcome.status == PipelineStatus.REJECTED
    assert outcome.model_id is None
    assert registered == []
    assert PipelineStage.TRAIN not in outcome.completed_stages


def test_acceptance_no_active_promotion_stage_exists():
    # 어떤 종료 status 도 ACTIVE 가 아니다(자동 승격 stage 부재).
    assert "active" not in {s.value for s in PipelineStatus}
    outcome = run_retrain_pipeline(_inputs())
    assert outcome.to_dict()["promoted_to_active"] is False
