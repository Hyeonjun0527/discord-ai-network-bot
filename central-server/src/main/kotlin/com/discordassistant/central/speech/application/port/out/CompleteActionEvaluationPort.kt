package com.discordassistant.central.speech.application.port.out

import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.SpeechSocialAct

/** 실제 문구를 포함한 SEND와 REACT·IGNORE를 같은 후보 집합에서 비교하는 외부 가치 평가 경계다. */
fun interface CompleteActionEvaluationPort {
    fun select(request: CompleteActionEvaluationRequest): CompleteActionEvaluation?

    data object Noop : CompleteActionEvaluationPort {
        override fun select(request: CompleteActionEvaluationRequest): CompleteActionEvaluation? = null
    }
}

data class CompleteActionEvaluationRequest(
    val focusThreadKey: String,
    val provisionalDecision: String,
    val provisionalConfidence: Double,
    val speechIntent: String?,
    val socialAct: SpeechSocialAct,
    val stateRefs: List<String>,
    val recentTurns: List<ConversationTurn>,
    val rawContextSceneData: String?,
    val contextVersion: Long,
    val seed: Long,
    val triggerMessageRef: String?,
    val enforcementConstraints: Set<String>,
    val candidates: List<CompleteActionCandidate>,
) {
    init {
        require(focusThreadKey.isNotBlank()) { "focusThreadKey 는 비어 있을 수 없다" }
        require(provisionalDecision in setOf("SPEAK", "WAIT", "REACT", "IGNORE")) {
            "지원하지 않는 잠정 행동이다: $provisionalDecision"
        }
        require(provisionalConfidence in 0.0..1.0) { "provisionalConfidence 는 [0,1]이어야 한다" }
        require(contextVersion >= 0) { "contextVersion 은 음수일 수 없다" }
        require(stateRefs.size <= MAX_STATE_REFS && stateRefs.all(String::isStableActionRef)) {
            "stateRefs 는 안정 참조 $MAX_STATE_REFS 개 이하여야 한다"
        }
        require(triggerMessageRef == null || triggerMessageRef.isStableActionRef()) { "triggerMessageRef 형식이 잘못됐다" }
        require(enforcementConstraints.isNotEmpty() && enforcementConstraints.all(String::isStableConstraint)) {
            "enforcementConstraints 는 안정 코드여야 한다"
        }
        require(candidates.isNotEmpty()) { "완전 행동 후보는 하나 이상이어야 한다" }
        require(candidates.map(CompleteActionCandidate::candidateId).distinct().size == candidates.size) {
            "완전 행동 후보 ID는 중복될 수 없다"
        }
    }

    private companion object {
        const val MAX_STATE_REFS: Int = 16
    }
}

data class CompleteActionCandidate(
    val candidateId: String,
    val kind: CompleteActionKind,
    val bubbles: List<String> = emptyList(),
    val reactionCode: String? = null,
) {
    init {
        require(candidateId.matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))) { "후보 ID 형식이 잘못됐다: $candidateId" }
        when (kind) {
            CompleteActionKind.SEND -> require(bubbles.isNotEmpty() && bubbles.all(String::isNotBlank)) { "SEND에는 실제 문구가 필요하다" }
            CompleteActionKind.REACT -> require(!reactionCode.isNullOrBlank()) { "REACT에는 reactionCode가 필요하다" }
            CompleteActionKind.IGNORE -> require(bubbles.isEmpty() && reactionCode == null) { "IGNORE에는 payload가 없어야 한다" }
        }
    }
}

enum class CompleteActionKind {
    SEND,
    REACT,
    IGNORE,
}

data class CompleteActionEvaluation(
    val selectedCandidateId: String,
    val predictedOutcome: String,
    val reasonCode: String,
    val confidence: Double,
) {
    init {
        require(selectedCandidateId.isNotBlank()) { "선택 후보 ID는 비어 있을 수 없다" }
        require(predictedOutcome.isNotBlank() && predictedOutcome.length <= 400) { "예상 결과가 비어 있거나 너무 길다" }
        require(reasonCode.matches(Regex("[A-Z0-9_]{1,80}"))) { "reasonCode 형식이 잘못됐다: $reasonCode" }
        require(confidence in 0.0..1.0) { "confidence 는 0.0..1.0 범위여야 한다" }
    }
}

private fun String.isStableActionRef(): Boolean = matches(Regex("[A-Za-z0-9_:.=-]{1,256}"))

private fun String.isStableConstraint(): Boolean = matches(Regex("[A-Z0-9_:-]{1,96}"))
