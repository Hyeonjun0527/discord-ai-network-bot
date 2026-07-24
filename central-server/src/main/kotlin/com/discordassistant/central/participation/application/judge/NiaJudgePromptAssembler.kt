package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.context.JudgeContextContent
import com.discordassistant.central.participation.application.context.JudgeContextMessage
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.NiaPromptTemplate
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

class NiaJudgePromptAssembler(
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
) {
    private val promptMapper = mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL)

    init {
        require(timeoutMillis > 0) { "judge prompt timeoutMillis 는 양수여야 한다: $timeoutMillis" }
    }

    fun assemble(request: SingleJudgeDecisionRequest): NiaJudgeLlmRequest {
        val promptPayload = request.toPromptPayload()
        val payloadJson = promptMapper.writeValueAsString(promptPayload)
        val prompt = buildManagedPrompt(payloadJson)
        return NiaJudgeLlmRequest(
            prompt = prompt.text,
            promptVersion = PROMPT_VERSION,
            seed = request.seed,
            timeoutMillis = timeoutMillis,
            stablePromptPrefixChars = prompt.stablePrefixChars,
            metadata =
                mapOf(
                    "input_schema" to INPUT_SCHEMA,
                    "output_schema" to NiaJudgeLlmRequest.OUTPUT_SCHEMA,
                    "scene_seq" to "${request.sceneSnapshot.ref.sceneSeq}",
                    "context_version" to "${request.sceneSnapshot.ref.contextVersion}",
                    "reasoning_mode" to if (request.requiresDeliberation()) "deliberate" else "fast",
                    EXECUTION_PURPOSE_METADATA_KEY to request.executionPurpose.wireName,
                ),
        )
    }

    private fun buildManagedPrompt(payloadJson: String): ManagedPrompt {
        val template = promptSource.text(NiaPromptKey.JUDGE_TEMPLATE)
        val values =
            mapOf(
                "outputSchema" to NiaJudgeLlmRequest.OUTPUT_SCHEMA,
                "inputJson" to payloadJson,
            )
        val stablePayloadChars = payloadJson.indexOf(RAW_SCENE_FIELD)
        check(stablePayloadChars > 0) { "judge payload에서 rawScene 경계를 찾지 못했다" }
        return ManagedPrompt(
            text = NiaPromptTemplate.render(template, values),
            stablePrefixChars =
                NiaPromptTemplate.stablePrefixChars(
                    template = template,
                    values = values,
                    stableValueChars =
                        mapOf(
                            "outputSchema" to NiaJudgeLlmRequest.OUTPUT_SCHEMA.length,
                            "inputJson" to stablePayloadChars,
                        ),
                ),
        )
    }

    private fun SingleJudgeDecisionRequest.requiresDeliberation(): Boolean {
        val scene = sceneSnapshot
        val competingHypotheses =
            scene.socialBeliefState.intentHypotheses
                .filter { it.status == "ACTIVE" }
                .groupBy(JudgeIntentHypothesisState::participantRef)
                .values
                .any { it.size > 1 }
        val addressConflict = scene.directAddressed && scene.conversationState.humansTalkingToEachOtherLikely
        val openLoop = scene.memoryState.pendingIntentActive == true
        val correctiveOutcome =
            scene.socialBeliefState.recentOutcomes.any {
                it.code == "repetition_complaint" || it.code == "promise_complaint" || it.code == "negative_feedback"
            }
        return competingHypotheses || addressConflict || openLoop || correctiveOutcome
    }

    private fun SingleJudgeDecisionRequest.toPromptPayload(): JudgePromptPayload =
        JudgePromptPayload(
            schema = INPUT_SCHEMA,
            outputSchema = NiaJudgeLlmRequest.OUTPUT_SCHEMA,
            rawScene =
                PromptRawScene(
                    omittedOldestCount = rawContextWindow.omittedOldestCount,
                    latestMessageRef = rawContextWindow.messages.lastOrNull()?.ref,
                    messages = rawContextWindow.messages.toPromptMessages(),
                ),
            fewShotSet = fewShotSet.toPromptFewShotSet(),
            conversationRag = conversationRag.toPromptConversationRag(),
            socialMemory = memoryRefs.map { it.toPromptMemory() },
            sceneState = sceneSnapshot.toPromptSceneState(),
            constraints =
                PromptConstraints(
                    allowedActions = constraints.allowedActions.map { it.toJudgeWireAction() }.sorted(),
                    speechAllowed = constraints.speechAllowed,
                    reactionAllowed = constraints.reactionAllowed,
                    maxDelayMillis = constraints.maxDelayMillis,
                    lowConfidenceFallbackActions =
                        constraints.lowConfidenceFallbackActions.map { it.toJudgeWireAction() }.sorted(),
                ),
        )

    private fun List<JudgeContextMessage>.toPromptMessages(): List<PromptRawMessage> =
        mapIndexed { index, message ->
            val elapsedSincePreviousMs =
                getOrNull(index - 1)?.let { previous ->
                    Duration.between(previous.createdAt, message.createdAt).toMillis()
                }
            message.toPromptMessage(elapsedSincePreviousMs)
        }

    private fun JudgeContextMessage.toPromptMessage(elapsedSincePreviousMs: Long?): PromptRawMessage =
        when (val value = content) {
            is JudgeContextContent.Available ->
                PromptRawMessage(
                    ref = ref,
                    speakerLabel = speakerLabel,
                    elapsedSincePreviousMs = elapsedSincePreviousMs,
                    replyToRef = replyToRef,
                    text = value.text,
                    unavailableReason = null,
                )
            is JudgeContextContent.Unavailable ->
                PromptRawMessage(
                    ref = ref,
                    speakerLabel = speakerLabel,
                    elapsedSincePreviousMs = elapsedSincePreviousMs,
                    replyToRef = replyToRef,
                    text = null,
                    unavailableReason = value.reason,
                )
        }

    private fun JudgeFewShotSetPayload.toPromptFewShotSet(): PromptFewShotSet =
        PromptFewShotSet(
            examples =
                examples.map { example ->
                    PromptFewShotExample(
                        title = example.title,
                        rawMessages =
                            example.rawMessages.map { message ->
                                PromptFewShotRawMessage(
                                    ref = message.ref,
                                    authorRole = message.authorRole,
                                    offsetMs = message.offsetMs,
                                    text = message.text,
                                )
                            },
                        expectedAction = example.expectedAction.name,
                        expectedDeliveryMode = example.expectedDeliveryMode?.name,
                        currentState = example.currentState,
                        expectedReactionCode = example.expectedReactionCode,
                        expectedReevaluateAfterMs = example.expectedReevaluateAfterMs,
                        reason = example.reason,
                        evidenceRefs = example.evidenceRefs.sorted(),
                        badAlternative =
                            PromptFewShotBadAlternative(
                                action = example.badAlternative.action.name,
                                deliveryMode = example.badAlternative.deliveryMode?.name,
                                whyBad = example.badAlternative.whyBad,
                            ),
                    )
                },
        )

    private fun JudgeMemoryRef.toPromptMemory(): PromptMemoryRef =
        PromptMemoryRef(refId = refId, claim = claim, provenance = provenance, confidence = confidence)

    private fun JudgeConversationRagPayload.toPromptConversationRag(): PromptConversationRag =
        PromptConversationRag(
            matches =
                matches.map { match ->
                    PromptConversationRagMatch(
                        score = match.score,
                        scoringMethod = match.scoringMethod,
                        example = match.example.toPromptFewShotExample(),
                    )
                },
        )

    private fun JudgeFewShotExamplePayload.toPromptFewShotExample(): PromptFewShotExample =
        PromptFewShotExample(
            title = title,
            rawMessages =
                rawMessages.map { message ->
                    PromptFewShotRawMessage(message.ref, message.authorRole, message.offsetMs, message.text)
                },
            expectedAction = expectedAction.name,
            expectedDeliveryMode = expectedDeliveryMode?.name,
            currentState = currentState,
            expectedReactionCode = expectedReactionCode,
            expectedReevaluateAfterMs = expectedReevaluateAfterMs,
            reason = reason,
            evidenceRefs = evidenceRefs.sorted(),
            badAlternative =
                PromptFewShotBadAlternative(
                    action = badAlternative.action.name,
                    deliveryMode = badAlternative.deliveryMode?.name,
                    whyBad = badAlternative.whyBad,
                ),
        )

    private fun SingleJudgeSceneSnapshot.toPromptSceneState(): PromptSceneState =
        PromptSceneState(
            directAddressed = directAddressed,
            replyToNia = replyToNia,
            conversationMentionsNia = conversationMentionsNia,
            silenceMillis = silenceMillis,
            pendingActionIds = pendingActionIds.sorted(),
            textSignals = textSignals,
            agentState = agentState,
            conversationState = conversationState,
            turnTakingState = turnTakingState,
            runtimeGuardState = runtimeGuardState,
            relationshipState = relationshipState,
            memoryState = memoryState,
            socialBeliefState = socialBeliefState,
        )

    private fun SocialActionKind.toJudgeWireAction(): String =
        when (this) {
            SocialActionKind.CANCEL_PENDING -> "CANCEL"
            else -> name
        }

    companion object {
        const val EXECUTION_PURPOSE_METADATA_KEY: String = "execution_purpose"
        const val PROMPT_VERSION: String = "nia-judge-prompt-v17"
        const val INPUT_SCHEMA: String = "nia.participation-judge-input.v3"
        const val DEFAULT_TIMEOUT_MILLIS: Long = 18_000
        private const val RAW_SCENE_FIELD: String = "\"rawScene\":"
    }

    private data class ManagedPrompt(
        val text: String,
        val stablePrefixChars: Int,
    )
}

