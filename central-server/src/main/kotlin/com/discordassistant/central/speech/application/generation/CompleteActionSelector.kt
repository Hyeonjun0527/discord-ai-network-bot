package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.CompleteActionCandidate
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluationPort
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluationRequest
import com.discordassistant.central.speech.application.port.out.CompleteActionKind
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.SpeechResponseObligation
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/** 모호한 장면에서 실제 SEND 문구·REACT·IGNORE를 평가한 뒤 최종 행동을 고른다. */
class CompleteActionSelector(
    private val evaluator: CompleteActionEvaluationPort,
) {
    fun select(
        speechCandidates: List<SpeechCandidate>,
        packet: SpeechScenePacket,
        provisionalConfidence: Double = 1.0,
        contextVersion: Long = 0,
        seed: Long = 0,
        triggerMessageRef: String? = null,
        offerReaction: Boolean = true,
    ): CompleteActionSelection {
        val responseRequired = packet.responseObligation == SpeechResponseObligation.REQUIRED
        val candidates =
            buildList {
                speechCandidates.forEach { candidate ->
                    add(
                        CompleteActionCandidate(
                            candidateId = candidate.candidateId,
                            kind = CompleteActionKind.SEND,
                            bubbles = candidate.bubbles,
                        ),
                    )
                }
                if (offerReaction && !responseRequired) {
                    add(
                        CompleteActionCandidate(
                            REACTION_CANDIDATE_ID,
                            CompleteActionKind.REACT,
                            reactionCode = DEFAULT_REACTION_CODE,
                        ),
                    )
                }
                if (!responseRequired) {
                    add(CompleteActionCandidate(IGNORE_CANDIDATE_ID, CompleteActionKind.IGNORE))
                }
            }
        val evaluation =
            evaluator.select(
                CompleteActionEvaluationRequest(
                    focusThreadKey = packet.focusThreadKey,
                    provisionalDecision = "SPEAK",
                    provisionalConfidence = provisionalConfidence,
                    speechIntent = packet.speechIntent,
                    socialAct = packet.socialAct,
                    stateRefs = packet.memoryRefs.map { "${it.provenance}:${it.claim.hashCode().toUInt()}" },
                    recentTurns = packet.recentTurns,
                    rawContextSceneData = packet.rawContextSceneData,
                    contextVersion = contextVersion,
                    seed = seed,
                    triggerMessageRef = triggerMessageRef,
                    enforcementConstraints = ENFORCEMENT_CONSTRAINTS,
                    candidates = candidates,
                ),
            ) ?: return fallbackSelection(speechCandidates, responseRequired)
        if (evaluation.confidence < MIN_CONFIDENCE) return fallbackSelection(speechCandidates, responseRequired)
        val selected =
            candidates.firstOrNull { it.candidateId == evaluation.selectedCandidateId }
                ?: return fallbackSelection(speechCandidates, responseRequired)
        return when (selected.kind) {
            CompleteActionKind.SEND ->
                speechCandidates.firstOrNull { it.candidateId == selected.candidateId }?.let(CompleteActionSelection::Send)
                    ?: CompleteActionSelection.Ignore
            CompleteActionKind.REACT -> CompleteActionSelection.React(selected.reactionCode!!)
            CompleteActionKind.IGNORE -> CompleteActionSelection.Ignore
        }
    }

    private fun fallbackSelection(
        speechCandidates: List<SpeechCandidate>,
        responseRequired: Boolean,
    ): CompleteActionSelection =
        if (responseRequired) {
            speechCandidates.minByOrNull(SpeechCandidate::uncertainty)?.let(CompleteActionSelection::Send)
                ?: CompleteActionSelection.Ignore
        } else {
            CompleteActionSelection.Ignore
        }

    companion object {
        const val REACTION_CANDIDATE_ID: String = "action_react_ack"
        const val IGNORE_CANDIDATE_ID: String = "action_ignore"
        const val DEFAULT_REACTION_CODE: String = "ack"
        const val MIN_CONFIDENCE: Double = 0.55
        val ENFORCEMENT_CONSTRAINTS: Set<String> =
            setOf("CONSENT_RECHECK_REQUIRED", "SAFETY_CRITICS_PASSED", "STALE_CONTEXT_CANCEL", "REACTION_ALLOWLIST")
    }
}

sealed interface CompleteActionSelection {
    data class Send(
        val candidate: SpeechCandidate,
    ) : CompleteActionSelection

    data class React(
        val reactionCode: String,
    ) : CompleteActionSelection

    data object Ignore : CompleteActionSelection
}
