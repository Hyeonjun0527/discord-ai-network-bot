"""P12 생존분석 타이밍 모델 테스트(T001~T013) — 결정론·검열·acceptance 증명."""

from __future__ import annotations

import numpy as np

from nexa_policy.calibration.time import (
    apply_time_temperature,
    select_time_calibration,
)
from nexa_policy.data.labels.delay import DelayLabel
from nexa_policy.eval.survival import (
    concordance_index,
    evaluate_survival,
    integrated_brier_score,
    survival_nll,
)
from nexa_policy.inference.sampler import (
    has_delay_action_mismatch,
    sample_joint,
)
from nexa_policy.inference.talkativeness import (
    HAZARD_CAP,
    scale_action_hazards,
    scale_hazard,
)
from nexa_policy.models.burst_timing import (
    MAX_INTER_BUBBLE_S,
    InterMessageModel,
    plan_burst,
)
from nexa_policy.models.discrete_hazard import (
    DiscreteHazardModel,
    pmf_plus_survival_sum,
    survival_from_hazard,
    time_bin_index,
)
from nexa_policy.models.heads import ScheduleCancelHead
from nexa_policy.models.marked_point_process import MarkedEvent, evaluate_poc
from nexa_policy.models.neural_survival import (
    NeuralSurvivalModel,
    quantify_mismatch,
)
from nexa_policy.models.survival_baselines import CoxLinearHazard, ExponentialSurvival
from nexa_policy.time.censoring import (
    CensorReason,
    SurvivalSample,
    from_delay_label,
    to_survival_arrays,
)
from nexa_policy.time.origin import (
    MS_PER_SECOND,
    OpportunityKind,
    TimeOriginError,
    resolve_origin_ms,
    to_relative_seconds,
)

# ── T001 시간 원점 계약 ────────────────────────────────────────────────


def test_t001_origin_is_latest_opportunity() -> None:
    """동시 구간 후보 중 가장 최근(늦은) opportunity 시각이 t=0."""
    times = {
        OpportunityKind.BURST_FINALIZE: 1000,
        OpportunityKind.SCENE_UPDATE: 1500,
        OpportunityKind.DIRECT_ADDRESS: 1200,
    }
    assert resolve_origin_ms(times) == 1500


def test_t001_relative_seconds_unit_is_seconds() -> None:
    """t = (event - origin)/1000 — 초 단위."""
    t = to_relative_seconds(event_time_ms=3000, origin_ms=1000)
    assert t == 2.0
    assert MS_PER_SECOND == 1000


def test_t001_rejects_event_before_origin() -> None:
    """원점 이전 사건은 시간축 사건이 아니라 거부."""
    try:
        to_relative_seconds(event_time_ms=500, origin_ms=1000)
    except TimeOriginError:
        return
    raise AssertionError("원점 이전 사건은 거부돼야 한다.")


# ── T002 right-censoring ───────────────────────────────────────────────


def test_t002_censored_not_learned_as_never() -> None:
    """검열 3종은 우중도절단(event_observed=False, is_true_never=False) — never 와 구분."""
    for reason in (
        CensorReason.SESSION_END,
        CensorReason.OBSERVATION_WINDOW_END,
        CensorReason.CONSENT_WITHDRAWAL,
    ):
        s = SurvivalSample(duration_s=10.0, event_observed=False, reason=reason)
        assert s.is_censored is True
        assert s.is_true_never is False


def test_t002_true_never_distinct_from_censored() -> None:
    """진짜 never 는 검열이 아니다(충분히 봤는데 안 함)."""
    s = SurvivalSample(duration_s=60.0, event_observed=False, reason=CensorReason.TRUE_NEVER)
    assert s.is_true_never is True
    assert s.is_censored is False


