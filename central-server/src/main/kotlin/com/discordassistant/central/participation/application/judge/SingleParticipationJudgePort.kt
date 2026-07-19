package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.context.JudgeContextWindow
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotPrivacyClass

interface SingleParticipationJudgePort {
    fun decide(request: SingleJudgeDecisionRequest): SingleJudgeDecision
}

data class SingleJudgeDecisionRequest(
    val rawContextWindow: JudgeContextWindow,
    val sceneSnapshot: SingleJudgeSceneSnapshot,
    val featureVector: FeatureVectorView,
    val fewShotSet: JudgeFewShotSetPayload = JudgeFewShotSetPayload.EMPTY,
    val memoryRefs: List<JudgeMemoryRef>,
    val constraints: JudgeDecisionConstraints,
    val schemaVersion: Int,
    val seed: Long,
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion 은 1 이상이어야 한다: $schemaVersion" }
        require(memoryRefs.size <= MAX_MEMORY_REFS) { "memoryRefs 는 최대 $MAX_MEMORY_REFS 개까지만 담는다: ${memoryRefs.size}" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val MAX_MEMORY_REFS: Int = 8
    }
}

data class JudgeFewShotSetPayload(
    val setId: Long?,
    val version: Int?,
    val examples: List<JudgeFewShotExamplePayload>,
) {
    init {
        setId?.let { require(it > 0) { "few-shot setId 는 양수여야 한다: $it" } }
        version?.let { require(it >= 1) { "few-shot version 은 1 이상이어야 한다: $it" } }
        require((setId == null) == (version == null)) { "few-shot setId 와 version 은 함께 있어야 한다" }
        require(examples.size <= MAX_EXAMPLES) { "few-shot examples 는 최대 $MAX_EXAMPLES 개까지만 담는다" }
        if (examples.isNotEmpty()) {
            require(setId != null && version != null) { "few-shot examples 가 있으면 setId/version 이 필요하다" }
        }
    }

    override fun toString(): String = "JudgeFewShotSetPayload(setId=$setId, version=$version, exampleCount=${examples.size})"

    companion object {
        val EMPTY = JudgeFewShotSetPayload(setId = null, version = null, examples = emptyList())
        const val MAX_EXAMPLES: Int = 64
    }
}

data class JudgeFewShotExamplePayload(
    val exampleId: String,
    val title: String,
    val rawMessages: List<JudgeFewShotRawMessagePayload>,
    val expectedAction: NiaFewShotAction,
    val expectedReplies: List<String> = emptyList(),
    val reason: String,
    val evidenceRefs: Set<String>,
    val badAlternative: JudgeFewShotBadAlternativePayload,
    val tags: Set<String> = emptySet(),
    val priority: Int = 0,
    val privacyClass: NiaFewShotPrivacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
) {
    init {
        require(exampleId.isStableRef()) { "few-shot exampleId 는 안정 ref 여야 한다: $exampleId" }
        require(title.isNotBlank()) { "few-shot title 은 비어 있을 수 없다" }
        require(title.length <= MAX_TITLE_CHARS) { "few-shot title 이 너무 길다" }
        require(rawMessages.isNotEmpty()) { "few-shot rawMessages 는 비어 있을 수 없다" }
        require(rawMessages.size <= MAX_RAW_MESSAGES) { "few-shot rawMessages 가 너무 많다" }
        require(reason.isNotBlank()) { "few-shot reason 은 비어 있을 수 없다" }
        require(reason.length <= MAX_REASON_CHARS) { "few-shot reason 이 너무 길다" }
        require(expectedReplies.size <= MAX_EXPECTED_REPLIES) { "few-shot expectedReplies 가 너무 많다" }
        require(badAlternative.action != expectedAction) { "few-shot badAlternative 는 expectedAction 과 달라야 한다" }
        require(evidenceRefs.isNotEmpty()) { "few-shot evidenceRefs 는 비어 있을 수 없다" }
        evidenceRefs.forEach { require(it.isStableRef()) { "few-shot evidence ref 는 안정 ref 여야 한다: $it" } }
        val rawRefs = rawMessages.map { it.ref }.toSet()
        require(rawRefs.containsAll(evidenceRefs)) { "few-shot evidenceRefs 는 rawMessages ref 를 가리켜야 한다" }
        tags.forEach { require(it.isStableSlug()) { "few-shot tag 는 안정 slug 여야 한다: $it" } }
    }

    override fun toString(): String =
        "JudgeFewShotExamplePayload(exampleId=$exampleId, expectedAction=$expectedAction, rawMessageCount=${rawMessages.size})"

    companion object {
        const val MAX_TITLE_CHARS: Int = 160
        const val MAX_REASON_CHARS: Int = 1_000
        const val MAX_RAW_MESSAGES: Int = 32
        const val MAX_EXPECTED_REPLIES: Int = 4
    }
}

