package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.SpeechResponseObligation
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/** Judge가 SPEAK로 확정한 뒤 로컬 검사 생존 후보 중 하나를 결정적으로 고른다. */
class CompleteActionSelector {
    fun select(
        speechCandidates: List<SpeechCandidate>,
        packet: SpeechScenePacket,
        offerReaction: Boolean = true,
    ): CompleteActionSelection {
        val selected = speechCandidates.minByOrNull(SpeechCandidate::uncertainty)
        if (selected != null) return CompleteActionSelection.Send(selected)

        val responseRequired = packet.responseObligation == SpeechResponseObligation.REQUIRED
        if (offerReaction && !responseRequired) return CompleteActionSelection.React(DEFAULT_REACTION_CODE)
        return CompleteActionSelection.Ignore
    }

    companion object {
        const val DEFAULT_REACTION_CODE: String = "ack"
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
