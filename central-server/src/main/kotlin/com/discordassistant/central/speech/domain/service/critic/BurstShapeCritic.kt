package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * participation 이 확정한 burst shape 를 후보 선택 전에 강제한다.
 *
 * speech 는 말할지/몇 조각으로 말할지를 다시 판단하지 않는다. 모델이 조각 수를 늘리거나, reaction-only 장면에 텍스트
 * 후보를 만들거나, 조각 길이를 넘기면 후보를 버린다. 텍스트를 고치지 않고 평가만 한다.
 */
class BurstShapeCritic : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict {
        val bubbles = candidate.bubbles.map { it.trim() }.filter { it.isNotEmpty() }
        val shape = packet.burstShape
        if (bubbles.isEmpty()) return CriticVerdict.ACCEPTED
        if (shape.reactionOnly) return CriticVerdict.reject(CriticReason.BURST_SHAPE_MISMATCH)
        if (bubbles.size != shape.fragmentCount) return CriticVerdict.reject(CriticReason.BURST_SHAPE_MISMATCH)
        if (bubbles.any { it.length > allowedBubbleLength(shape) }) {
            return CriticVerdict.reject(CriticReason.BURST_SHAPE_MISMATCH)
        }
        return CriticVerdict.ACCEPTED
    }

    private fun allowedBubbleLength(shape: SpeechBurstShape): Int = minOf(shape.maxFragmentLength, DISCORD_MAX_MESSAGE_LENGTH)

    companion object {
        private const val DISCORD_MAX_MESSAGE_LENGTH = 2_000
    }
}