def test_t002_from_delay_label_preserves_distinction() -> None:
    """DelayLabel → SurvivalSample 변환이 검열↔never 구분을 보존한다."""
    observed = from_delay_label(
        DelayLabel(delay_ms=2000, censored=False, is_never=False), observation_limit_s=60.0
    )
    assert observed.event_observed is True
    assert observed.duration_s == 2.0

    censored = from_delay_label(
        DelayLabel(delay_ms=None, censored=True, is_never=False),
        observation_limit_s=60.0,
        censor_reason=CensorReason.CONSENT_WITHDRAWAL,
    )
    assert censored.is_censored is True

    never = from_delay_label(
        DelayLabel(delay_ms=None, censored=False, is_never=True), observation_limit_s=60.0
    )
    assert never.is_true_never is True


def test_t002_to_arrays_durations_are_lower_bounds() -> None:
    """검열 표본의 duration 은 관찰 한계(하한)로만 들어간다(never 강제 아님)."""
    samples = [
        SurvivalSample(duration_s=2.0, event_observed=True, reason=None),
        SurvivalSample(duration_s=60.0, event_observed=False, reason=CensorReason.SESSION_END),
    ]
    durations, events = to_survival_arrays(samples)
    assert list(events) == [1, 0]
    assert list(durations) == [2.0, 60.0]


# ── T003 discrete hazard baseline ──────────────────────────────────────


def test_t003_survival_monotone_decreasing() -> None:
    """survival S_k 는 단조 비증가."""
    model = DiscreteHazardModel.build(in_dim=6, n_bins=4, seed=1)
    x = np.random.default_rng(0).random((10, 6))
    surv = survival_from_hazard(model.hazard(x))
    assert np.all(np.diff(surv, axis=-1) <= 1e-12)


def test_t003_pmf_plus_survival_sums_to_one() -> None:
    """sum_k f_k + S_last = 1 — 수학적으로 유효."""
    model = DiscreteHazardModel.build(in_dim=6, n_bins=5, seed=2)
    x = np.random.default_rng(1).random((8, 6))
    totals = pmf_plus_survival_sum(model.hazard(x))
    assert np.allclose(totals, 1.0)


def test_t003_time_bin_index() -> None:
    edges = (2.0, 10.0, 60.0)
    assert time_bin_index(1.0, edges) == 0
    assert time_bin_index(5.0, edges) == 1
    assert time_bin_index(30.0, edges) == 2
    assert time_bin_index(120.0, edges) == 3


# ── T004 survival metric ───────────────────────────────────────────────


def test_t004_concordance_perfect_ordering() -> None:
    """risk 가 더 일찍 사건난 쪽이 크면 C-index=1."""
    risk = np.array([3.0, 2.0, 1.0])
    duration = np.array([1.0, 2.0, 3.0])
    events = np.array([1, 1, 1])
    assert concordance_index(risk, duration, events) == 1.0


def test_t004_metrics_support_censoring_and_delay_accuracy() -> None:
    """생존 metric 이 검열을 지원하고 delay accuracy 를 함께 보고한다."""
    model = DiscreteHazardModel.build(in_dim=6, n_bins=4, seed=3)
    x = np.random.default_rng(2).random((20, 6))
    hazard = model.hazard(x)
    event_bin = np.random.default_rng(3).integers(0, 4, size=20)
    event_observed = np.random.default_rng(4).integers(0, 2, size=20)
    metrics = evaluate_survival(hazard, event_bin, event_observed)
    d = metrics.to_dict()
    assert "delay_accuracy" in d
    assert d["nll"] >= 0.0
    assert 0.0 <= d["concordance"] <= 1.0
    # NLL·IB 가 유한.
    assert np.isfinite(survival_nll(hazard, event_bin, event_observed))
    assert np.isfinite(integrated_brier_score(hazard, event_bin, event_observed))


# ── T005 Cox/parametric baseline ───────────────────────────────────────


def test_t005_exponential_survival_monotone() -> None:
    duration = np.array([1.0, 2.0, 5.0, 10.0])
    events = np.array([1, 1, 0, 1])
    model = ExponentialSurvival.fit(duration, events)
    t = np.array([0.0, 1.0, 2.0, 5.0])
    surv = model.survival(t)
    assert np.all(np.diff(surv) <= 0.0)
    assert model.rate > 0.0


