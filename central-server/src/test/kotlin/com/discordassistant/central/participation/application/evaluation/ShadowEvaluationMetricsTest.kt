package com.discordassistant.central.participation.application.evaluation

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * shadow 예측 평가 약지도 라벨러·proxy·메트릭 acceptance 단위 테스트(NEXA-P09-T014~T019). 모두 결정론·재현
 * (seed/랜덤 없음 — 같은 입력=같은 결과).
 */
class ShadowEvaluationMetricsTest {
    private val t0 = Instant.parse("2026-06-22T00:00:00Z")

    private fun event(
        pseudonym: String,
        act: HumanAct,
        afterSeconds: Long,
    ) = HumanActionEvent(pseudonym, act, t0.plusSeconds(afterSeconds))

    // ── T014 weak SPEAK/SILENT labeler ──────────────────────────────────────────

    @Test
    fun `T014 — weak label 은 학습 정답이 아니다(isWeak true, confidence 1_0 미만)`() {
        val events = listOf(event("m-1", HumanAct.REPLY, 2))
        val outcome = HumanNextActionMatcher.match(CounterfactualObservation.build(t0, events), events)
        val label = WeakSpeakLabeler.label(outcome)
        assertThat(label.label).isEqualTo(SpeakLabel.SPEAK)
        assertThat(label.isWeak).isTrue()
        assertThat(label.confidence).isLessThan(1.0)
        assertThat(label.rationale).isNotBlank()
    }

    @Test
    fun `T014 — 아무도 응답 안 하면 SILENT 약한 label`() {
        val outcome = HumanNextActionMatcher.match(CounterfactualObservation.build(t0, emptyList()), emptyList())
        val label = WeakSpeakLabeler.label(outcome)
        assertThat(label.label).isEqualTo(SpeakLabel.SILENT)
        assertThat(label.confidence).isLessThan(1.0)
    }

    @Test
    fun `T014 — 여러 명 응답은 모호해 더 낮은 confidence`() {
        val events = listOf(event("m-1", HumanAct.REPLY, 1), event("m-2", HumanAct.REACT, 1))
        val outcome = HumanNextActionMatcher.match(CounterfactualObservation.build(t0, events), events)
        val ambiguous = WeakSpeakLabeler.label(outcome)
        val clearEvents = listOf(event("m-1", HumanAct.REPLY, 1))
        val clear = WeakSpeakLabeler.label(HumanNextActionMatcher.match(CounterfactualObservation.build(t0, clearEvents), clearEvents))
        assertThat(ambiguous.label).isEqualTo(SpeakLabel.SPEAK)
        assertThat(ambiguous.confidence).isLessThan(clear.confidence)
    }

    @Test
    fun `T014 — 결정론 - 같은 입력이면 같은 label`() {
        val events = listOf(event("m-1", HumanAct.REPLY, 2))
        val a = WeakSpeakLabeler.label(HumanNextActionMatcher.match(CounterfactualObservation.build(t0, events), events))
        val b = WeakSpeakLabeler.label(HumanNextActionMatcher.match(CounterfactualObservation.build(t0, events), events))
        assertThat(a).isEqualTo(b)
    }

    // ── T015 False Interruption / T016 Missed Intervention proxy ────────────────

    @Test
    fun `T015 — SPEAK 예측인데 즉시 인간 응답이면 False Interruption`() {
        // 1초 안에 인간이 이미 응답 → 끼어들 뻔.
        val obs = CounterfactualObservation.build(t0, listOf(event("m-1", HumanAct.REPLY, 1)))
        val r = InterventionProxies.classify(SocialActionKind.SPEAK, obs)
        assertThat(r.falseInterruption).isTrue()
        assertThat(r.missedIntervention).isFalse()
    }

    @Test
    fun `T015 — SPEAK 예측이라도 즉시 응답 없으면 False Interruption 아님`() {
        // 25초 뒤(즉시 창 밖) → 끼어들 뻔 아님.
        val obs = CounterfactualObservation.build(t0, listOf(event("m-1", HumanAct.REPLY, 25)))
        val r = InterventionProxies.classify(SocialActionKind.SPEAK, obs)
        assertThat(r.falseInterruption).isFalse()
    }

    @Test
    fun `T016 — IGNORE 예측인데 관찰 창에 인간 응답이면 Missed Intervention`() {
        val obs = CounterfactualObservation.build(t0, listOf(event("m-1", HumanAct.REPLY, 5)))
        val r = InterventionProxies.classify(SocialActionKind.IGNORE, obs)
        assertThat(r.missedIntervention).isTrue()
        assertThat(r.falseInterruption).isFalse()
    }

    @Test
    fun `T016 — IGNORE 예측이고 아무도 응답 안 하면 Missed 아님(관찰 신호만)`() {
        val obs = CounterfactualObservation.build(t0, emptyList())
        val r = InterventionProxies.classify(SocialActionKind.IGNORE, obs)
        assertThat(r.missedIntervention).isFalse()
    }

    @Test
    fun `T015 T016 — 집계 비율(FIR MIR), 표본 0 이면 null`() {
        val empty = InterventionProxies.aggregate(emptyList())
        assertThat(empty.falseInterruptionRate).isNull()
        assertThat(empty.missedInterventionRate).isNull()

        val samples =
            listOf(
                SocialActionKind.SPEAK to CounterfactualObservation.build(t0, listOf(event("m-1", HumanAct.REPLY, 1))), // FI
                SocialActionKind.IGNORE to CounterfactualObservation.build(t0, listOf(event("m-2", HumanAct.REPLY, 5))), // MI
                SocialActionKind.IGNORE to CounterfactualObservation.build(t0, emptyList()), // neither
                SocialActionKind.SPEAK to CounterfactualObservation.build(t0, emptyList()), // neither
            )
        val rates = InterventionProxies.aggregate(samples)
        assertThat(rates.sampleCount).isEqualTo(4)
        assertThat(rates.falseInterruptionRate).isEqualTo(0.25)
        assertThat(rates.missedInterventionRate).isEqualTo(0.25)
    }