private data class JudgePromptPayload(
    val schema: String,
    val outputSchema: String,
    val fewShotSet: PromptFewShotSet,
    val rawScene: PromptRawScene,
    val conversationRag: PromptConversationRag,
    val socialMemory: List<PromptMemoryRef>,
    val sceneState: PromptSceneState,
    val constraints: PromptConstraints,
)

private data class PromptConversationRag(
    val matches: List<PromptConversationRagMatch>,
)

private data class PromptConversationRagMatch(
    val score: Double,
    val scoringMethod: String,
    val example: PromptFewShotExample,
)

private data class PromptRawScene(
    val omittedOldestCount: Int,
    val latestMessageRef: String?,
    val messages: List<PromptRawMessage>,
)

private data class PromptRawMessage(
    val ref: String,
    val speakerLabel: String,
    val elapsedSincePreviousMs: Long?,
    val replyToRef: String?,
    val text: String?,
    val unavailableReason: String?,
)

private data class PromptFewShotSet(
    val examples: List<PromptFewShotExample>,
)

private data class PromptFewShotExample(
    val title: String,
    val rawMessages: List<PromptFewShotRawMessage>,
    val expectedAction: String,
    val expectedDeliveryMode: String?,
    val currentState: String?,
    val expectedReactionCode: String?,
    val expectedReevaluateAfterMs: Long?,
    val reason: String,
    val evidenceRefs: List<String>,
    val badAlternative: PromptFewShotBadAlternative,
)

