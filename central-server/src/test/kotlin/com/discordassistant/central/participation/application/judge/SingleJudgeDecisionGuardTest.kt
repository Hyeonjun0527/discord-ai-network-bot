package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.participation.application.context.JudgeContextWindowBuilder
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class SingleJudgeDecisionGuardTest {
    private val scope = RawContextScope(guildId = 1L, channelId = 2L)

    @Test
    fun `low confidence SPEAK 는 WAIT 로 보수적으로 낮춘다`() {
        val guarded =
            SingleJudgeDecisionGuard.apply(
                request = sampleRequest(),
                decision = speakDecision(confidence = 0.3),
            )

        assertThat(guarded.finalDecision.action).isEqualTo(SocialActionKind.WAIT)
        assertThat(guarded.finalDecision.speechIntent).isNull()
        assertThat(guarded.finalDecision.reasonCode.code).endsWith(".low_confidence")
        assertThat(guarded.adjustments.map { it.code }).containsExactly("low_confidence_speak")
    }

    @Test
    fun `허용되지 않은 action 은 로그 가능한 fallback decision 으로 바뀐다`() {
        val guarded =
            SingleJudgeDecisionGuard.apply(
                request =
                    sampleRequest(
                        constraints =
                            JudgeDecisionConstraints(
                                allowedActions = setOf(SocialActionKind.IGNORE),
                                speechAllowed = false,
                                reactionAllowed = false,
                                maxDelayMillis = 0,
                                lowConfidenceFallbackActions = setOf(SocialActionKind.IGNORE),
                            ),
                    ),
                decision =
                    SingleJudgeDecision(
                        action = SocialActionKind.REACT,
                        confidence = 0.9,
                        delay = JudgeDecisionDelay.IMMEDIATE,
                        reactionCandidate = JudgeReactionCandidate("soft_ack"),
                        speechIntent = null,
                        toneAxes = JudgeToneAxes.NEUTRAL,
                        reasonCode = JudgeReasonCode("react_candidate"),
                    ),
            )

        assertThat(guarded.finalDecision.action).isEqualTo(SocialActionKind.IGNORE)
        assertThat(guarded.finalDecision.reasonCode.code).endsWith(".action_not_allowed")
        assertThat(guarded.adjustments.single().code).isEqualTo("action_not_allowed")
    }

    @Test
    fun `delay 는 runtime max 를 넘지 않게 잘린다`() {
        val guarded =
            SingleJudgeDecisionGuard.apply(
                request = sampleRequest(),
                decision =
                    SingleJudgeDecision(
                        action = SocialActionKind.WAIT,
                        confidence = 0.9,
                        delay = JudgeDecisionDelay(millis = 60_000, wakeUpHint = "idle_recheck"),
                        reactionCandidate = null,
                        speechIntent = null,
                        toneAxes = JudgeToneAxes.NEUTRAL,
                        reasonCode = JudgeReasonCode("wait_for_gap"),
                    ),
            )

        assertThat(guarded.finalDecision.delay.millis).isEqualTo(30_000)
        assertThat(guarded.finalDecision.delay.wakeUpHint).isEqualTo("idle_recheck")
        assertThat(guarded.finalDecision.reasonCode.code).endsWith(".delay_clipped")
        assertThat(guarded.adjustments.single().code).isEqualTo("delay_clipped")
    }

    @Test
    fun `low confidence fallback 은 WAIT 또는 IGNORE 로만 제한된다`() {
        assertThatThrownBy {
            JudgeDecisionConstraints(
                allowedActions = setOf(SocialActionKind.SPEAK),
                speechAllowed = true,
                reactionAllowed = false,
                maxDelayMillis = 30_000,
                lowConfidenceFallbackActions = setOf(SocialActionKind.SPEAK),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun sampleRequest(
        constraints: JudgeDecisionConstraints =
            JudgeDecisionConstraints(
                allowedActions = SocialActionKind.entries.toSet(),
                speechAllowed = true,
                reactionAllowed = true,
                maxDelayMillis = 30_000,
            ),
    ): SingleJudgeDecisionRequest =
        SingleJudgeDecisionRequest(
            rawContextWindow =
                JudgeContextWindowBuilder(maxRawChars = 1_000)
                    .build(
                        RawContextSnapshot(
                            scope = scope,
                            entries =
                                listOf(
                                    RawContextEntry(
                                        scope = scope,
                                        messageId = 10L,
                                        authorPseudonym = "user_a",
                                        occurredAt = Instant.parse("2026-06-29T00:00:00Z"),
                                        replyToMessageId = null,
                                        sourceType = RawContextSourceType.HUMAN,
                                        content = RawContextContent.Available("위로하라고"),
                                    ),
                                ),
                        ),
                    ),
            sceneSnapshot =
                SingleJudgeSceneSnapshot(
                    ref = SceneSnapshotRef("guild_a", "2", sceneSeq = 1L, contextVersion = 1L),
                    directAddressed = true,
                    replyToNia = false,
                    conversationMentionsNia = true,
                    recentAgentBurstCount = 0,
                    silenceMillis = 5_000,
                ),
            featureVector = FeatureVectorView.empty(version = FeatureCatalog.VERSION),
            memoryRefs = emptyList(),
            constraints = constraints,
            schemaVersion = SingleJudgeDecisionRequest.CURRENT_SCHEMA_VERSION,
            seed = 11L,
        )

    private fun speakDecision(confidence: Double): SingleJudgeDecision =
        SingleJudgeDecision(
            action = SocialActionKind.SPEAK,
            confidence = confidence,
            delay = JudgeDecisionDelay.IMMEDIATE,
            reactionCandidate = null,
            speechIntent =
                JudgeSpeechIntent(
                    intentSummary = "짧게 반응한다",
                    sceneDirection = "한 문장으로 인정한다",
                    actHint = "acknowledge",
                ),
            toneAxes = JudgeToneAxes.NEUTRAL,
            reasonCode = JudgeReasonCode("direct_address"),
        )
}
