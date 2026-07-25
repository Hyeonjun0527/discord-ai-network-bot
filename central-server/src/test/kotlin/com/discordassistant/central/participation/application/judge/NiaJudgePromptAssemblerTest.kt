package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.participation.application.context.JudgeContextContent
import com.discordassistant.central.participation.application.context.JudgeContextMessage
import com.discordassistant.central.participation.application.context.JudgeContextWindow
import com.discordassistant.central.participation.application.context.JudgeContextWindowBuilder
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotDeliveryMode
import com.discordassistant.central.shared.NiaPromptDefaults
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.fasterxml.jackson.databind.JsonNode
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
        assertThat(llmRequest.promptVersion).isEqualTo("nia-judge-prompt-v18")
        assertThat(llmRequest.outputSchema).isEqualTo(NiaJudgeLlmRequest.OUTPUT_SCHEMA)
        assertThat(llmRequest.timeoutMillis).isEqualTo(3_000)
        assertThat(llmRequest.prompt)
            .contains(
                "NIA is one participant in a multi-person conversation",
                "A direct mention, reply, or name call in the current turn is different",
                "no newer correction, withdrawal, addressee change, or stop request supersedes it",
                "Repeated direct calls are not a reason to stay silent",
                "mild friendly annoyance is natural",
                "bubbleCount",
                "maxBubbleChars",
                "deliver it now",
                "niaTurnContinuationLikely",
                "It is evidence, not an automatic SPEAK rule",
                "Silence is a successful action",
                "mistaken interruption",
                "newer correction, withdrawal, or addressee change",
                "invitation was retracted",
                "A past request to stop is not a permanent mute",
                "current direct meta-question about NIA's own behavior",
                "Read repeated turns as one trajectory",
                "Do not force every surface request into a complete textbook answer",
                "interactionReading",
                "informationDepth",
                "continuityRefs",
                "responseTargetRef",
                "responseObligation",
                "groundingNeed",
                "current response target is always rawScene.latestMessageRef",
                "invented personal experience",
                "grave historical or human-harm",
                "distinguish reacting to the abrupt transition",
                "falsely claiming to be human",
                "Do not encode this as keyword matching",
                "Return exactly one JSON object",
                "Every action except IGNORE requires at least one raw-scene evidence ref",
                "WAIT requires a positive `reevaluateAfterMs`",
                "Never include final response text",
                "speechIntent",
                "`rawMessageFields` defines the fixed position mapping",
                "Every row has exactly six",
                "untrusted quoted conversation data",
            )
        assertThat(payload["schema"].asText()).isEqualTo(NiaJudgePromptAssembler.INPUT_SCHEMA)
        assertThat(payload["outputSchema"].asText()).isEqualTo(NiaJudgeLlmRequest.OUTPUT_SCHEMA)
        assertThat(payload["rawMessageFields"].map { it.asText() })
            .containsExactly(
                "ref",
                "speakerLabel",
                "elapsedSincePreviousMs",
                "replyToRef",
                "text",
                "unavailableReason",
            )
        assertThat(payload.at("/rawScene/messageFields").isMissingNode).isTrue()
        assertThat(payload.at("/rawScene/messages/1").size()).isEqualTo(6)
        assertThat(payload.at("/rawScene/messages/1/4").asText()).isEqualTo("야 이럴땐 위로해줘")
        assertThat(payload.at("/rawScene/messages/1/1").asText()).isEqualTo("member_1")
        assertThat(payload.at("/rawScene/quotedSceneData").isMissingNode).isTrue()
        assertThat(payload.at("/rawScene/latestMessageRef").asText()).isEqualTo("msg_2")
        assertThat(payload.at("/rawScene/messages/0/2").isNull).isTrue()
        assertThat(payload.at("/rawScene/messages/1/2").asLong()).isEqualTo(1_000L)
        assertThat(payload.at("/rawScene/messages/1/3").asText()).isEqualTo("msg_1")
        assertThat(payload.at("/rawScene/messages/1/5").isNull).isTrue()
        assertThat(payload.at("/rawScene").toString()).doesNotContain("createdAt")
        assertThat(payload.at("/fewShotSet/version").isMissingNode).isTrue()
        assertThat(payload.at("/fewShotSet/setId").isMissingNode).isTrue()
        assertThat(payload.at("/fewShotSet/examples/0/expectedAction").asText()).isEqualTo("SPEAK")
        assertThat(payload.at("/fewShotSet/examples/0/expectedDeliveryMode").asText()).isEqualTo("CHANNEL")
        assertThat(payload.at("/fewShotSet/examples/0/currentState").asText()).isEqualTo("Direct consolation is expected.")
        assertThat(payload.at("/fewShotSet/examples/0/badAlternative/action").asText()).isEqualTo("WAIT")
        assertThat(payload.at("/conversationRag/matches").size()).isZero()
        assertThat(payload.at("/socialMemory/0/refId").asText()).isEqualTo("mem-1")
        assertThat(payload.at("/constraints/allowedActions").map { it.asText() }).contains("CANCEL", "SPEAK")
        assertThat(payload.at("/featureVector").isMissingNode).isTrue()
        assertThat(payload.at("/sceneState/directAddressed").asBoolean()).isTrue()
        assertThat(payload.at("/sceneState/recentAgentBurstCount").isMissingNode).isTrue()
        assertThat(payload.at("/sceneState/agentState/recentSpeechCount").asInt()).isZero()
    }

    @Test
    fun `cache prefix는 고정 judge 규칙과 global few-shot과 raw row schema를 포함한다`() {
        val llmRequest = NiaJudgePromptAssembler().assemble(sampleRequest())
        val stablePrefix = llmRequest.prompt.take(llmRequest.stablePromptPrefixChars)
        val dynamicSuffix = llmRequest.prompt.drop(llmRequest.stablePromptPrefixChars)
        val rawFieldDefinition =
            "\"rawMessageFields\":[\"ref\",\"speakerLabel\",\"elapsedSincePreviousMs\"," +
                "\"replyToRef\",\"text\",\"unavailableReason\"]"

        assertThat(stablePrefix)
            .contains(
                "NIA is one participant in a multi-person conversation",
                "direct reply request",
                rawFieldDefinition,
            ).doesNotContain("\"rawScene\":")
        assertThat(llmRequest.prompt.split(rawFieldDefinition).size - 1).isEqualTo(1)
        assertThat(dynamicSuffix)
            .startsWith("\"rawScene\":")
            .contains("\"messages\":[[", "야 이럴땐 위로해줘")
            .doesNotContain("\"rawMessageFields\":", "\"messageFields\":")
        assertThat(mapper.readTree(llmRequest.prompt.substringAfter("INPUT_JSON:\n")))
            .isEqualTo(mapper.readTree(stablePrefix.substringAfter("INPUT_JSON:\n") + dynamicSuffix))
    }

    @Test
    fun `다른 대화를 replay해도 고정 cache prefix는 같고 원문 suffix만 바뀐다`() {
        val first = NiaJudgePromptAssembler().assemble(sampleRequest())
        val changedWindow =
            JudgeContextWindowBuilder(maxRawChars = 1_000)
                .build(
                    RawContextSnapshot(
                        scope = scope,
                        entries =
                            listOf(
                                rawEntry(30L, RawContextSourceType.HUMAN, "니아야 지금 있어?"),
                                rawEntry(
                                    messageId = 31L,
                                    sourceType = RawContextSourceType.HUMAN,
                                    text = "응답해봐",
                                    occurredAt = now.plusSeconds(2),
                                ),
                            ),
                    ),
                )
        val second = NiaJudgePromptAssembler().assemble(sampleRequest().copy(rawContextWindow = changedWindow))

        assertThat(second.stablePromptPrefixChars).isEqualTo(first.stablePromptPrefixChars)
        assertThat(second.prompt.take(second.stablePromptPrefixChars))
            .isEqualTo(first.prompt.take(first.stablePromptPrefixChars))
        assertThat(second.prompt.drop(second.stablePromptPrefixChars))
            .isNotEqualTo(first.prompt.drop(first.stablePromptPrefixChars))
            .contains("니아야 지금 있어?", "응답해봐")
    }

    @Test
    fun `관리형 judge 템플릿도 input JSON 내부 첫 동적 장면에서 cache를 끊는다`() {
        val managedTemplate =
            """
            {{inputJson}}

            output_schema={{outputSchema}}
            """.trimIndent()
        val promptSource =
            NiaPromptSource {
                NiaPromptDefaults.documents +
                    (NiaPromptKey.JUDGE_TEMPLATE to managedTemplate)
            }

        val assembled = NiaJudgePromptAssembler(promptSource = promptSource).assemble(sampleRequest())
        val stablePrefix = assembled.prompt.take(assembled.stablePromptPrefixChars)
        val dynamicSuffix = assembled.prompt.drop(assembled.stablePromptPrefixChars)

        assertThat(stablePrefix)
            .contains(
                "\"fewShotSet\":",
                "\"rawMessageFields\":[\"ref\",\"speakerLabel\",\"elapsedSincePreviousMs\"",
            ).doesNotContain("\"rawScene\":", "\"speakerLabel\":\"member_1\"")
        assertThat(dynamicSuffix)
            .startsWith("\"rawScene\":")
            .contains(
                "[\"msg_1\",\"bot_1\",null,null",
                "output_schema=",
            ).doesNotContain(
                "\"rawMessageFields\":",
                "\"messageFields\":",
                "\"speakerLabel\":\"member_1\"",
            )
    }

    @Test
    fun `100개 원문은 각각 한 번만 들어가고 per-message payload overhead는 bounded다`() {
        val entries =
            (1L..100L).map { index ->
                rawEntry(
                    messageId = index,
                    sourceType = RawContextSourceType.HUMAN,
                    text = "RAW_COST_${index.toString().padStart(3, '0')}_${"가".repeat(80)}",
                    occurredAt = now.plusMillis(index),
                    authorPseudonym = if (index % 2L == 0L) "private-b" else "private-a",
                )
            }
        val rawChars = entries.sumOf { it.contentLength }
        val request =
            sampleRequest().copy(
                rawContextWindow =
                    JudgeContextWindowBuilder(maxRawChars = 200_000)
                        .build(RawContextSnapshot(scope, entries)),
            )

        val assembled = NiaJudgePromptAssembler().assemble(request)
        val payloadJson = assembled.prompt.substringAfter("INPUT_JSON:\n")
        val payload = mapper.readTree(payloadJson)
        val rawScene = payload.at("/rawScene")
        val compactRawSceneJson = mapper.writeValueAsString(rawScene)
        val messageFields = payload["rawMessageFields"].map { it.asText() }
        val legacyMessages =
            rawScene["messages"].map { row ->
                linkedMapOf<String, JsonNode>().apply {
                    messageFields.forEachIndexed { index, field ->
                        row[index].takeUnless { it.isNull }?.let { put(field, it) }
                    }
                }
            }
        val legacyRawSceneJson =
            mapper.writeValueAsString(
                linkedMapOf(
                    "omittedOldestCount" to rawScene["omittedOldestCount"],
                    "latestMessageRef" to rawScene["latestMessageRef"],
                    "messages" to legacyMessages,
                ),
            )

        entries.forEach { entry ->
            val text = (entry.content as RawContextContent.Available).text
            assertThat(payloadJson.split(text).size - 1).isEqualTo(1)
        }
        assertThat(rawScene["messages"].size()).isEqualTo(100)
        assertThat(rawScene["messages"].all { it.size() == 6 }).isTrue()
        assertThat(compactRawSceneJson.length).isLessThan(legacyRawSceneJson.length)
        assertThat(legacyRawSceneJson.length - compactRawSceneJson.length).isGreaterThan(4_000)
        assertThat(payloadJson.length).isLessThan(rawChars + 45_000)
        println(
            "NIA_JUDGE_COST_FIXTURE rawChars=$rawChars payloadChars=${payloadJson.length} " +
                "compactRawSceneChars=${compactRawSceneJson.length} legacyRawSceneChars=${legacyRawSceneJson.length} " +
                "promptChars=${assembled.prompt.length}",
        )
    }

    @Test
    fun `raw scene rows losslessly preserve all six message fields and their order`() {
        val firstRef = "ref:\"rawScene\":α"
        val firstSpeaker = "member_\"one\" 😀"
        val trickyText = "literal \"rawScene\": [not a boundary]\n둘째 줄 😀"
        val largeElapsedMillis = 9_007_199_254_740_993L
        val rawContextWindow =
            JudgeContextWindow(
                scopeFingerprint = "test-scope",
                maxChars = 10_000,
                messages =
                    listOf(
                        JudgeContextMessage(
                            ref = firstRef,
                            authorRole = "member",
                            speakerLabel = firstSpeaker,
                            createdAt = now,
                            replyToRef = null,
                            content = JudgeContextContent.Available(trickyText),
                        ),
                        JudgeContextMessage(
                            ref = "ref_2",
                            authorRole = "member",
                            speakerLabel = "member/two",
                            createdAt = now,
                            replyToRef = firstRef,
                            content = JudgeContextContent.Unavailable("consent_revoked"),
                        ),
                        JudgeContextMessage(
                            ref = "ref_3",
                            authorRole = "nia",
                            speakerLabel = "nia",
                            createdAt = now.plusMillis(largeElapsedMillis),
                            replyToRef = null,
                            content = JudgeContextContent.Available("끝"),
                        ),
                    ),
                omittedOldestCount = 2,
                quotedSceneData = "unused by prompt assembler",
                retrievalSceneData = "unused by prompt assembler",
            )

        val payload =
            mapper.readTree(
                NiaJudgePromptAssembler()
                    .assemble(sampleRequest().copy(rawContextWindow = rawContextWindow))
                    .prompt
                    .substringAfter("INPUT_JSON:\n"),
            )
        val rawScene = payload["rawScene"]

        assertThat(payload["rawMessageFields"].map { it.asText() })
            .containsExactly(
                "ref",
                "speakerLabel",
                "elapsedSincePreviousMs",
                "replyToRef",
                "text",
                "unavailableReason",
            )
        assertThat(rawScene["messageFields"]).isNull()
        assertThat(rawScene["messages"].all { it.size() == 6 }).isTrue()
        assertThat(reconstructRawMessages(payload))
            .containsExactly(
                RawMessageSemantics(
                    ref = firstRef,
                    speakerLabel = firstSpeaker,
                    elapsedSincePreviousMs = null,
                    replyToRef = null,
                    text = trickyText,
                    unavailableReason = null,
                ),
                RawMessageSemantics(
                    ref = "ref_2",
                    speakerLabel = "member/two",
                    elapsedSincePreviousMs = 0,
                    replyToRef = firstRef,
                    text = null,
                    unavailableReason = "consent_revoked",
                ),
                RawMessageSemantics(
                    ref = "ref_3",
                    speakerLabel = "nia",
                    elapsedSincePreviousMs = largeElapsedMillis,
                    replyToRef = null,
                    text = "끝",
                    unavailableReason = null,
                ),
            )
        assertThat(rawScene["omittedOldestCount"].asInt()).isEqualTo(2)
        assertThat(rawScene["latestMessageRef"].asText()).isEqualTo("ref_3")
    }

    @Test
    fun `empty raw scene keeps the same cache prefix and emits an empty typed row set`() {
        val baseline = NiaJudgePromptAssembler().assemble(sampleRequest())
        val emptyWindow =
            JudgeContextWindow(
                scopeFingerprint = "empty-scope",
                maxChars = 1_000,
                messages = emptyList(),
                omittedOldestCount = 0,
                quotedSceneData = "unused by prompt assembler",
                retrievalSceneData = "unused by prompt assembler",
            )

        val assembled = NiaJudgePromptAssembler().assemble(sampleRequest().copy(rawContextWindow = emptyWindow))
        val stablePrefix = assembled.prompt.take(assembled.stablePromptPrefixChars)
        val dynamicSuffix = assembled.prompt.drop(assembled.stablePromptPrefixChars)
        val payload = mapper.readTree(assembled.prompt.substringAfter("INPUT_JSON:\n"))
        val rawScene = payload["rawScene"]

        assertThat(assembled.stablePromptPrefixChars).isEqualTo(baseline.stablePromptPrefixChars)
        assertThat(stablePrefix).isEqualTo(baseline.prompt.take(baseline.stablePromptPrefixChars))
        assertThat(stablePrefix).contains("\"rawMessageFields\":[\"ref\",\"speakerLabel\"")
        assertThat(dynamicSuffix).startsWith("\"rawScene\":")
        assertThat(payload["rawMessageFields"].size()).isEqualTo(6)
        assertThat(rawScene["messageFields"]).isNull()
        assertThat(rawScene["messages"].isEmpty).isTrue()
        assertThat(rawScene["latestMessageRef"]).isNull()
    }

    @Test
    fun `global few-shot and retrieved conversation RAG stay separate in every judge input`() {
        val retrieved = sampleFewShotSet().examples.single().copy(exampleId = "rag_41", title = "비슷한 대화")
        val request =
            sampleRequest().copy(
                conversationRag =
                    JudgeConversationRagPayload(
                        listOf(
                            JudgeConversationRagMatchPayload(
                                entryId = 41,
                                score = 0.91,
                                scoringMethod = "EMBEDDING",
                                example = retrieved,
                            ),
                        ),
                    ),
            )

        val assembled = NiaJudgePromptAssembler().assemble(request)
        val payload = mapper.readTree(assembled.prompt.substringAfter("INPUT_JSON:\n"))

        assertThat(payload.at("/fewShotSet/examples/0/exampleId").isMissingNode).isTrue()
        assertThat(payload.at("/fewShotSet/examples/0/title").asText()).isEqualTo("direct reply request")
        assertThat(payload.at("/conversationRag/matches/0/entryId").isMissingNode).isTrue()
        assertThat(payload.at("/conversationRag/matches/0/score").asDouble()).isEqualTo(0.91)
        assertThat(payload.at("/conversationRag/matches/0/example/title").asText()).isEqualTo("비슷한 대화")
        assertThat(assembled.prompt).contains(
            "fewShotSet` is the global judgment constitution included every time",
            "conversationRag` contains only the closest dialogue-library examples retrieved for this scene",
        )
    }

    @Test
    fun `prompt preserves action-specific few-shot payloads`() {
        val source = sampleFewShotSet().examples.single()
        val react =
            source.copy(
                exampleId = "fs_react",
                expectedAction = NiaFewShotAction.REACT,
                expectedDeliveryMode = null,
                currentState = "A reply would be redundant.",
                expectedReactionCode = "eyes",
                badAlternative = JudgeFewShotBadAlternativePayload(NiaFewShotAction.SPEAK, "speech would repeat the answer"),
            )
        val wait =
            source.copy(
                exampleId = "fs_wait",
                expectedAction = NiaFewShotAction.WAIT,
                expectedDeliveryMode = null,
                currentState = "The member is sending a message burst.",
                expectedReevaluateAfterMs = 1_800,
                badAlternative = JudgeFewShotBadAlternativePayload(NiaFewShotAction.SPEAK, "speech would interrupt the burst"),
            )
        val request = sampleRequest().copy(fewShotSet = sampleFewShotSet().copy(examples = listOf(react, wait)))

        val payload = mapper.readTree(NiaJudgePromptAssembler().assemble(request).prompt.substringAfter("INPUT_JSON:\n"))

        assertThat(payload.at("/fewShotSet/examples/0/expectedReactionCode").asText()).isEqualTo("eyes")
        assertThat(payload.at("/fewShotSet/examples/1/expectedReevaluateAfterMs").asLong()).isEqualTo(1_800)
    }

    @Test
    fun `prompt makes a long gap before the current repair question explicit`() {
        val base = sampleRequest()
        val request =
            base.copy(
                rawContextWindow =
                    JudgeContextWindowBuilder(
                        maxRawChars = 1_000,
                        niaAuthorPseudonyms = setOf("nia_bot"),
                    ).build(
                        RawContextSnapshot(
                            scope = scope,
                            entries =
                                listOf(
                                    rawEntry(20L, RawContextSourceType.HUMAN, "니아야 재밌는 얘기 해봐"),
                                    rawEntry(
                                        messageId = 21L,
                                        sourceType = RawContextSourceType.BOT,
                                        text = "알겠어 이번엔 다른 얘기 간다",
                                        occurredAt = now.plusSeconds(1),
                                    ),
                                    rawEntry(
                                        messageId = 22L,
                                        sourceType = RawContextSourceType.HUMAN,
                                        text = "야 왜 계속하냐고",
                                        occurredAt = now.plusSeconds(2),
                                    ),
                                    rawEntry(
                                        messageId = 23L,
                                        sourceType = RawContextSourceType.HUMAN,
                                        text = "니아야 왜 계속하냐고",
                                        occurredAt = now.plusSeconds(2 + 13 * 60 * 60),
                                    ),
                                ),
                        ),
                    ),
            )

        val payload =
            mapper.readTree(
                NiaJudgePromptAssembler().assemble(request).prompt.substringAfter("INPUT_JSON:\n"),
            )

        assertThat(payload.at("/rawScene/latestMessageRef").asText()).isEqualTo("msg_4")
        assertThat(payload.at("/rawScene/messages/1/1").asText()).isEqualTo("nia")
        assertThat(payload.at("/rawScene/messages/3/4").asText()).isEqualTo("니아야 왜 계속하냐고")
        assertThat(payload.at("/rawScene/messages/3/2").asLong())
            .isEqualTo(13 * 60 * 60 * 1_000L)
    }

    @Test
    fun `prompt excludes operational metadata and raw scope identifiers`() {
        val llmRequest = NiaJudgePromptAssembler().assemble(sampleRequest())
        val payload = mapper.readTree(llmRequest.prompt.substringAfter("INPUT_JSON:\n"))

        assertThat(llmRequest.timeoutMillis).isEqualTo(18_000)
        assertThat(payload.at("/metadata").isMissingNode).isTrue()
        assertThat(payload.at("/rawScene/scopeFingerprint").isMissingNode).isTrue()
        assertThat(payload.at("/rawScene/maxChars").isMissingNode).isTrue()
        assertThat(payload.toString()).doesNotContain("guild_a", "channel_a")
        assertThat(llmRequest.toString()).doesNotContain("야 이럴땐 위로해줘")
    }

    @Test
    fun `열린 약속이 있는 장면은 deliberate reasoning으로 승격한다`() {
        val base = sampleRequest()
        val request =
            base.copy(
                sceneSnapshot =
                    base.sceneSnapshot.copy(
                        memoryState = JudgeMemorySceneState(false, null, null, pendingIntentActive = true),
                    ),
            )

        val llmRequest = NiaJudgePromptAssembler().assemble(request)

        assertThat(llmRequest.metadata["reasoning_mode"]).isEqualTo("deliberate")
        assertThat(llmRequest.metadata["execution_purpose"]).isEqualTo("final")
    }

    private fun reconstructRawMessages(payload: JsonNode): List<RawMessageSemantics> {
        val fieldIndexes =
            payload["rawMessageFields"]
                .map { it.asText() }
                .withIndex()
                .associate { (index, field) -> field to index }
        val rawScene = payload["rawScene"]
        return rawScene["messages"].map { row ->
            check(row.size() == fieldIndexes.size) {
                "rawScene message row width mismatch: expected=${fieldIndexes.size}, actual=${row.size()}"
            }
            RawMessageSemantics(
                ref = row[fieldIndexes.getValue("ref")].asText(),
                speakerLabel = row[fieldIndexes.getValue("speakerLabel")].asText(),
                elapsedSincePreviousMs = row[fieldIndexes.getValue("elapsedSincePreviousMs")].nullableLong(),
                replyToRef = row[fieldIndexes.getValue("replyToRef")].nullableText(),
                text = row[fieldIndexes.getValue("text")].nullableText(),
                unavailableReason = row[fieldIndexes.getValue("unavailableReason")].nullableText(),
            )
        }
    }

    private fun JsonNode.nullableLong(): Long? = if (isNull) null else asLong()

    private fun JsonNode.nullableText(): String? = if (isNull) null else asText()

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

    private data class RawMessageSemantics(
        val ref: String,
        val speakerLabel: String,
        val elapsedSincePreviousMs: Long?,
        val replyToRef: String?,
        val text: String?,
        val unavailableReason: String?,
    )

    private fun rawEntry(
        messageId: Long,
        sourceType: RawContextSourceType,
        text: String,
        occurredAt: Instant = now,
        replyToMessageId: Long? = null,
        authorPseudonym: String = if (sourceType == RawContextSourceType.BOT) "nia_bot" else "user_a",
    ): RawContextEntry =
        RawContextEntry(
            scope = scope,
            messageId = messageId,
            authorPseudonym = authorPseudonym,
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
                        expectedDeliveryMode = NiaFewShotDeliveryMode.CHANNEL,
                        currentState = "Direct consolation is expected.",
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