private data class PromptFewShotRawMessage(
    val ref: String,
    val authorRole: String,
    val offsetMs: Long,
    val text: String,
)

private data class PromptFewShotBadAlternative(
    val action: String,
    val deliveryMode: String?,
    val whyBad: String,
)

private data class PromptMemoryRef(
    val refId: String,
    val claim: String,
    val provenance: String,
    val confidence: Double,
)

private data class PromptSceneState(
    val directAddressed: Boolean,
    val replyToNia: Boolean,
    val conversationMentionsNia: Boolean,
    val silenceMillis: Long?,
    val pendingActionIds: List<String>,
    val textSignals: JudgeSceneTextSignals,
    val agentState: JudgeAgentSceneState,
    val conversationState: JudgeConversationSceneState,
    val turnTakingState: JudgeTurnTakingSceneState,
    val runtimeGuardState: JudgeRuntimeGuardState,
    val relationshipState: JudgeRelationshipSceneState,
    val memoryState: JudgeMemorySceneState,
    val socialBeliefState: JudgeSocialBeliefState,
)

private data class PromptConstraints(
    val allowedActions: List<String>,
    val speechAllowed: Boolean,
    val reactionAllowed: Boolean,
    val maxDelayMillis: Long,
    val lowConfidenceFallbackActions: List<String>,
)
