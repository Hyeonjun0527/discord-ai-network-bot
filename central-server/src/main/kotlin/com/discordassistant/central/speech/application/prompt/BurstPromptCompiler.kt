package com.discordassistant.central.speech.application.prompt

import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.NiaPromptTemplate
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
class BurstPromptCompiler(
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
) {
    /** [shape] 를 발화 형태 지침으로 컴파일한다(조각 수는 정확히 강제). */
    fun compile(shape: SpeechBurstShape): String {
        if (shape.reactionOnly) {
            return render("REACTION_ONLY", shape)
        }
        val n = shape.fragmentCount
        return render(if (n == 1) "SINGLE" else "MULTI", shape) + " " + render("TAIL", shape)
    }

    private fun render(
        key: String,
        shape: SpeechBurstShape,
    ): String {
        val template =
            promptSource
                .text(NiaPromptKey.BURST_INSTRUCTIONS)
                .lineSequence()
                .mapNotNull { line ->
                    val split = line.split('=', limit = 2)
                    if (split.size == 2) split[0].trim() to split[1].trim() else null
                }.toMap()
                .getValue(key)
        return NiaPromptTemplate.render(
            template,
            mapOf(
                "count" to shape.fragmentCount.toString(),
                "maxChars" to shape.maxFragmentLength.toString(),
            ),
        )
    }

    /** 이 형태가 강제하는 조각 수(acceptance T010 — 모델이 바꿀 수 없는 확정값). reaction-only 면 0. */
    fun bubbleCount(shape: SpeechBurstShape): Int = if (shape.reactionOnly) 0 else shape.fragmentCount
}
