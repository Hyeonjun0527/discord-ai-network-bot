package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.context.JudgeContextContent
import com.discordassistant.central.participation.application.context.JudgeContextMessage
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.NiaPromptTemplate
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

class NiaJudgePromptAssembler(
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
) {
    init {
        require(timeoutMillis > 0) { "judge prompt timeoutMillis 는 양수여야 한다: $timeoutMillis" }
    }

    fun assemble(request: SingleJudgeDecisionRequest): NiaJudgeLlmRequest {
        val promptPayload = request.toPromptPayload()
        val payloadJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(promptPayload)
        return NiaJudgeLlmRequest(
            prompt = buildManagedPrompt(payloadJson),
            promptVersion = PROMPT_VERSION,
            seed = request.seed,
            timeoutMillis = timeoutMillis,
            metadata =
                mapOf(
                    "input_schema" to INPUT_SCHEMA,
                    "output_schema" to NiaJudgeLlmRequest.OUTPUT_SCHEMA,
                    "scene_seq" to "${request.sceneSnapshot.ref.sceneSeq}",
                    "context_version" to "${request.sceneSnapshot.ref.contextVersion}",
                    "reasoning_mode" to if (request.requiresDeliberation()) "deliberate" else "fast",
                ),
        )
    }

    private fun buildManagedPrompt(payloadJson: String): String =
        NiaPromptTemplate.render(
            promptSource.text(NiaPromptKey.JUDGE_TEMPLATE),
            mapOf(
                "outputSchema" to NiaJudgeLlmRequest.OUTPUT_SCHEMA,
                "inputJson" to payloadJson,
            ),
        )

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
                    scopeFingerprint = rawContextWindow.scopeFingerprint,
                    maxChars = rawContextWindow.maxChars,
                    omittedOldestCount = rawContextWindow.omittedOldestCount,
                    quotedSceneData = rawContextWindow.quotedSceneData,
                    latestMessageRef = rawContextWindow.messages.lastOrNull()?.ref,
                    messages = rawContextWindow.messages.toPromptMessages(),
                ),
            fewShotSet = fewShotSet.toPromptFewShotSet(),
            conversationRag = conversationRag.toPromptConversationRag(),
            socialMemory = memoryRefs.map { it.toPromptMemory() },
            metadata =
                PromptMetadata(
                    sceneSeq = sceneSnapshot.ref.sceneSeq,
                    contextVersion = sceneSnapshot.ref.contextVersion,
                    schemaVersion = schemaVersion,
                    featureVectorVersion = featureVector.version,
                    seed = seed,
                ),
            sceneState = sceneSnapshot.toPromptSceneState(),
            featureVector =
                featureVector.features
                    .toSortedMap(compareBy { it.id })
                    .mapKeys { it.key.id }
                    .mapValues { (_, feature) -> PromptFeatureValue(feature.value, feature.missing) },
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
                    authorRole = authorRole,
                    createdAt = createdAt.toString(),
                    elapsedSincePreviousMs = elapsedSincePreviousMs,
                    replyToRef = replyToRef,
                    text = value.text,
                    unavailableReason = null,
                )
            is JudgeContextContent.Unavailable ->
                PromptRawMessage(
                    ref = ref,
                    authorRole = authorRole,
                    createdAt = createdAt.toString(),
                    elapsedSincePreviousMs = elapsedSincePreviousMs,
                    replyToRef = replyToRef,
                    text = null,
                    unavailableReason = value.reason,
                )
        }

    private fun JudgeFewShotSetPayload.toPromptFewShotSet(): PromptFewShotSet =
        PromptFewShotSet(
            setId = setId,
            version = version,
            examples =
                examples.map { example ->
                    PromptFewShotExample(
                        exampleId = example.exampleId,
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
                        tags = example.tags.sorted(),
                        priority = example.priority,
                        privacyClass = example.privacyClass.name,
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
                        entryId = match.entryId,
                        score = match.score,
                        scoringMethod = match.scoringMethod,
                        example = match.example.toPromptFewShotExample(),
                    )
                },
        )

    private fun JudgeFewShotExamplePayload.toPromptFewShotExample(): PromptFewShotExample =
        PromptFewShotExample(
            exampleId = exampleId,
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
            tags = tags.sorted(),
            priority = priority,
            privacyClass = privacyClass.name,
        )

    private fun SingleJudgeSceneSnapshot.toPromptSceneState(): PromptSceneState =
        PromptSceneState(
            directAddressed = directAddressed,
            replyToNia = replyToNia,
            conversationMentionsNia = conversationMentionsNia,
            recentAgentBurstCount = recentAgentBurstCount,
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
        const val PROMPT_VERSION: String = "nia-judge-prompt-v13"
        const val INPUT_SCHEMA: String = "nia.participation-judge-input.v1"
        const val DEFAULT_TIMEOUT_MILLIS: Long = 18_000
    }
}

private data class JudgePromptPayload(
    val schema: String,
    val outputSchema: String,
    val rawScene: PromptRawScene,
    val fewShotSet: PromptFewShotSet,
    val conversationRag: PromptConversationRag,
    val socialMemory: List<PromptMemoryRef>,
    val metadata: PromptMetadata,
    val sceneState: PromptSceneState,
    val featureVector: Map<String, PromptFeatureValue>,
    val constraints: PromptConstraints,
)

private data class PromptConversationRag(
    val matches: List<PromptConversationRagMatch>,
)

private data class PromptConversationRagMatch(
    val entryId: Long,
    val score: Double,
    val scoringMethod: String,
    val example: PromptFewShotExample,
)

private data class PromptRawScene(
    val scopeFingerprint: String,
    val maxChars: Int,
    val omittedOldestCount: Int,
    val quotedSceneData: String,
    val latestMessageRef: String?,
    val messages: List<PromptRawMessage>,
)

private data class PromptRawMessage(
    val ref: String,
    val authorRole: String,
    val createdAt: String,
    val elapsedSincePreviousMs: Long?,
    val replyToRef: String?,
    val text: String?,
    val unavailableReason: String?,
)

private data class PromptFewShotSet(
    val setId: Long?,
    val version: Int?,
    val examples: List<PromptFewShotExample>,
)

private data class PromptFewShotExample(
    val exampleId: String,
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
    val tags: List<String>,
    val priority: Int,
    val privacyClass: String,
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

private data class PromptMetadata(
    val sceneSeq: Long,
    val contextVersion: Long,
    val schemaVersion: Int,
    val featureVectorVersion: Int,
    val seed: Long,
)

private data class PromptSceneState(
    val directAddressed: Boolean,
    val replyToNia: Boolean,
    val conversationMentionsNia: Boolean,
    val recentAgentBurstCount: Int,
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

private data class PromptFeatureValue(
    val value: Double,
    val missing: Boolean,
)

private data class PromptConstraints(
    val allowedActions: List<String>,
    val speechAllowed: Boolean,
    val reactionAllowed: Boolean,
    val maxDelayMillis: Long,
    val lowConfidenceFallbackActions: List<String>,
)
