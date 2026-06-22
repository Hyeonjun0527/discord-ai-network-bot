package com.discordassistant.central.participation.domain.model.decision

import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialAction
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.action.SpeechRequestRef
import com.discordassistant.central.participation.domain.service.CalibrationRecord
import com.discordassistant.central.participation.domain.service.SeededPolicySampler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P08-T022 ParticipationDecision aggregate acceptance 단위 테스트. */
class ParticipationDecisionTest {
    @Test
    fun `T022 acceptance — live 행동을 사후 재현할 정보를 모두 담는다`() {
        val dist = distribution()
        val seed = 777L
        val sampled = SeededPolicySampler.sample(dist, seed)
        val decision =
            ParticipationDecision(
                correlationId = "corr-1",
                contextVersion = 5,
                rawDistribution = dist,
                calibrationRecord = neutralRecord(),
                removedKinds = setOf(SocialActionKind.CANCEL_PENDING),
                sampled = sampled,
                finalAction = actionFor(sampled.action),
                modelVersion = "rules-1",
                featureVectorVersion = 1,
                seed = seed,
            )
        // 재현 키: 같은 raw distribution·seed 로 같은 sampled 가 다시 나온다.
        assertThat(SeededPolicySampler.sample(decision.rawDistribution, decision.seed)).isEqualTo(decision.sampled)
        // provenance 보존.
        assertThat(decision.modelVersion).isEqualTo("rules-1")
        assertThat(decision.contextVersion).isEqualTo(5)
        assertThat(decision.removedKinds).contains(SocialActionKind.CANCEL_PENDING)
    }

    @Test
    fun `T022 — IGNORE 도 정상 결정으로 기록되고 quota 무소모`() {
        val dist = ignoreOnly()
        val sampled = SeededPolicySampler.sample(dist, 1L)
        val decision =
            ParticipationDecision(
                correlationId = "corr-ignore",
                contextVersion = 0,
                rawDistribution = dist,
                calibrationRecord = neutralRecord(),
                removedKinds = emptySet(),
                sampled = sampled,
                finalAction = SocialAction.Ignore,
                modelVersion = "rules-1",
                featureVectorVersion = 1,
                seed = 1L,
            )
        assertThat(decision.isIgnore).isTrue()
        assertThat(decision.consumesGenerationQuota).isFalse()
    }

    @Test
    fun `T022 — finalAction 종류와 sampled 종류가 다르면 거부한다(재현 무결성)`() {
        val dist = ignoreOnly()
        val sampled = SeededPolicySampler.sample(dist, 1L) // IGNORE
        assertThatThrownBy {
            ParticipationDecision(
                correlationId = "c",
                contextVersion = 0,
                rawDistribution = dist,
                calibrationRecord = neutralRecord(),
                removedKinds = emptySet(),
                sampled = sampled,
                finalAction = SocialAction.Speak(SpeechRequestRef("c")), // SPEAK ≠ sampled IGNORE
                modelVersion = "m",
                featureVectorVersion = 1,
                seed = 1L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun actionFor(kind: SocialActionKind): SocialAction =
        when (kind) {
            SocialActionKind.IGNORE -> SocialAction.Ignore
            SocialActionKind.WAIT -> SocialAction.Wait(ActionDelay.IMMEDIATE)
            SocialActionKind.REACT ->
                SocialAction.React(
                    listOf(
                        com.discordassistant.central.participation.domain.model.action
                            .ReactionCode("ack"),
                    ),
                )
            SocialActionKind.SPEAK -> SocialAction.Speak(SpeechRequestRef("corr-1"))
            SocialActionKind.CANCEL_PENDING ->
                SocialAction.CancelPending(
                    com.discordassistant.central.participation.domain.model.action
                        .PendingActionId("p"),
                )
        }

    private fun neutralRecord() =
        CalibrationRecord(
            modelTemperature = 1.0,
            talkativenessLogitAdjustment = 0.0,
            rawSpeakProbability = 0.6,
            calibratedSpeakProbability = 0.6,
        )

    private fun distribution(): ActionDistribution =
        ActionDistribution(
            actionWeights = mapOf(SocialActionKind.SPEAK to 1.0),
            targetDistribution = ActionTargetDistribution.none("v1"),
            delayDistribution = DelayDistribution.IMMEDIATE,
            socialActWeights = mapOf(SocialAct.ASK to 1.0),
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 0.1,
        )

    private fun ignoreOnly(): ActionDistribution =
        ActionDistribution(
            actionWeights = mapOf(SocialActionKind.IGNORE to 1.0),
            targetDistribution = ActionTargetDistribution.none("v1"),
            delayDistribution = DelayDistribution.NEVER,
            socialActWeights = emptyMap(),
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 0.0,
        )
}
