package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.service.critic.CandidateText
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import com.discordassistant.central.speech.domain.service.critic.CriticVerdict
import com.discordassistant.central.speech.domain.service.critic.SpeechCritic

/** 비밀 노출·버블 형식 등 로컬 critic을 통과한 발화 후보만 남긴다. */
class CandidateCriticFilter(
    private val critics: List<SpeechCritic>,
) {
    /** 모든 critic을 통과해 로컬 선택에 올릴 수 있는 실제 SEND 후보만 돌려준다. */
    fun survivors(
        candidates: List<SpeechCandidate>,
        packet: SpeechScenePacket,
    ): List<SpeechCandidate> = candidates.filter { survives(it, packet) }

    /** 후보를 평가해 탈락 사유들을 모은다. */
    fun rejectionReasons(
        candidate: SpeechCandidate,
        packet: SpeechScenePacket,
    ): List<CriticReason> {
        val text = CandidateText(candidate.candidateId, candidate.bubbles)
        return critics
            .map { it.evaluate(text, packet) }
            .filter { it.rejected }
            .mapNotNull(CriticVerdict::reason)
    }

    private fun survives(
        candidate: SpeechCandidate,
        packet: SpeechScenePacket,
    ): Boolean {
        val text = CandidateText(candidate.candidateId, candidate.bubbles)
        if (text.joined.isBlank()) return false
        return critics.all { it.evaluate(text, packet).accepted }
    }
}
