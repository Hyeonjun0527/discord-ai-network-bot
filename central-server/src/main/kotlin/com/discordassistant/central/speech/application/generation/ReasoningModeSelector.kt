package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.ReasoningMode
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct

/**
 * thinking mode 선택 정책(NEXA-P14-T013, application).
 *
 * 짧은 잡담은 비추론([ReasoningMode.NONE]), 복잡한 사실·코드는 추론([ReasoningMode.THINKING]) 모드를 요청하도록
 * routing metadata 를 만든다. 비용/품질 트레이드오프를 **정책이** 결정한다.
 *
 * **acceptance(T013) — 정책 결정 자체를 GLM thinking 에 맡기지 않는다**: 모드는 이 selector 가 패킷 신호(socialAct·
 * 조각 수·길이·기억 동반 여부)로 **central 에서** 정하며, 결과는 [SpeechGenerationRequest.reasoningMode] 로 박혀
 * GLM 에 단순 파라미터로 전달된다 — GLM 이 "추론할지 말지" 를 스스로 정하지 않는다.
 */
class ReasoningModeSelector {
    /** [packet] 신호로 추론 모드를 결정한다. */
    fun select(packet: SpeechScenePacket): ReasoningMode {
        // 사실 정정·기억 동반 응답은 정확성이 중요 → 추론.
        if (packet.socialAct == SpeechSocialAct.CORRECT) return ReasoningMode.THINKING
        if (packet.memoryRefs.isNotEmpty() && packet.socialAct == SpeechSocialAct.ASK) return ReasoningMode.THINKING

        // 긴 단일 발화(복잡한 설명 가능성) → 추론.
        val shape = packet.burstShape
        if (!shape.reactionOnly && shape.fragmentCount == 1 && shape.maxFragmentLength >= LONG_FORM_THRESHOLD) {
            return ReasoningMode.THINKING
        }

        // 그 외 짧은 잡담·맞장구·리액션 → 비추론(빠르고 저렴).
        return ReasoningMode.NONE
    }

    companion object {
        /** 단일 조각이 이 길이 이상이면 복잡한 설명 가능성으로 보고 추론 모드. */
        const val LONG_FORM_THRESHOLD: Int = 400
    }
}
