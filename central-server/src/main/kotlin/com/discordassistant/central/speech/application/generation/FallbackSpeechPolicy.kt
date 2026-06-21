package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 안전한 generation fallback(NEXA-P14-T016, application).
 *
 * GLM 실패(빈 결과·무발화 후보)나 reaction-only 형태일 때 상황에 따라 **action 취소** 또는 **reaction-only** 로
 * 끝내고, canned 장문(성실한 도우미 템플릿)을 절대 보내지 않는다 — 잘못된 말을 하느니 침묵한다.
 *
 * **acceptance(T016) — 오류 때문에 갑자기 성실한 도우미 템플릿이 출력되지 않는다**: 이 정책은 **어떤 텍스트도
 * 생성하지 않는다**(canned 문자열 필드 자체가 없다). 가능한 결과는 [SpeechOutcome.Speak](모델 후보) /
 * [SpeechOutcome.ReactionOnly](말 없이 리액션) / [SpeechOutcome.Cancel](무발화·행동 취소)뿐이다. 빈 생성 결과는
 * 절대 Speak 가 되지 않는다.
 */
class FallbackSpeechPolicy {
    /**
     * 생성 [result] 와 [packet] 으로 최종 발화 결과를 정한다.
     * - 사용 가능한 후보가 있으면 [SpeechOutcome.Speak].
     * - reaction-only 형태면 [SpeechOutcome.ReactionOnly](모델 결과와 무관하게 말 안 함).
     * - 후보가 비었으면(GLM 실패) [SpeechOutcome.Cancel] — canned 장문 금지.
     */
    fun decide(
        result: SpeechGenerationResult,
        packet: SpeechScenePacket,
    ): SpeechOutcome {
        if (packet.burstShape.reactionOnly) return SpeechOutcome.ReactionOnly

        val usable = result.candidates.filter { it.bubbles.any { b -> b.isNotBlank() } }
        if (usable.isEmpty()) {
            // GLM 실패/무응답: 잘못된 말 대신 침묵(행동 취소). 단, 짧은 맞장구 결이면 리액션으로 약하게 마무리.
            return if (isLowStakesAcknowledgement(packet)) SpeechOutcome.ReactionOnly else SpeechOutcome.Cancel
        }
        return SpeechOutcome.Speak(usable)
    }

    /** 짧은 맞장구/동의류는 무발화보다 리액션이 자연스럽다(그래도 canned 텍스트는 만들지 않는다). */
    private fun isLowStakesAcknowledgement(packet: SpeechScenePacket): Boolean =
        packet.socialAct in LOW_STAKES_ACTS && packet.burstShape.fragmentCount == 1

    companion object {
        private val LOW_STAKES_ACTS =
            setOf(
                com.discordassistant.central.speech.domain.model.SpeechSocialAct.ACKNOWLEDGE,
                com.discordassistant.central.speech.domain.model.SpeechSocialAct.AGREE,
            )
    }
}

/**
 * 발화 결과(NEXA-P14-T016, application·sealed). canned 장문 출력 경로가 없다 — speech 의 안전 실패는 침묵/리액션뿐.
 */
sealed interface SpeechOutcome {
    /** 모델 후보로 발화한다(actionruntime 이 전송). */
    data class Speak(
        val candidates: List<SpeechCandidate>,
    ) : SpeechOutcome {
        init {
            require(candidates.isNotEmpty()) { "Speak 는 최소 1개 후보가 필요하다" }
        }
    }

    /** 말 없이 짧은 리액션으로 끝낸다(REACT 하강). */
    data object ReactionOnly : SpeechOutcome

    /** 무발화 — 행동 취소(IGNORE 하강). 잘못된 말 대신 침묵. */
    data object Cancel : SpeechOutcome
}
