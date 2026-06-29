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
) {
    init {
        require(recentAgentBurstCount >= 0) { "recentAgentBurstCount 는 음수일 수 없다: $recentAgentBurstCount" }
        silenceMillis?.let { require(it >= 0) { "silenceMillis 는 음수일 수 없다: $it" } }
        pendingActionIds.forEach { pendingActionId ->
            require(pendingActionId.isNotBlank()) { "pendingActionId 는 비어 있을 수 없다" }
        }
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