data class JudgeFewShotRawMessagePayload(
    val ref: String,
    val authorRole: String,
    val offsetMs: Long,
    val text: String,
) {
    init {
        require(ref.isStableRef()) { "few-shot raw message ref 는 안정 ref 여야 한다: $ref" }
        require(authorRole.isStableSlug()) { "few-shot authorRole 은 안정 slug 여야 한다: $authorRole" }
        require(text.isNotBlank()) { "few-shot raw message text 는 비어 있을 수 없다" }
        require(text.length <= MAX_TEXT_CHARS) { "few-shot raw message text 가 너무 길다" }
    }

    override fun toString(): String =
        "JudgeFewShotRawMessagePayload(ref=$ref, authorRole=$authorRole, offsetMs=$offsetMs, textLength=${text.length})"

    companion object {
        const val MAX_TEXT_CHARS: Int = 4_000
    }
}

data class JudgeFewShotBadAlternativePayload(
    val action: NiaFewShotAction,
    val whyBad: String,
) {
    init {
        require(whyBad.isNotBlank()) { "few-shot bad alternative reason 은 비어 있을 수 없다" }
        require(whyBad.length <= MAX_REASON_CHARS) { "few-shot bad alternative reason 이 너무 길다" }
    }

    override fun toString(): String = "JudgeFewShotBadAlternativePayload(action=$action, reasonLength=${whyBad.length})"

    companion object {
        const val MAX_REASON_CHARS: Int = 1_000
    }
}

data class SingleJudgeSceneSnapshot(
    val ref: SceneSnapshotRef,
    val directAddressed: Boolean,
    val replyToNia: Boolean,
    val conversationMentionsNia: Boolean,
    val recentAgentBurstCount: Int,
    val silenceMillis: Long?,
    val pendingActionIds: List<String> = emptyList(),
    val textSignals: JudgeSceneTextSignals = JudgeSceneTextSignals.EMPTY,
    val agentState: JudgeAgentSceneState = JudgeAgentSceneState.EMPTY,
    val conversationState: JudgeConversationSceneState = JudgeConversationSceneState.EMPTY,
    val turnTakingState: JudgeTurnTakingSceneState = JudgeTurnTakingSceneState.EMPTY,
    val runtimeGuardState: JudgeRuntimeGuardState = JudgeRuntimeGuardState.EMPTY,
    val relationshipState: JudgeRelationshipSceneState = JudgeRelationshipSceneState.EMPTY,
    val memoryState: JudgeMemorySceneState = JudgeMemorySceneState.EMPTY,
    val socialBeliefState: JudgeSocialBeliefState = JudgeSocialBeliefState.EMPTY,
) {
    init {
        require(recentAgentBurstCount >= 0) { "recentAgentBurstCount 는 음수일 수 없다: $recentAgentBurstCount" }
        silenceMillis?.let { require(it >= 0) { "silenceMillis 는 음수일 수 없다: $it" } }
        pendingActionIds.forEach { pendingActionId ->
            require(pendingActionId.isNotBlank()) { "pendingActionId 는 비어 있을 수 없다" }
        }
    }
}

