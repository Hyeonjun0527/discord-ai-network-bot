package com.discordassistant.central.participation.application.shadow

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.participation.application.context.JudgeContextWindowBuilder
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.judge.JudgeDecisionConstraints
import com.discordassistant.central.participation.application.judge.JudgeDecisionDelay
import com.discordassistant.central.participation.application.judge.JudgeReasonCode
import com.discordassistant.central.participation.application.judge.JudgeSpeechIntent
import com.discordassistant.central.participation.application.judge.JudgeToneAxes
import com.discordassistant.central.participation.application.judge.NiaJudgeExecutionPurpose
import com.discordassistant.central.participation.application.judge.SingleJudgeDecision
import com.discordassistant.central.participation.application.judge.SingleJudgeDecisionRequest
import com.discordassistant.central.participation.application.judge.SingleJudgeSceneSnapshot
import com.discordassistant.central.participation.application.judge.SingleParticipationJudgePort
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.SceneKey
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.application.port.out.ShadowPredictionRecord
import com.discordassistant.central.participation.application.port.out.ShadowPredictionStorePort
import com.discordassistant.central.participation.application.port.out.ShadowPredictionSummary
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NiaJudgeShadowServiceTest {
    private val now = Instant.parse("2026-06-30T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val scope = RawContextScope(guildId = 1L, channelId = 2L)

    @Test
    fun `records judge result as shadow prediction without raw text`() {
        val store = FakePredictionStore()
        val judge = CapturingJudge(speakDecision())
        val service = NiaJudgeShadowService(judge, store, clock)

        val result = service.record(sampleRequest())

        assertThat(result).isInstanceOf(NiaJudgeShadowResult.Recorded::class.java)
        val record = store.records.single()
        assertThat(record.modelVersion).isEqualTo(NiaJudgeShadowService.MODEL_VERSION)
        assertThat(record.sampledAction).isEqualTo(SocialActionKind.SPEAK)
        assertThat(record.actionWeights[SocialActionKind.SPEAK]).isEqualTo(1.0)
        assertThat(record.actionWeights[SocialActionKind.IGNORE]).isEqualTo(0.0)
        assertThat(record.expectedFireAt).isEqualTo(now)
        assertThat(record.featureHash).hasSize(64)
        assertThat(record.featureHash).doesNotContain("위로해줘")
        assertThat(judge.request!!.executionPurpose).isEqualTo(NiaJudgeExecutionPurpose.SHADOW)
    }

    @Test
    fun `judge failure is returned as shadow failure and does not append`() {
        val store = FakePredictionStore()
        val service =
            NiaJudgeShadowService(
                judge = ThrowingJudge(),
                predictionStore = store,
                clock = clock,
            )

        val result = service.record(sampleRequest())

        assertThat(result).isInstanceOf(NiaJudgeShadowResult.Failed::class.java)
        assertThat(store.records).isEmpty()
    }

    private fun sampleRequest(): SingleJudgeDecisionRequest =
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
                                        occurredAt = now,
                                        replyToMessageId = null,
                                        sourceType = RawContextSourceType.HUMAN,
                                        content = RawContextContent.Available("야 이럴땐 위로해줘"),
                                    ),
                                ),
                        ),
                    ),
            sceneSnapshot =
                SingleJudgeSceneSnapshot(
                    ref = SceneSnapshotRef("guild_a", "channel_a", sceneSeq = 7L, contextVersion = 3L),
                    directAddressed = true,
                    replyToNia = false,
                    conversationMentionsNia = true,
                    recentAgentBurstCount = 0,
                    silenceMillis = 8_000,
                ),
            featureVector = FeatureVectorView.empty(version = FeatureCatalog.VERSION),
            memoryRefs = emptyList(),
            constraints =
                JudgeDecisionConstraints(
                    allowedActions = SocialActionKind.entries.toSet(),
                    speechAllowed = true,
                    reactionAllowed = true,
                    maxDelayMillis = 30_000,
                ),
            schemaVersion = SingleJudgeDecisionRequest.CURRENT_SCHEMA_VERSION,
            seed = 42L,
        )

    private fun speakDecision(): SingleJudgeDecision =
        SingleJudgeDecision(
            action = SocialActionKind.SPEAK,
            confidence = 0.8,
            delay = JudgeDecisionDelay.IMMEDIATE,
            reactionCandidate = null,
            speechIntent = JudgeSpeechIntent("acknowledge direct request", "one short sentence"),
            toneAxes = JudgeToneAxes.NEUTRAL,
            reasonCode = JudgeReasonCode("judge.synthetic"),
        )

    private class CapturingJudge(
        private val decision: SingleJudgeDecision,
    ) : SingleParticipationJudgePort {
        var request: SingleJudgeDecisionRequest? = null

        override fun decide(request: SingleJudgeDecisionRequest): SingleJudgeDecision {
            this.request = request
            return decision
        }
    }

    private class ThrowingJudge : SingleParticipationJudgePort {
        override fun decide(request: SingleJudgeDecisionRequest): SingleJudgeDecision = error("judge failed")
    }

    private class FakePredictionStore : ShadowPredictionStorePort {
        val records = mutableListOf<ShadowPredictionRecord>()

        override fun append(record: ShadowPredictionRecord) {
            records += record
        }

        override fun findByScene(scene: SceneKey): List<ShadowPredictionRecord> = records.filter { it.scene == scene }

        override fun summarizeGuild(guildPseudonym: String): ShadowPredictionSummary =
            ShadowPredictionSummary(records.size.toLong(), records.firstOrNull()?.predictedAt, records.lastOrNull()?.predictedAt)

        override fun purgeExpired(olderThan: Instant): Int = 0
    }
}
