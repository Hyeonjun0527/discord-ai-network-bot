package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.context.JudgeContextWindow
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind

interface SingleParticipationJudgePort {
    fun decide(request: SingleJudgeDecisionRequest): SingleJudgeDecision
}

data class SingleJudgeDecisionRequest(
    val rawContextWindow: JudgeContextWindow,
    val sceneSnapshot: SingleJudgeSceneSnapshot,
    val featureVector: FeatureVectorView,
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
) {
    init {
        require(recentAgentBurstCount >= 0) { "recentAgentBurstCount 는 음수일 수 없다: $recentAgentBurstCount" }
        silenceMillis?.let { require(it >= 0) { "silenceMillis 는 음수일 수 없다: $it" } }
        pendingActionIds.forEach { pendingActionId ->
            require(pendingActionId.isNotBlank()) { "pendingActionId 는 비어 있을 수 없다" }
        }
    }
}

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
) {
    companion object {
        val EMPTY: JudgeConversationSceneState =
            JudgeConversationSceneState(
                humanLikelyAnswering = false,
                idleGapLikely = false,
                resolvedLikely = false,
                humansTalkingToEachOtherLikely = false,
                niaAddressedOrIdleOpportunity = false,
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
) {
    init {
        require(intentSummary.isNotBlank()) { "intentSummary 는 비어 있을 수 없다" }
        require(sceneDirection.isNotBlank()) { "sceneDirection 은 비어 있을 수 없다" }
        actHint?.let { require(it.isNotBlank()) { "actHint 는 비어 있을 수 없다" } }
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
