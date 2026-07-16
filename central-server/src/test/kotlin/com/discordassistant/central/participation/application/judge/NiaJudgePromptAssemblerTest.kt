package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.participation.application.context.JudgeContextWindowBuilder
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NiaJudgePromptAssemblerTest {
    private val mapper = jacksonObjectMapper()
    private val scope = RawContextScope(guildId = 1L, channelId = 2L)
    private val now = Instant.parse("2026-06-29T00:00:00Z")

    @Test
    fun `prompt includes raw scene few-shot memory constraints and schema`() {
        val llmRequest = NiaJudgePromptAssembler(timeoutMillis = 3_000).assemble(sampleRequest())
        val payload = mapper.readTree(llmRequest.prompt.substringAfter("INPUT_JSON:\n"))

        assertThat(llmRequest.promptVersion).isEqualTo(NiaJudgePromptAssembler.PROMPT_VERSION)
        assertThat(llmRequest.promptVersion).isEqualTo("nia-judge-prompt-v4")
        assertThat(llmRequest.outputSchema).isEqualTo(NiaJudgeLlmRequest.OUTPUT_SCHEMA)
        assertThat(llmRequest.timeoutMillis).isEqualTo(3_000)
        assertThat(llmRequest.prompt)
            .contains(
                "NIA is one participant in a multi-person conversation",
                "A direct mention, reply, or name call in the current turn is different",
                "no newer correction, withdrawal, addressee change, or stop request supersedes it",
                "Repeated direct calls are not a reason to stay silent",
                "Silence is a successful action",
                "mistaken interruption",
                "newer correction, withdrawal, or addressee change",
                "invitation was retracted",
                "Do not encode this as keyword matching",
                "Return only JSON",
                "Do not include final response text",
                "speechIntent",
            )
        assertThat(payload["schema"].asText()).isEqualTo(NiaJudgePromptAssembler.INPUT_SCHEMA)
        assertThat(payload["outputSchema"].asText()).isEqualTo(NiaJudgeLlmRequest.OUTPUT_SCHEMA)
        assertThat(payload.at("/rawScene/messages/1/text").asText()).isEqualTo("야 이럴땐 위로해줘")
        assertThat(payload.at("/fewShotSet/version").asInt()).isEqualTo(3)
        assertThat(payload.at("/fewShotSet/examples/0/expectedAction").asText()).isEqualTo("SPEAK")
        assertThat(payload.at("/fewShotSet/examples/0/badAlternative/action").asText()).isEqualTo("WAIT")
        assertThat(payload.at("/socialMemory/0/refId").asText()).isEqualTo("mem-1")
        assertThat(payload.at("/constraints/allowedActions").map { it.asText() }).contains("CANCEL", "SPEAK")
        assertThat(payload.at("/featureVector/turn.direct_pressure/value").asDouble()).isEqualTo(0.8)
    }

    @Test
    fun `prompt metadata avoids raw guild and channel ids outside the raw scene source`() {
        val llmRequest = NiaJudgePromptAssembler().assemble(sampleRequest())
        val payload = mapper.readTree(llmRequest.prompt.substringAfter("INPUT_JSON:\n"))

        assertThat(payload.at("/metadata/sceneSeq").asLong()).isEqualTo(7L)
        assertThat(payload.at("/metadata/contextVersion").asLong()).isEqualTo(3L)
        assertThat(payload.toString()).doesNotContain("guild_a", "channel_a")
        assertThat(llmRequest.toString()).doesNotContain("야 이럴땐 위로해줘")
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
                                    rawEntry(10L, RawContextSourceType.BOT, "그래, 그게 내가 뭘 잘못한 거지?"),
                                    rawEntry(
                                        messageId = 11L,
                                        sourceType = RawContextSourceType.HUMAN,
                                        text = "야 이럴땐 위로해줘",
                                        occurredAt = now.plusSeconds(1),
                                        replyToMessageId = 10L,
                                    ),
                                ),
                        ),
                    ),
            sceneSnapshot =
                SingleJudgeSceneSnapshot(
                    ref = SceneSnapshotRef("guild_a", "channel_a", sceneSeq = 7L, contextVersion = 3L),
                    directAddressed = true,
                    replyToNia = true,
                    conversationMentionsNia = true,
                    recentAgentBurstCount = 0,
                    silenceMillis = 8_000,
                    textSignals =
                        JudgeSceneTextSignals(
                            contentAvailable = true,
                            isQuestion = false,
                            replyTargetKind = "nia",
                            emotionalIntensity = 0.4,
                            callPressure = 0.8,
                        ),
                ),
            featureVector =
                FeatureVectorView.of(
                    version = FeatureCatalog.VERSION,
                    pairs = mapOf(FeatureId("turn.direct_pressure") to FeatureValue.present(0.8)),
                ),
            fewShotSet = sampleFewShotSet(),
            memoryRefs =
                listOf(
                    JudgeMemoryRef(
                        refId = "mem-1",
                        claim = "user_a expects short direct acknowledgment from NIA",
                        provenance = "synthetic",
                        confidence = 0.7,
                    ),
                ),
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

    private fun rawEntry(
        messageId: Long,
        sourceType: RawContextSourceType,
        text: String,
        occurredAt: Instant = now,
        replyToMessageId: Long? = null,
    ): RawContextEntry =
        RawContextEntry(
            scope = scope,
            messageId = messageId,
            authorPseudonym = if (sourceType == RawContextSourceType.BOT) "nia_bot" else "user_a",
            occurredAt = occurredAt,
            replyToMessageId = replyToMessageId,
            sourceType = sourceType,
            content = RawContextContent.Available(text),
        )

    private fun sampleFewShotSet(): JudgeFewShotSetPayload =
        JudgeFewShotSetPayload(
            setId = 1L,
            version = 3,
            examples =
                listOf(
                    JudgeFewShotExamplePayload(
                        exampleId = "fs_direct_reply",
                        title = "direct reply request",
                        rawMessages =
                            listOf(
                                JudgeFewShotRawMessagePayload(
                                    ref = "m1",
                                    authorRole = "member",
                                    offsetMs = 0,
                                    text = "야 이럴땐 위로해줘",
                                ),
                            ),
                        expectedAction = NiaFewShotAction.SPEAK,
                        reason = "Direct reply request should be judged from raw scene evidence.",
                        evidenceRefs = setOf("m1"),
                        badAlternative =
                            JudgeFewShotBadAlternativePayload(
                                action = NiaFewShotAction.WAIT,
                                whyBad = "Waiting would make NIA appear to ignore the direct request.",
                            ),
                    ),
                ),
        )
}