def test_t005_cox_ranks_risk_by_covariate() -> None:
    """공변량이 클수록 일찍 사건나게 만든 데이터에서 Cox 가 위험 순서를 학습한다."""
    gen = np.random.default_rng(5)
    n = 40
    x = gen.random((n, 1))
    # 큰 x → 짧은 duration.
    duration = (1.0 - x[:, 0]) * 10.0 + 0.1
    events = np.ones(n, dtype=int)
    cox = CoxLinearHazard.fit(x, duration, events)
    risk = cox.log_partial_hazard(x)
    c = concordance_index(risk, duration, events)
    assert c > 0.8  # 단순 baseline 이 신호를 잡는다.


# ── T006 neural survival ───────────────────────────────────────────────


def test_t006_action_time_mismatch_quantified() -> None:
    model = NeuralSurvivalModel.build(in_dim=4, n_actions=5, n_bins=4, seed=6)
    seqs = [np.random.default_rng(i).random((3, 4)) for i in range(12)]
    hidden = model.encode_batch(seqs)
    mismatch = quantify_mismatch(model, hidden, action_index={"react": 2, "speak": 3})
    assert {m.action for m in mismatch} == {"react", "speak"}
    for m in mismatch:
        assert np.isfinite(m.mean_abs_gap)
        assert -1.0 <= m.correlation <= 1.0


# ── T007 marked temporal point process PoC ─────────────────────────────


def test_t007_mtpp_poc_reports_likelihood_and_calibration() -> None:
    events = [
        MarkedEvent(time_s=1.0, mark=1),
        MarkedEvent(time_s=2.0, mark=1),
        MarkedEvent(time_s=5.0, mark=0),
    ]
    result = evaluate_poc(events, horizon_s=10.0)
    d = result.to_dict()
    assert d["base_intensity"] == 3 / 10.0
    assert np.isfinite(d["log_likelihood"])
    assert d["mark_brier"] >= 0.0


# ── T008 action-time joint sampler ─────────────────────────────────────


def test_t008_no_speak_delay_when_speak_prob_zero() -> None:
    """SPEAK 확률 0 이면 SPEAK delay 가 절대 나오지 않는다."""
    classes = ("ignore", "wait", "react", "speak", "cancel")
    n = 30
    proba = np.zeros((n, 5))
    proba[:, 0] = 1.0  # 전부 IGNORE, SPEAK=0.
    hazards = {
        "react": np.full((n, 4), 0.3),
        "speak": np.full((n, 4), 0.3),
    }
    samples = sample_joint(
        action_proba=proba, action_classes=classes, time_hazards=hazards, seed=7
    )
    assert all(s.action == "ignore" and s.delay_bin is None for s in samples)
    assert has_delay_action_mismatch(samples) is False


def test_t008_speak_gets_delay_from_speak_head() -> None:
    classes = ("ignore", "wait", "react", "speak", "cancel")
    n = 20
    proba = np.zeros((n, 5))
    proba[:, 3] = 1.0  # 전부 SPEAK.
    hazards = {
        "react": np.full((n, 4), 0.3),
        "speak": np.full((n, 4), 0.5),
    }
    samples = sample_joint(
        action_proba=proba, action_classes=classes, time_hazards=hazards, seed=8
    )
    assert all(s.action == "speak" and s.delay_bin is not None for s in samples)
    assert has_delay_action_mismatch(samples) is False


def test_t008_deterministic() -> None:
    classes = ("ignore", "wait", "react", "speak", "cancel")
    proba = np.full((10, 5), 0.2)
    hazards = {"react": np.full((10, 4), 0.3), "speak": np.full((10, 4), 0.3)}
    a = sample_joint(action_proba=proba, action_classes=classes, time_hazards=hazards, seed=9)
    b = sample_joint(action_proba=proba, action_classes=classes, time_hazards=hazards, seed=9)
    assert [(s.action, s.delay_bin) for s in a] == [(s.action, s.delay_bin) for s in b]


# ── T009 talkativeness hazard scaling (ml) ─────────────────────────────


