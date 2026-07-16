package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.context.JudgeContextContent
import com.discordassistant.central.participation.application.context.JudgeContextMessage
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class NiaJudgePromptAssembler(
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis > 0) { "judge prompt timeoutMillis 는 양수여야 한다: $timeoutMillis" }
    }

    fun assemble(request: SingleJudgeDecisionRequest): NiaJudgeLlmRequest {
        val promptPayload = request.toPromptPayload()
        val payloadJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(promptPayload)
        return NiaJudgeLlmRequest(
            prompt = buildPrompt(payloadJson),
            promptVersion = PROMPT_VERSION,
            seed = request.seed,
            timeoutMillis = timeoutMillis,
            metadata =
                mapOf(
                    "input_schema" to INPUT_SCHEMA,
                    "output_schema" to NiaJudgeLlmRequest.OUTPUT_SCHEMA,
                    "scene_seq" to "${request.sceneSnapshot.ref.sceneSeq}",
                    "context_version" to "${request.sceneSnapshot.ref.contextVersion}",
                ),
        )
    }

    private fun buildPrompt(payloadJson: String): String =
        """
        You are NIA's single participation judge.
        Decide exactly one action for the current Discord scene: IGNORE, WAIT, REACT, SPEAK, or CANCEL.
        Use the raw scene text as the primary source. Use few-shot examples as the judgment constitution.
        Use memory and metadata only as secondary support when they do not contradict the raw scene.

        NIA is one participant in a multi-person conversation, not an answer API that must respond to every message.
        Before choosing an action, infer who the current turn is addressed to, who owns the conversational turn, whether
        NIA's participation is expected, and whether speaking would help the scene or interrupt it. Topic words or a
        third-person topic words or an older name reference alone do not decide this; the whole raw scene and the
        relationships between turns do. A direct mention, reply, or name call in the current turn is different: when
        it addresses NIA and no newer correction, withdrawal, addressee change, or stop request supersedes it, choose
        SPEAK. Repeated direct calls are not a reason to stay silent; acknowledge the repetition briefly and naturally
        instead of restarting a generic greeting.
        Silence is a successful action when another member is being addressed, humans are naturally continuing with each
        other, NIA has been asked to yield, or NIA speaking would make her the center of a conversation she does not own.
        If NIA's own preceding response was a mistaken interruption, a single brief SPEAK to acknowledge the mistake and
        yield can be natural. It must not turn into more questions, probing, or a new attempt to take over the topic.
        Treat a newer correction, withdrawal, or addressee change as stronger evidence than older name, mention, or
        reply signals. If NIA has not spoken after an invitation was retracted, IGNORE is normally the repair; if she
        just interrupted, at most one brief acknowledgement and yield is appropriate.
        After yielding, judge every later turn from the raw scene again; re-enter only when NIA is genuinely addressed or
        her input is clearly invited. As rejection or frustration becomes stronger, prefer a shorter acknowledgement or
        silence. Do not encode this as keyword matching; reason from the social situation in the scene.

        Return only JSON matching ${NiaJudgeLlmRequest.OUTPUT_SCHEMA}.
        Do not include final response text, utterance, message, or content for SPEAK.
        For SPEAK, include only intent-level speechIntent fields for the speech pipeline.

        INPUT_JSON:
        $payloadJson
        """.trimIndent()

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
                    messages = rawContextWindow.messages.map { it.toPromptMessage() },
                ),
            fewShotSet = fewShotSet.toPromptFewShotSet(),
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

    private fun JudgeContextMessage.toPromptMessage(): PromptRawMessage =
        when (val value = content) {
            is JudgeContextContent.Available ->
                PromptRawMessage(
                    ref = ref,
                    authorRole = authorRole,
                    createdAt = createdAt.toString(),
                    replyToRef = replyToRef,
                    text = value.text,
                    unavailableReason = null,
                )
            is JudgeContextContent.Unavailable ->
                PromptRawMessage(
                    ref = ref,
                    authorRole = authorRole,
                    createdAt = createdAt.toString(),
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
                        reason = example.reason,
                        evidenceRefs = example.evidenceRefs.sorted(),
                        badAlternative =
                            PromptFewShotBadAlternative(
                                action = example.badAlternative.action.name,
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
        )

    private fun SocialActionKind.toJudgeWireAction(): String =
        when (this) {
            SocialActionKind.CANCEL_PENDING -> "CANCEL"
            else -> name
        }

    companion object {
        const val PROMPT_VERSION: String = "nia-judge-prompt-v4"
        const val INPUT_SCHEMA: String = "nia.participation-judge-input.v1"
        const val DEFAULT_TIMEOUT_MILLIS: Long = 8_000
    }
}

private data class JudgePromptPayload(
    val schema: String,
    val outputSchema: String,
    val rawScene: PromptRawScene,
    val fewShotSet: PromptFewShotSet,
    val socialMemory: List<PromptMemoryRef>,
    val metadata: PromptMetadata,
    val sceneState: PromptSceneState,
    val featureVector: Map<String, PromptFeatureValue>,
    val constraints: PromptConstraints,
)

private data class PromptRawScene(
    val scopeFingerprint: String,
    val maxChars: Int,
    val omittedOldestCount: Int,
    val quotedSceneData: String,
    val messages: List<PromptRawMessage>,
)

private data class PromptRawMessage(
    val ref: String,
    val authorRole: String,
    val createdAt: String,
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
