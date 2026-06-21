"""P12 생존분석 타이밍 모델 테스트(T001~T013) — 결정론·검열·acceptance 증명."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pytest

from nexa_policy.benchmarks.inference_latency import (
    LatencyBudget,
    benchmark,
    benchmark_sweep,
)
from nexa_policy.calibration.time import (
    apply_time_temperature,
    select_time_calibration,
)
from nexa_policy.data.labels.delay import DelayLabel
from nexa_policy.data.leakage import (
    FeatureTimestamp,
    LeakageError,
    assert_no_leakage,
    check_feature_cutoff_leakage,
)
from nexa_policy.eval.survival import (
    concordance_index,
    evaluate_survival,
    integrated_brier_score,
    survival_nll,
)
from nexa_policy.eval.tempo_slices import TempoSlice, evaluate_by_tempo
from nexa_policy.inference.multiplier_analysis import (
    analyze_multiplier,
    default_scenarios,
    per_window_speak_probability,
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


# ── T010 multiplier saturation·fairness 분석 ───────────────────────────


def test_t010_fast_conversation_is_not_over_interrupting_at_1_5x() -> None:
    """worst-case 보고: 빠른 대화(fast/close)에서 1.5x 가 과도한 끼어들기로 변하지 않는다.

    base hazard 가 이미 높은 빠른 대화는 오즈 가산이 누적 발화확률을 거의 못 올린다(포화) — 절대
    증가폭이 작다. fairness 민감 구간은 중간 tempo(발화확률 0.5 부근)다.
    """
    report = analyze_multiplier(default_scenarios(), 1.5)
    by_tempo = {o.tempo: o for o in report.outcomes}
    fast = by_tempo["fast"]
    normal = by_tempo["normal"]
    # 빠른 대화 worst-case: 1.5x 증가폭이 작다(과도 끼어들기 아님).
    assert fast.delta_speak_prob < 0.05
    # 민감 구간은 중간 tempo — fast 보다 증가폭이 크다(평균이 빠른 채널을 숨기는 게 아니라 반대).
    assert normal.delta_speak_prob > fast.delta_speak_prob
    # worst_case(절대 발화율 최대)는 fast/close 지만 base 대비 +3pp 수준.
    wc = report.worst_case
    assert wc.tempo == "fast"
    assert wc.scaled_speak_prob - wc.base_speak_prob < 0.05


def test_t010_multiplier_1_0_is_identity_and_capped() -> None:
    """multiplier 1.0 은 보정 없음(발화확률 불변), cap 으로 hazard 가 폭주하지 않는다."""
    report = analyze_multiplier(default_scenarios(), 1.0)
    for o in report.outcomes:
        assert abs(o.delta_speak_prob) < 1e-9
    # 발화확률은 [0,1].
    for o in report.outcomes:
        assert 0.0 <= o.scaled_speak_prob <= 1.0
    assert per_window_speak_probability(np.array([0.0, 0.0])) == 0.0


# ── T015 채널 tempo 조건부 timing 평가 ─────────────────────────────────


def test_t015_average_does_not_hide_fast_channel_error() -> None:
    """fast slice 만 timing 이 나쁜데 평균은 합격선이면 hides_fast_channel_error=True 로 드러난다."""
    n_quiet, n_fast = 30, 30
    # quiet: 완벽에 가까운 예측(bin 0 에 사건, hazard 가 bin 0 에 집중).
    quiet_h = np.tile(np.array([0.95, 0.5, 0.5, 0.5]), (n_quiet, 1))
    quiet_bin = np.zeros(n_quiet, dtype=int)
    # fast: 사건은 늦은 bin 인데 모델은 이른 bin 예측(나쁜 timing).
    fast_h = np.tile(np.array([0.95, 0.5, 0.5, 0.5]), (n_fast, 1))
    fast_bin = np.full(n_fast, 3, dtype=int)
    hazard = np.concatenate([quiet_h, fast_h])
    event_bin = np.concatenate([quiet_bin, fast_bin])
    event_observed = np.ones(n_quiet + n_fast, dtype=int)
    tempo = np.array([TempoSlice.QUIET.value] * n_quiet + [TempoSlice.FAST.value] * n_fast)
    report = evaluate_by_tempo(
        hazard, event_bin, event_observed, tempo, overall_pass_brier=1.0, hide_gap=0.05
    )
    # slice 가 둘 다 잡힌다.
    assert TempoSlice.QUIET in report.per_slice
    assert TempoSlice.FAST in report.per_slice
    # fast slice 의 Brier 가 quiet 보다 나쁘다(평균이 숨길 수 있는 구조).
    assert (
        report.per_slice[TempoSlice.FAST].integrated_brier
        > report.per_slice[TempoSlice.QUIET].integrated_brier
    )
    # 평균이 합격선(1.0) 안인데 worst slice 가 gap 이상 나쁘면 은닉 플래그가 켜진다.
    assert report.hides_fast_channel_error is True


# ── T016 시간 feature 미래 누출 감사 ───────────────────────────────────


def test_t016_feature_computed_after_cutoff_is_leakage() -> None:
    """reply 도착 후 계산된 tempo·finalize reason 이 feature 면 미래 누출로 탐지·실패한다."""

    @dataclass(frozen=True)
    class Row:
        cutoff_ms: int
        tempo_at_ms: int  # reply 뒤 계산된 tempo.
        relationship_at_ms: int  # cutoff 전 feature(정상).

    rows = [
        Row(cutoff_ms=1000, tempo_at_ms=1500, relationship_at_ms=800),  # tempo 누출.
        Row(cutoff_ms=2000, tempo_at_ms=1800, relationship_at_ms=1900),  # 정상.
    ]
    report = check_feature_cutoff_leakage(
        rows,
        cutoff_of=lambda r: r.cutoff_ms,
        features=[
            FeatureTimestamp(computed_at_of=lambda r: r.tempo_at_ms, name="tempo"),
            FeatureTimestamp(
                computed_at_of=lambda r: r.relationship_at_ms, name="relationship"
            ),
        ],
    )
    assert report.ok is False
    assert any(v.kind == "feature_cutoff" and "tempo" in v.detail for v in report.violations)
    # row 1 의 relationship 은 cutoff 전이라 위반 아님.
    assert not any("relationship" in v.detail for v in report.violations)
    with pytest.raises(LeakageError):
        assert_no_leakage(report)


def test_t016_all_features_before_cutoff_passes() -> None:
    """모든 feature 가 cutoff 전이면 누출 없음(정상 학습 데이터)."""

    @dataclass(frozen=True)
    class Row:
        cutoff_ms: int
        feat_at_ms: int

    rows = [Row(cutoff_ms=1000, feat_at_ms=900), Row(cutoff_ms=2000, feat_at_ms=1500)]
    report = check_feature_cutoff_leakage(
        rows,
        cutoff_of=lambda r: r.cutoff_ms,
        features=[FeatureTimestamp(computed_at_of=lambda r: r.feat_at_ms, name="f")],
    )
    assert report.ok is True
    assert_no_leakage(report)  # 예외 없음.


# ── T017 추론 latency benchmark ────────────────────────────────────────


def test_t017_latency_percentiles_and_budget_gate() -> None:
    """latency percentile 산출 + GLM 호출보다 먼저 끝나는 목표(budget gate)를 결정론으로 검증."""
    model = DiscreteHazardModel.build(in_dim=6, n_bins=4, seed=12)

    def make_batch(bs: int) -> np.ndarray:
        return np.random.default_rng(1).random((bs, 6))

    # 결정론 가짜 시계: 호출마다 1ms 씩 증가(percentile 로직만 검증, 실측 시간 비의존).
    ticks = iter(float(i) / 1000.0 for i in range(10_000))
    stats = benchmark(
        model.hazard, make_batch, label="cpu-numpy", batch_size=8, runs=20, warmup=2,
        clock=lambda: next(ticks),
    )
    assert stats.p50_ms <= stats.p95_ms <= stats.p99_ms
    # 각 호출 1ms 라 p95 ≈ 1ms — GLM 예산(800ms × 0.1 = 80ms) 안에서 끝난다(목표 충족).
    budget = LatencyBudget(glm_call_p95_ms=800.0, policy_fraction=0.1)
    assert budget.policy_p95_target_ms == 80.0
    assert stats.within_budget(budget) is True


def test_t017_sweep_covers_batch_sizes() -> None:
    model = DiscreteHazardModel.build(in_dim=4, n_bins=3, seed=13)
    ticks = iter(float(i) / 1000.0 for i in range(100_000))
    stats = benchmark_sweep(
        model.hazard,
        lambda bs: np.zeros((bs, 4)),
        label="cpu-numpy",
        batch_sizes=(1, 8, 32),
        runs=10,
        clock=lambda: next(ticks),
    )
    assert [s.batch_size for s in stats] == [1, 8, 32]
    assert all(s.runs == 10 for s in stats)
