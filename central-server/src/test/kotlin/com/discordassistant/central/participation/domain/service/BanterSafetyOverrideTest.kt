package com.discordassistant.central.participation.domain.service

import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
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

/** NEXA-P17-T015: 괴롭힘·모욕 안전 override — 위험 socialAct/target 조합을 제거·SPEAK 취소, raw 와 함께 로깅. */
class BanterSafetyOverrideTest {
    @Test
    fun `no safety signal leaves distribution unchanged`() {
        val result = BanterSafetyOverride.apply(distribution(), BanterSafetyContext())
        assertThat(result.changed).isFalse()
        assertThat(result.removals).isEmpty()
        assertThat(result.overridden).isEqualTo(result.raw)
    }

    @Test
    fun `banter opt-out removes TEASE and renormalizes`() {
        val result =
            BanterSafetyOverride.apply(
                distribution(acts = mapOf(SocialAct.TEASE to 0.5, SocialAct.ACKNOWLEDGE to 0.5)),
                BanterSafetyContext(targetOptedOutOfBanter = true),
            )
        assertThat(result.changed).isTrue()
        assertThat(result.overridden.socialActWeights).doesNotContainKey(SocialAct.TEASE)
        // 남은 act 는 1.0 으로 재정규화.
        assertThat(result.overridden.socialActWeights[SocialAct.ACKNOWLEDGE]).isEqualTo(1.0)
        assertThat(result.removals.map { it.reason }).contains(SafetyOverrideReason.BANTER_OPT_OUT)
    }

    @Test
    fun `repeated targeting removes aggressive acts`() {
        val result =
            BanterSafetyOverride.apply(
                distribution(
                    acts =
                        mapOf(
                            SocialAct.TEASE to 0.3,
                            SocialAct.DISAGREE to 0.3,
                            SocialAct.ACKNOWLEDGE to 0.4,
                        ),
                ),
                BanterSafetyContext(repeatedTargetingCount = 3, repeatedTargetingThreshold = 3),
            )
        assertThat(result.overridden.socialActWeights.keys)
            .doesNotContain(SocialAct.TEASE, SocialAct.DISAGREE)
        assertThat(result.removals.map { it.reason }).contains(SafetyOverrideReason.REPEATED_TARGETING)
    }

    @Test
    fun `stop request cancels SPEAK when no safe social act remains`() {
        // 중단 신호 → 모든 비-침묵 act 제거 → SPEAK 근거 소멸 → SPEAK 취소·재정규화.
        val result =
            BanterSafetyOverride.apply(
                distribution(acts = mapOf(SocialAct.TEASE to 0.5, SocialAct.ASK to 0.5)),
                BanterSafetyContext(targetStopRequested = true),
            )
        assertThat(result.overridden.socialActWeights).isEmpty()
        assertThat(result.overridden.actionWeights).doesNotContainKey(SocialActionKind.SPEAK)
        assertThat(result.removals.map { it.targetKind }).contains(SafetyOverrideTargetKind.ACTION)
        assertThat(result.removals.map { it.reason }).contains(SafetyOverrideReason.NO_SAFE_SOCIAL_ACT)
        // 분포는 여전히 합 1.0(ActionDistribution init 가 보장).
        assertThat(
            result.overridden.actionWeights.values
                .sum(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `result carries raw and overridden together for decision log`() {
        val raw = distribution(acts = mapOf(SocialAct.TEASE to 1.0))
        val result = BanterSafetyOverride.apply(raw, BanterSafetyContext(targetOptedOutOfBanter = true))
        // acceptance: raw 와 override 가 함께 남는다.
        assertThat(result.raw).isEqualTo(raw)
        assertThat(result.overridden).isNotEqualTo(raw)
        assertThat(result.changed).isTrue()
    }

    private fun distribution(
        actions: Map<SocialActionKind, Double> =
            mapOf(
                SocialActionKind.IGNORE to 0.2,
                SocialActionKind.REACT to 0.2,
                SocialActionKind.SPEAK to 0.6,
            ),
        acts: Map<SocialAct, Double> = mapOf(SocialAct.ACKNOWLEDGE to 1.0),
    ): ActionDistribution =
        ActionDistribution(
            actionWeights = actions,
            targetDistribution =
                ActionTargetDistribution(
                    candidates = listOf(TargetCandidate(TargetRef(TargetKind.MESSAGE, "m-1"), 0.7)),
                    noneProbability = 0.3,
                    resolverVersion = "rules-1",
                ),
            delayDistribution = DelayDistribution(mapOf(DelayBucket.IMMEDIATE to 1.0)),
            socialActWeights = acts,
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 0.3,
        )
}