data class JudgeSocialBeliefState(
    val commonGround: List<JudgeCommonGroundState>,
    val intentHypotheses: List<JudgeIntentHypothesisState>,
    val recentNiaActions: List<JudgeRecentNiaActionState>,
    val recentOutcomes: List<JudgeRecentOutcomeState>,
) {
    init {
        require(commonGround.size <= 20) { "judge 공통 기반은 최대 20개다" }
        require(intentHypotheses.size <= 12) { "judge 의도 가설은 최대 12개다" }
        require(recentNiaActions.size <= 12) { "judge 최근 행동은 최대 12개다" }
        require(recentOutcomes.size <= 12) { "judge 최근 결과는 최대 12개다" }
    }

    companion object {
        val EMPTY = JudgeSocialBeliefState(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

data class JudgeCommonGroundState(
    val code: String,
    val confidence: Double,
    val evidenceRefs: Set<String>,
    val status: String,
)

data class JudgeIntentHypothesisState(
    val participantRef: String,
    val code: String,
    val probability: Double,
    val evidenceRefs: Set<String>,
    val status: String,
)

data class JudgeRecentNiaActionState(
    val actionId: String,
    val actionKind: String,
    val intentSummary: String?,
    val targetMessageRef: String?,
    val contextVersion: Long,
)

data class JudgeRecentOutcomeState(
    val actionId: String,
    val code: String,
    val evidenceRef: String,
)

data class JudgeRelationshipSceneState(
    val familiarity: Double?,
    val reciprocity: Double?,
    val banterAcceptance: Double?,
    val sampleConfidence: Double?,
) {
    init {
        validateNullableAxis("familiarity", familiarity)
        validateNullableAxis("reciprocity", reciprocity)
        validateNullableAxis("banterAcceptance", banterAcceptance)
        validateNullableAxis("sampleConfidence", sampleConfidence)
    }

    companion object {
        val EMPTY =
            JudgeRelationshipSceneState(
                familiarity = null,
                reciprocity = null,
                banterAcceptance = null,
                sampleConfidence = null,
            )
    }
}

data class JudgeMemorySceneState(
    val relevantPresent: Boolean?,
    val topConfidence: Double?,
    val freshestAgeSeconds: Double?,
    val pendingIntentActive: Boolean?,
) {
    init {
        validateNullableAxis("topConfidence", topConfidence)
        freshestAgeSeconds?.let { require(it >= 0.0) { "freshestAgeSeconds 는 음수일 수 없다: $it" } }
    }

    companion object {
        val EMPTY =
            JudgeMemorySceneState(
                relevantPresent = null,
                topConfidence = null,
                freshestAgeSeconds = null,
                pendingIntentActive = null,
            )
    }
}

data class JudgeSceneTextSignals(
    val contentAvailable: Boolean,
    val isQuestion: Boolean,
    val replyTargetKind: String,
    val emotionalIntensity: Double,
    val callPressure: Double,
    val stopRequested: Boolean = false,
    val otherAddresseeLikely: Boolean = false,
) {
    init {
        require(replyTargetKind.isNotBlank()) { "replyTargetKind 는 비어 있을 수 없다" }
        require(emotionalIntensity in 0.0..1.0) { "emotionalIntensity 는 [0,1] 범위여야 한다: $emotionalIntensity" }
        require(callPressure in 0.0..1.0) { "callPressure 는 [0,1] 범위여야 한다: $callPressure" }
    }

    companion object {
        val EMPTY: JudgeSceneTextSignals =
            JudgeSceneTextSignals(
                contentAvailable = false,
                isQuestion = false,
                replyTargetKind = "none",
                emotionalIntensity = 0.0,
                callPressure = 0.0,
                stopRequested = false,
                otherAddresseeLikely = false,
            )
    }
}

data class JudgeAgentSceneState(
    val recentSpeechCount: Int,
    val lastSpokeAgeSeconds: Double?,
) {
    init {
        require(recentSpeechCount >= 0) { "recentSpeechCount 는 음수일 수 없다: $recentSpeechCount" }
        lastSpokeAgeSeconds?.let { require(it >= 0.0) { "lastSpokeAgeSeconds 는 음수일 수 없다: $it" } }
    }

    companion object {
        val EMPTY: JudgeAgentSceneState = JudgeAgentSceneState(recentSpeechCount = 0, lastSpokeAgeSeconds = null)
    }
}

data class JudgeConversationSceneState(
    val humanLikelyAnswering: Boolean,
    val idleGapLikely: Boolean,
    val resolvedLikely: Boolean,
    val humansTalkingToEachOtherLikely: Boolean = false,
    val niaAddressedOrIdleOpportunity: Boolean = false,
    val niaTurnContinuationLikely: Boolean = false,
) {
    companion object {
        val EMPTY: JudgeConversationSceneState =
            JudgeConversationSceneState(
                humanLikelyAnswering = false,
                idleGapLikely = false,
                resolvedLikely = false,
                humansTalkingToEachOtherLikely = false,
                niaAddressedOrIdleOpportunity = false,
                niaTurnContinuationLikely = false,
            )
    }
}

data class JudgeTurnTakingSceneState(
    val directAddressPressure: Double,
    val replyChainDepth: Int,
    val nicknameCall: Boolean,
    val previousIgnoredRequestCount: Int,
) {
    init {
        require(directAddressPressure in 0.0..1.0) {
            "directAddressPressure 는 [0,1] 범위여야 한다: $directAddressPressure"
        }
        require(replyChainDepth >= 0) { "replyChainDepth 는 음수일 수 없다: $replyChainDepth" }
        require(previousIgnoredRequestCount >= 0) {
            "previousIgnoredRequestCount 는 음수일 수 없다: $previousIgnoredRequestCount"
        }
    }

    companion object {
        val EMPTY =
            JudgeTurnTakingSceneState(
                directAddressPressure = 0.0,
                replyChainDepth = 0,
                nicknameCall = false,
                previousIgnoredRequestCount = 0,
            )
    }
}

data class JudgeRuntimeGuardState(
    val rateLimitPressure: Double,
    val antiSpamPressure: Double,
) {
    init {
        require(rateLimitPressure in 0.0..1.0) { "rateLimitPressure 는 [0,1] 범위여야 한다: $rateLimitPressure" }
        require(antiSpamPressure in 0.0..1.0) { "antiSpamPressure 는 [0,1] 범위여야 한다: $antiSpamPressure" }
    }

    companion object {
        val EMPTY = JudgeRuntimeGuardState(rateLimitPressure = 0.0, antiSpamPressure = 0.0)
    }
}

data class JudgeMemoryRef(
    val refId: String,
    val claim: String,
    val provenance: String,
    val confidence: Double,
) {
    init {
        require(refId.isNotBlank()) { "memory refId 는 비어 있을 수 없다" }
        require(claim.isNotBlank()) { "memory claim 은 비어 있을 수 없다" }
        require(provenance.isNotBlank()) { "memory provenance 는 비어 있을 수 없다" }
        require(confidence in 0.0..1.0) { "memory confidence 는 [0,1] 범위여야 한다: $confidence" }
    }
}

data class JudgeDecisionConstraints(
    val allowedActions: Set<SocialActionKind>,
    val speechAllowed: Boolean,
    val reactionAllowed: Boolean,
    val maxDelayMillis: Long,
    val lowConfidenceFallbackActions: Set<SocialActionKind> = setOf(SocialActionKind.WAIT, SocialActionKind.IGNORE),
) {
    init {
        require(allowedActions.isNotEmpty()) { "allowedActions 는 비어 있을 수 없다" }
        require(maxDelayMillis >= 0) { "maxDelayMillis 는 음수일 수 없다: $maxDelayMillis" }
        require(!hasGateConflict(SocialActionKind.SPEAK, speechAllowed)) {
            "speechAllowed=false 이면 SPEAK 는 allowedActions 에 들어갈 수 없다"
        }
        require(!hasGateConflict(SocialActionKind.REACT, reactionAllowed)) {
            "reactionAllowed=false 이면 REACT 는 allowedActions 에 들어갈 수 없다"
        }
        require(lowConfidenceFallbackActions.isNotEmpty()) { "lowConfidenceFallbackActions 는 비어 있을 수 없다" }
        require(lowConfidenceFallbackActions.all { it in allowedActions }) {
            "lowConfidenceFallbackActions 는 allowedActions 안에서만 고를 수 있다"
        }
        require(lowConfidenceFallbackActions.all { it == SocialActionKind.WAIT || it == SocialActionKind.IGNORE }) {
            "lowConfidenceFallbackActions 는 WAIT 또는 IGNORE 만 허용한다"
        }
    }

    private fun hasGateConflict(
        action: SocialActionKind,
        allowed: Boolean,
    ): Boolean = !allowed && action in allowedActions
}

data class SingleJudgeDecision(
    val action: SocialActionKind,
    val confidence: Double,
    val delay: JudgeDecisionDelay,
    val reactionCandidate: JudgeReactionCandidate?,
    val speechIntent: JudgeSpeechIntent?,
    val toneAxes: JudgeToneAxes,
    val reasonCode: JudgeReasonCode,
    val beliefDelta: JudgeBeliefDelta = JudgeBeliefDelta.EMPTY,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence 는 [0,1] 범위여야 한다: $confidence" }
        when (action) {
            SocialActionKind.SPEAK -> require(speechIntent != null) { "SPEAK 결정에는 speechIntent 가 필요하다" }
            SocialActionKind.REACT -> require(reactionCandidate != null) { "REACT 결정에는 reactionCandidate 가 필요하다" }
            SocialActionKind.WAIT ->
                require(delay.millis > 0 || !delay.wakeUpHint.isNullOrBlank()) {
                    "WAIT 결정에는 양수 delay 또는 wakeUpHint 가 필요하다"
                }
            SocialActionKind.IGNORE,
            SocialActionKind.CANCEL_PENDING,
            -> Unit
        }
    }
}

/** judge가 새 관찰 근거로 제안하는 수정 가능한 믿음 상태 갱신이다. */
data class JudgeBeliefDelta(
    val commonGround: List<JudgeCommonGroundUpdate> = emptyList(),
    val intentHypotheses: List<JudgeIntentHypothesisUpdate> = emptyList(),
    val commitments: List<JudgeCommitmentUpdate> = emptyList(),
) {
    init {
        require(commonGround.size <= MAX_UPDATES) { "공통 기반 갱신은 최대 $MAX_UPDATES 개다" }
        require(intentHypotheses.size <= MAX_UPDATES) { "의도 가설 갱신은 최대 $MAX_UPDATES 개다" }
        require(commitments.size <= MAX_UPDATES) { "약속 갱신은 최대 $MAX_UPDATES 개다" }
    }

    companion object {
        val EMPTY = JudgeBeliefDelta()
        const val MAX_UPDATES: Int = 8
    }
}

data class JudgeCommitmentUpdate(
    val commitmentRef: String,
    val topic: String,
    val socialAct: String,
    val evidenceRefs: Set<String>,
    val confidence: Double,
    val status: JudgeCommitmentStatus,
) {
    init {
        require(commitmentRef.isStableRef()) { "commitmentRef 형식이 잘못됐다: $commitmentRef" }
        require(topic.isNotBlank() && topic.length <= 256) { "약속 topic이 비어 있거나 너무 길다" }
        require(socialAct in ALLOWED_SOCIAL_ACTS) { "지원하지 않는 약속 socialAct다: $socialAct" }
        require(evidenceRefs.isNotEmpty() && evidenceRefs.all(String::isStableRef)) { "약속에는 근거 ref가 필요하다" }
        require(confidence in 0.0..1.0) { "약속 confidence는 [0,1]이어야 한다" }
    }

    companion object {
        val ALLOWED_SOCIAL_ACTS = setOf("REPLY", "FIND_INFORMATION", "FOLLOW_UP", "APOLOGIZE", "TELL_STORY", "EXPLAIN", "ANSWER")
    }
}

enum class JudgeCommitmentStatus {
    ACTIVE,
    COMPLETED,
    REJECTED,
}

data class JudgeCommonGroundUpdate(
    val code: String,
    val confidence: Double,
    val evidenceRefs: Set<String>,
    val status: JudgeBeliefStatus = JudgeBeliefStatus.ACTIVE,
) {
    init {
        require(code.matches(Regex("[a-z0-9][a-z0-9_.-]{0,159}"))) { "공통 기반 code 형식이 잘못됐다: $code" }
        require(confidence in 0.0..1.0) { "공통 기반 confidence 는 [0,1]이어야 한다" }
        require(evidenceRefs.isNotEmpty() && evidenceRefs.all(String::isStableRef)) { "공통 기반에는 근거 ref가 필요하다" }
    }
}

data class JudgeIntentHypothesisUpdate(
    val participantRef: String,
    val code: String,
    val probability: Double,
    val evidenceRefs: Set<String>,
    val status: JudgeBeliefStatus = JudgeBeliefStatus.ACTIVE,
) {
    init {
        require(participantRef.isStableRef()) { "participantRef 형식이 잘못됐다: $participantRef" }
        require(code.matches(Regex("[a-z0-9][a-z0-9_.-]{0,159}"))) { "의도 가설 code 형식이 잘못됐다: $code" }
        require(probability in 0.0..1.0) { "의도 가설 probability 는 [0,1]이어야 한다" }
        require(evidenceRefs.isNotEmpty() && evidenceRefs.all(String::isStableRef)) { "의도 가설에는 근거 ref가 필요하다" }
    }
}

enum class JudgeBeliefStatus {
    ACTIVE,
    SUPERSEDED,
    REJECTED,
}

data class JudgeDecisionDelay(
    val millis: Long,
    val wakeUpHint: String? = null,
) {
    init {
        require(millis >= 0) { "delay millis 는 음수일 수 없다: $millis" }
        wakeUpHint?.let { require(it.isNotBlank()) { "wakeUpHint 는 비어 있을 수 없다" } }
    }

    companion object {
        val IMMEDIATE: JudgeDecisionDelay = JudgeDecisionDelay(0)
    }
}

data class JudgeReactionCandidate(
    val reactionCode: String,
    val fallbackAction: SocialActionKind = SocialActionKind.IGNORE,
) {
    init {
        require(reactionCode.isNotBlank()) { "reactionCode 는 비어 있을 수 없다" }
        require(fallbackAction != SocialActionKind.REACT) { "reaction fallbackAction 은 REACT 일 수 없다" }
    }
}

data class JudgeSpeechIntent(
    val intentSummary: String,
    val sceneDirection: String,
    val actHint: String? = null,
    val bubbleCount: Int = 1,
    val maxBubbleChars: Int = DEFAULT_MAX_BUBBLE_CHARS,
    val interactionReading: String = intentSummary,
    val informationDepth: String = sceneDirection,
    val continuityRefs: Set<String> = emptySet(),
) {
    init {
        require(intentSummary.isNotBlank()) { "intentSummary 는 비어 있을 수 없다" }
        require(sceneDirection.isNotBlank()) { "sceneDirection 은 비어 있을 수 없다" }
        require(interactionReading.isNotBlank()) { "interactionReading 은 비어 있을 수 없다" }
        require(informationDepth.isNotBlank()) { "informationDepth 는 비어 있을 수 없다" }
        actHint?.let { require(it.isNotBlank()) { "actHint 는 비어 있을 수 없다" } }
        continuityRefs.forEach { require(it.isStableRef()) { "continuityRef 는 안정 ref 여야 한다: $it" } }
        require(bubbleCount in MIN_BUBBLE_COUNT..MAX_BUBBLE_COUNT) {
            "bubbleCount 는 $MIN_BUBBLE_COUNT..$MAX_BUBBLE_COUNT 범위여야 한다: $bubbleCount"
        }
        require(maxBubbleChars in MIN_MAX_BUBBLE_CHARS..MAX_MAX_BUBBLE_CHARS) {
            "maxBubbleChars 는 $MIN_MAX_BUBBLE_CHARS..$MAX_MAX_BUBBLE_CHARS 범위여야 한다: $maxBubbleChars"
        }
    }

    companion object {
        const val MIN_BUBBLE_COUNT: Int = 1
        const val MAX_BUBBLE_COUNT: Int = 4
        const val MIN_MAX_BUBBLE_CHARS: Int = 40
        const val MAX_MAX_BUBBLE_CHARS: Int = 1_800
        const val DEFAULT_MAX_BUBBLE_CHARS: Int = 280
    }
}

data class JudgeToneAxes(
    val warmth: Double,
    val playfulness: Double,
    val directness: Double,
    val emotionalIntensity: Double,
) {
    init {
        validateAxis("warmth", warmth)
        validateAxis("playfulness", playfulness)
        validateAxis("directness", directness)
        validateAxis("emotionalIntensity", emotionalIntensity)
    }

    companion object {
        val NEUTRAL: JudgeToneAxes =
            JudgeToneAxes(
                warmth = 0.5,
                playfulness = 0.0,
                directness = 0.5,
                emotionalIntensity = 0.0,
            )

        private fun validateAxis(
            name: String,
            value: Double,
        ) {
            require(value in 0.0..1.0) { "$name 축은 [0,1] 범위여야 한다: $value" }
        }
    }
}

private fun validateNullableAxis(
    name: String,
    value: Double?,
) {
    value?.let { require(it in 0.0..1.0) { "$name 축은 [0,1] 범위여야 한다: $it" } }
}

private fun String.isStableRef(): Boolean = matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))

private fun String.isStableSlug(): Boolean = matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))

@JvmInline
value class JudgeReasonCode(
    val code: String,
) {
    init {
        require(code.matches(CODE_PATTERN)) { "reasonCode 는 소문자 안정 코드여야 한다: $code" }
    }

    companion object {
        private val CODE_PATTERN = Regex("[a-z0-9_.-]+")
    }
}