def test_t009_hazard_scaling_order_preserved() -> None:
    """0.5/1.0/1.5/2.0 에서 hazard 순서가 보존된다(ml, central 과 동일 수식)."""
    base = np.array([0.3])
    h = [float(scale_hazard(base, m)[0]) for m in (0.5, 1.0, 1.5, 2.0)]
    assert h[0] < h[1] < h[2] < h[3]
    assert abs(h[1] - 0.3) < 1e-9  # 1.0 = 보정 없음.


def test_t009_hazard_capped() -> None:
    scaled = scale_hazard(np.array([0.99]), 2.0)
    assert scaled[0] <= HAZARD_CAP


def test_t009_only_timed_actions_scaled() -> None:
    hazards = {
        "speak": np.full((3, 4), 0.3),
        "react": np.full((3, 4), 0.3),
        "ignore": np.full((3, 4), 0.3),
    }
    scaled = scale_action_hazards(hazards, 2.0)
    assert np.all(scaled["speak"] > 0.3)
    assert np.all(scaled["react"] > 0.3)
    assert np.array_equal(scaled["ignore"], hazards["ignore"])


# ── T011 time calibration ──────────────────────────────────────────────


def test_t011_calibration_not_applied_when_worse() -> None:
    """integrated Brier 가 악화되면 보정을 적용하지 않고 원 hazard 반환."""
    model = DiscreteHazardModel.build(in_dim=6, n_bins=4, seed=10)
    x = np.random.default_rng(5).random((30, 6))
    hazard = model.hazard(x)
    event_bin = np.random.default_rng(6).integers(0, 4, size=30)
    event_observed = np.ones(30, dtype=int)
    decision, out = select_time_calibration(
        val_hazard=hazard,
        val_event_bin=event_bin,
        val_event_observed=event_observed,
        test_hazard=hazard,
        test_event_bin=event_bin,
        test_event_observed=event_observed,
    )
    if not decision.applied:
        assert np.array_equal(out, hazard)
    else:
        assert decision.integrated_brier_after <= decision.integrated_brier_before + 1e-9


def test_t011_temperature_preserves_hazard_range() -> None:
    hazard = np.array([[0.1, 0.5, 0.9, 0.3]])
    out = apply_time_temperature(hazard, 2.0)
    assert np.all((out > 0.0) & (out < 1.0))


# ── T012 burst inter-message timing ────────────────────────────────────


def test_t012_plan_is_schedule_not_sleep() -> None:
    """burst plan 은 상대 offset(초) 리스트 — 비감소·cap·blocking sleep 아님."""
    model = InterMessageModel.fit(np.array([0.5, 0.8, 1.0]))
    plan = plan_burst(model, n_bubbles=3)
    assert plan.n_bubbles == 3
    assert list(plan.offsets_s) == sorted(plan.offsets_s)
    # 간격이 MAX cap 이하.
    diffs = np.diff(plan.offsets_s)
    assert np.all(diffs <= MAX_INTER_BUBBLE_S + 1e-9)


def test_t012_single_bubble_plan() -> None:
    model = InterMessageModel.fit(np.array([]))
    plan = plan_burst(model, n_bubbles=1)
    assert plan.offsets_s == (0.0,)


# ── T013 schedule-cancel head ──────────────────────────────────────────


def test_t013_hard_invalidation_overrides_learned_head() -> None:
    """하드 contextVersion invalidation 은 학습 head 보다 우선(무조건 취소)."""
    head = ScheduleCancelHead.build(in_dim=4, seed=11)
    hidden = np.random.default_rng(7).random((5, 4))
    hard = np.array([True, False, True, False, True])
    decision = head.should_cancel(hidden, hard, threshold=2.0)  # 학습 임계 매우 높음 → 학습은 절대 취소 안 함.
    # 하드 무효화된 인덱스는 학습 확률과 무관하게 취소.
    assert bool(decision[0]) is True
    assert bool(decision[2]) is True
    assert bool(decision[4]) is True
    # 하드 무효화 아니고 학습 임계 못 넘으면 취소 안 함.
    assert bool(decision[1]) is False
    assert bool(decision[3]) is False