    // ── T017 Brier score / calibration ──────────────────────────────────────────

    @Test
    fun `T017 — Brier score sanity - 완벽 예측은 0, 최악은 1`() {
        val perfect =
            listOf(
                CalibrationSample(1.0, SpeakLabel.SPEAK),
                CalibrationSample(0.0, SpeakLabel.SILENT),
            )
        assertThat(CalibrationMetrics.compute(perfect).brierScore).isCloseTo(0.0, within(1e-9))

        val worst =
            listOf(
                CalibrationSample(0.0, SpeakLabel.SPEAK),
                CalibrationSample(1.0, SpeakLabel.SILENT),
            )
        assertThat(CalibrationMetrics.compute(worst).brierScore).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `T017 — 표본 수와 bin confidence interval 이 함께 나온다`() {
        // 0.5 확률 4표본 중 2개 SPEAK → 잘 보정된 bin.
        val samples =
            listOf(
                CalibrationSample(0.5, SpeakLabel.SPEAK),
                CalibrationSample(0.5, SpeakLabel.SPEAK),
                CalibrationSample(0.5, SpeakLabel.SILENT),
                CalibrationSample(0.5, SpeakLabel.SILENT),
            )
        val report = CalibrationMetrics.compute(samples)
        assertThat(report.sampleCount).isEqualTo(4)
        val bin = report.bins.single()
        assertThat(bin.sampleCount).isEqualTo(4)
        assertThat(bin.observedRate).isEqualTo(0.5)
        // 신뢰구간이 관찰 발화율을 감싸고 [0,1] 안.
        assertThat(bin.confidenceLow).isLessThanOrEqualTo(bin.observedRate)
        assertThat(bin.confidenceHigh).isGreaterThanOrEqualTo(bin.observedRate)
        assertThat(bin.confidenceLow).isGreaterThanOrEqualTo(0.0)
        assertThat(bin.confidenceHigh).isLessThanOrEqualTo(1.0)
        // ECE: 평균확률 0.5 vs 관찰 0.5 → 0.
        assertThat(report.ece).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `T017 — 표본 0 이면 brier ECE null`() {
        val report = CalibrationMetrics.compute(emptyList())
        assertThat(report.sampleCount).isEqualTo(0)
        assertThat(report.brierScore).isNull()
        assertThat(report.ece).isNull()
        assertThat(report.bins).isEmpty()
    }

    // ── T018 distribution comparison ────────────────────────────────────────────

    @Test
    fun `T018 — 외부 label 은 저카디널리티 버킷뿐(원본 ID 없음)`() {
        val policy =
            ActivityDistribution(
                speakRate = 0.4,
                medianDelay = Duration.ofSeconds(3),
                actionMix = mapOf(SocialActionKind.SPEAK to 0.4, SocialActionKind.IGNORE to 0.6),
            )
        val baseline =
            ActivityDistribution(
                speakRate = 0.2,
                medianDelay = Duration.ofSeconds(5),
                actionMix = mapOf(SocialActionKind.SPEAK to 0.2, SocialActionKind.IGNORE to 0.8),
            )
        val cmp = DistributionComparison.compare(CohortBucket.SMALL_ACTIVE, policy, baseline)
        assertThat(cmp.cohortBucket).isEqualTo(CohortBucket.SMALL_ACTIVE)
        assertThat(cmp.speakRateDelta).isCloseTo(0.2, within(1e-9))
        assertThat(cmp.medianDelayDelta).isEqualTo(Duration.ofSeconds(-2))
        assertThat(cmp.actionMixL1Distance).isCloseTo(0.4, within(1e-9))
    }

    // ── T019 mention non-response audit ─────────────────────────────────────────

    @Test
    fun `T019 — 인간 무응답률이 0보다 크면 멘션=응답 가정을 반박`() {
        val report = MentionResponseAudit.audit(mentionCount = 10, humanNonResponseCount = 3, policyIgnoreCount = 2)
        assertThat(report.humanNonResponseRate).isCloseTo(0.3, within(1e-9))
        assertThat(report.refutesMentionEqualsResponse).isTrue()
        // 정책 무시율(0.2)이 인간(0.3)보다 낮으니 경보 아님.
        assertThat(report.exceedsSafetyMargin).isFalse()
    }

    @Test
    fun `T019 — 정책 멘션 무시율이 인간보다 마진 이상 높으면 안전 회귀 경보`() {
        val report = MentionResponseAudit.audit(mentionCount = 10, humanNonResponseCount = 1, policyIgnoreCount = 8, safetyMargin = 0.2)
        // 정책 0.8 − 인간 0.1 = 0.7 > 0.2 → 경보.
        assertThat(report.exceedsSafetyMargin).isTrue()
    }

    @Test
    fun `T019 — 인간 무응답 0이면 멘션=응답 가정을 확인(반박 아님)`() {
        val report = MentionResponseAudit.audit(mentionCount = 5, humanNonResponseCount = 0, policyIgnoreCount = 0)
        assertThat(report.refutesMentionEqualsResponse).isFalse()
    }

    @Test
    fun `T019 — 표본 0이면 단정하지 않음(null)`() {
        val report = MentionResponseAudit.audit(mentionCount = 0, humanNonResponseCount = 0, policyIgnoreCount = 0)
        assertThat(report.humanNonResponseRate).isNull()
        assertThat(report.refutesMentionEqualsResponse).isNull()
        assertThat(report.exceedsSafetyMargin).isNull()
    }
}
