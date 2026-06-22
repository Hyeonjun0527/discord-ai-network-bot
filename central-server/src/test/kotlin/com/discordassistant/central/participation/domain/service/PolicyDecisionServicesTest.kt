package com.discordassistant.central.participation.domain.service

import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.config.TalkativenessMultiplier
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayBucket
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import com.discordassistant.central.participation.domain.model.decision.TargetCandidate
import com.discordassistant.central.participation.domain.model.decision.TargetKind
import com.discordassistant.central.participation.domain.model.decision.TargetRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P08-T019/T020/T021 정책 도메인 서비스 acceptance 단위 테스트. */
class PolicyDecisionServicesTest {
    // ── T019 SeededPolicySampler ──────────────────────────────────────────────
    @Test
    fun `T019 acceptance — 같은 입력과 seed 면 같은 결과(결정론)`() {
        val dist = sampleDistribution()
        val a = SeededPolicySampler.sample(dist, seed = 12345L)
        val b = SeededPolicySampler.sample(dist, seed = 12345L)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `T019 acceptance — 분포 밖 값이 나오지 않는다`() {
        val dist = sampleDistribution()
        // 여러 seed 로 뽑아도 action 은 분포 키 중에서만, target 은 후보(또는 none) 중에서만.
        val allowedActions = dist.actionWeights.keys
        val allowedTargets =
            dist.targetDistribution.candidates
                .map { it.target }
                .toSet()
        repeat(200) { i ->
            val out = SeededPolicySampler.sample(dist, seed = i.toLong())
            assertThat(out.action).isIn(allowedActions)
            out.target?.let { assertThat(it).isIn(allowedTargets) }
            out.socialAct?.let { assertThat(it).isIn(dist.socialActWeights.keys) }
        }
    }

    @Test
    fun `T019 — 빈 socialAct 면 socialAct 는 null(UNKNOWN 으로 강제하지 않음)`() {
        val dist = sampleDistribution().copy(socialActWeights = emptyMap())
        assertThat(SeededPolicySampler.sample(dist, 1L).socialAct).isNull()
    }

    // ── T020 PolicyCalibration ────────────────────────────────────────────────
    @Test
    fun `T020 acceptance — 하드 보정이 raw 를 덮으면 record 에 남는다`() {
        val raw = sampleDistribution()
        val result =
            PolicyCalibration.calibrate(
                raw = raw,
                modelTemperature = 1.0,
                talkativeness = TalkativenessMultiplier(2.0), // 발화 쪽으로 보정.
            )
        // 덮어쓰기 발생 + record 에 raw vs calibrated 가 남는다.
        assertThat(result.record.overrodeRawProbability).isTrue()
        assertThat(result.record.calibratedSpeakProbability)
            .isGreaterThan(result.record.rawSpeakProbability)
        // 분포는 여전히 합 1.0(ActionDistribution init 가 보장).
        assertThat(
            result.calibrated.actionWeights.values
                .sum(),
        ).isCloseTo(1.0, within())
    }

    @Test
    fun `T020 — neutral 보정은 raw 를 바꾸지 않는다`() {
        val raw = sampleDistribution()
        val result =
            PolicyCalibration.calibrate(raw, modelTemperature = 1.0, talkativeness = TalkativenessMultiplier.NEUTRAL)
        assertThat(result.record.overrodeRawProbability).isFalse()
    }

    // ── T021 PolicySafetyConstraint ───────────────────────────────────────────
    @Test
    fun `T021 acceptance — 모델 SPEAK 확률이 높아도 mute 면 후처리가 제거한다`() {
        val raw = sampleDistribution() // SPEAK 0.6
        val result =
            PolicySafetyConstraint.apply(
                raw,
                SafetyConstraintInput(
                    consentGranted = true,
                    channelMuted = true, // mute → REACT/SPEAK 제거.
                    hasSendPermission = true,
                    killSwitchEngaged = false,
                    nexaShare = 0.0,
                    shareCap = 1.0,
                ),
            )
        assertThat(result.removedKinds).contains(SocialActionKind.SPEAK)
        assertThat(result.constrained.actionWeights).doesNotContainKey(SocialActionKind.SPEAK)
        assertThat(
            result.constrained.actionWeights.values
                .sum(),
        ).isCloseTo(1.0, within())
    }

    @Test
    fun `T021 — 비-침묵만 있는데 모두 막히면 IGNORE 로 접힌다`() {
        // REACT/SPEAK 만 가중(침묵 행동 없음) — 동의 없으면 둘 다 제거돼 남는 게 없다.
        val raw =
            sampleDistribution().withActionWeights(
                mapOf(SocialActionKind.REACT to 0.4, SocialActionKind.SPEAK to 0.6),
            )
        val result =
            PolicySafetyConstraint.apply(
                raw,
                SafetyConstraintInput(
                    consentGranted = false, // 동의 없음 → 비-침묵 전부 제거.
                    channelMuted = false,
                    hasSendPermission = true,
                    killSwitchEngaged = false,
                    nexaShare = 0.0,
                    shareCap = 1.0,
                ),
            )
        assertThat(result.constrained.actionWeights)
            .hasSize(1)
            .containsEntry(SocialActionKind.IGNORE, 1.0)
    }

    @Test
    fun `T021 — 동의 없어도 침묵(WAIT)은 살아남는다`() {
        val raw = sampleDistribution() // IGNORE/WAIT/REACT/SPEAK
        val result =
            PolicySafetyConstraint.apply(
                raw,
                SafetyConstraintInput(
                    consentGranted = false,
                    channelMuted = false,
                    hasSendPermission = true,
                    killSwitchEngaged = false,
                    nexaShare = 0.0,
                    shareCap = 1.0,
                ),
            )
        // 침묵 행동(IGNORE·WAIT)만 남고 비-침묵은 제거.
        assertThat(result.constrained.actionWeights.keys)
            .containsExactlyInAnyOrder(SocialActionKind.IGNORE, SocialActionKind.WAIT)
        assertThat(result.removedKinds).contains(SocialActionKind.REACT, SocialActionKind.SPEAK)
    }

    @Test
    fun `T021 — share cap 초과면 SPEAK 만 막히고 REACT 는 남는다`() {
        val raw = sampleDistribution() // IGNORE/WAIT/REACT/SPEAK
        val result =
            PolicySafetyConstraint.apply(
                raw,
                SafetyConstraintInput(
                    consentGranted = true,
                    channelMuted = false,
                    hasSendPermission = true,
                    killSwitchEngaged = false,
                    nexaShare = 0.9,
                    shareCap = 0.8, // 초과 → SPEAK 차단.
                ),
            )
        assertThat(result.removedKinds).containsExactly(SocialActionKind.SPEAK)
        assertThat(result.constrained.actionWeights).containsKey(SocialActionKind.REACT)
    }

    private fun within() =
        org.assertj.core.api.Assertions
            .within(1e-9)

    private fun sampleDistribution(): ActionDistribution =
        ActionDistribution(
            actionWeights =
                mapOf(
                    SocialActionKind.IGNORE to 0.2,
                    SocialActionKind.WAIT to 0.1,
                    SocialActionKind.REACT to 0.1,
                    SocialActionKind.SPEAK to 0.6,
                ),
            targetDistribution =
                ActionTargetDistribution(
                    candidates = listOf(TargetCandidate(TargetRef(TargetKind.MESSAGE, "m-1"), 0.7)),
                    noneProbability = 0.3,
                    resolverVersion = "rules-1",
                ),
            delayDistribution = DelayDistribution(mapOf(DelayBucket.IMMEDIATE to 0.5, DelayBucket.SHORT to 0.5)),
            socialActWeights = mapOf(SocialAct.ACKNOWLEDGE to 0.4, SocialAct.ASK to 0.6),
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 0.3,
        )
}
