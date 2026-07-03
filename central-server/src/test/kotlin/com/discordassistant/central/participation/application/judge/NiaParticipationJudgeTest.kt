package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.participation.application.context.JudgeContextWindowBuilder
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmPort
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmResponse
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NiaParticipationJudgeTest {
    private val mapper = jacksonObjectMapper()
    private val scope = RawContextScope(guildId = 1L, channelId = 2L)
    private val now = Instant.parse("2026-06-29T00:00:00Z")

    @Test
    fun `judge returns guarded decision on first valid output`() {
        val llm = FakeJudgeLlm(response(output("SPEAK")))
        val judge = judge(llm)

        val decision = judge.decide(sampleRequest())

        assertThat(decision.action).isEqualTo(SocialActionKind.SPEAK)
        assertThat(decision.speechIntent!!.intentSummary).contains("acknowledge")
        assertThat(llm.requests).hasSize(1)
    }

    @Test
    fun `judge retries once with repair instruction after malformed output`() {
        val llm = FakeJudgeLlm(response("{bad-json"), response(output("WAIT")))
        val judge = judge(llm)

        val decision = judge.decide(sampleRequest())

        assertThat(decision.action).isEqualTo(SocialActionKind.WAIT)
        assertThat(llm.requests).hasSize(2)
        assertThat(llm.requests[1].prompt).contains("REPAIR_INSTRUCTION")
    }

    @Test
    fun `judge degrades to WAIT or IGNORE after retry exhaustion`() {
        val llm = FakeJudgeLlm(response("{bad-json"), response("{still-bad"))
        val judge = judge(llm)

        val decision = judge.decide(sampleRequest())

        assertThat(decision.action).isIn(SocialActionKind.WAIT, SocialActionKind.IGNORE)
        assertThat(decision.action).isNotEqualTo(SocialActionKind.SPEAK)
        assertThat(decision.confidence).isEqualTo(0.0)
        assertThat(llm.requests).hasSize(2)
    }

    private fun judge(llm: FakeJudgeLlm): NiaParticipationJudge =
        NiaParticipationJudge(
            promptAssembler = NiaJudgePromptAssembler(),
            llmPort = llm,
            outputParser = NiaJudgeOutputParser(mapper),
        )

    private fun output(action: String): String {
        val base =
            linkedMapOf<String, Any?>(
                "schema" to "nia.participation-judge-output.v1",
                "action" to action,
                "reason" to "synthetic judge reason",
                "reasonCode" to "judge.synthetic",
                "evidenceRefs" to if (action == "IGNORE") emptyList<String>() else listOf("msg_1"),
                "confidence" to 0.82,
                "riskFlags" to emptyList<String>(),
                "reevaluateAfterMs" to if (action == "WAIT") 2_000 else 0,
            )
        if (action == "SPEAK") {
            base["speechIntent"] =
                mapOf(
                    "intentSummary" to "acknowledge direct request",
                    "sceneDirection" to "one short sentence",
                )
        }
        return mapper.writeValueAsString(base)
    }

    private fun response(content: String): NiaJudgeLlmResponse =
        NiaJudgeLlmResponse(content = content, modelVersion = "local-judge-v1", finishReason = "stop")

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

    private class FakeJudgeLlm(
        private vararg val responses: NiaJudgeLlmResponse,
    ) : NiaJudgeLlmPort {
        val requests = mutableListOf<NiaJudgeLlmRequest>()
        private var cursor = 0

        override fun complete(request: NiaJudgeLlmRequest): NiaJudgeLlmResponse {
            requests += request
            return responses[cursor++]
        }
    }
}
