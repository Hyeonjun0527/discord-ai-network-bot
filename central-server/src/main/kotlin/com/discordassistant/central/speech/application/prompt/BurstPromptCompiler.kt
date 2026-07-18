package com.discordassistant.central.speech.application.prompt

import com.discordassistant.central.speech.domain.model.SpeechBurstShape

/**
 * burstProfile prompt compiler(NEXA-P14-T010, application).
 *
 * 메시지 조각 수, 최대 길이, 첫 반응/후속 핵심 구조를 모델에게 **확정 형태**로 전달한다.
 *
 * **acceptance(T010) — 정책이 1개 버블을 고른 경우 모델이 4개를 강제하지 못한다**: 컴파일러는 [SpeechBurstShape.fragmentCount]
 * 를 "정확히 N개" 라는 **하드 제약**으로 적는다(범위가 아님). [bubbleCount] 가 곧 패킷이 강제하는 조각 수다 —
 * 1이면 "정확히 1개", 4이면 "정확히 4개". 모델이 형태를 늘리거나 줄일 여지를 주지 않는다. reaction-only 면 발화
 * 대신 짧은 리액션으로 끝내라고 지시한다.
 */
class BurstPromptCompiler {
    /** [shape] 를 발화 형태 지침으로 컴파일한다(조각 수는 정확히 강제). */
    fun compile(shape: SpeechBurstShape): String {
        if (shape.reactionOnly) {
            return "말을 길게 만들지 말고, 짧은 한마디나 가벼운 리액션 정도로만 반응해요(혹은 무발화)."
        }
        val n = shape.fragmentCount
        val structure =
            if (n == 1) {
                "메시지는 정확히 1개로, 한 호흡에 담아요."
            } else {
                "메시지를 정확히 ${n}개로 나눠 보내요. 첫 조각은 즉각적인 반응, 이어지는 조각은 자연스러운 후속이에요."
            }
        return buildString {
            append(structure)
            append(" 각 조각은 ${shape.maxFragmentLength}자 이내에서 맡은 행위를 수행할 만큼 쓰고, 채팅하듯 담백하게.")
            append(" 조각 수를 임의로 늘리거나 줄이지 말고 정확히 ${n}개를 지켜요.")
        }
    }

    /** 이 형태가 강제하는 조각 수(acceptance T010 — 모델이 바꿀 수 없는 확정값). reaction-only 면 0. */
    fun bubbleCount(shape: SpeechBurstShape): Int = if (shape.reactionOnly) 0 else shape.fragmentCount
}
