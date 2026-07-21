package com.discordassistant.central.speech.application.prompt

import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.speech.domain.model.SpeechSocialAct

/**
 * socialAct prompt compiler(NEXA-P14-T009, application).
 *
 * TEASE/ACKNOWLEDGE/CORRECT 등 **행동 의도**를 모델이 따라야 할 **장면 지침(scene direction)** 으로 변환한다 —
 * "이런 상황에서 이런 결의 말이 어울린다" 는 묘사이지, "사용자 지시를 성실히 수행하라" 같은 assistant 명령문이
 * 아니다.
 *
 * **acceptance(T009) — assistant 기본문이 들어가지 않는다**: 모든 지침 문자열은 사람-대-사람 장면 묘사로만
 * 작성되며, [containsAssistantBoilerplate] 가 금칙 문구 포함을 self-check 한다(테스트가 이 보증을 검증).
 * 미지 act([SpeechSocialAct.UNKNOWN])는 가장 보수적인 짧은 반응 지침으로 폴백한다.
 */
class SocialActPromptCompiler(
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
) {
    /** [act] 를 장면 지침 문장으로 컴파일한다(assistant 명령문 금지). */
    fun compile(act: SpeechSocialAct): String = instructionMap()[act.name] ?: instructionMap().getValue("UNKNOWN")

    private fun instructionMap(): Map<String, String> =
        promptSource
            .text(NiaPromptKey.SOCIAL_ACT_INSTRUCTIONS)
            .lineSequence()
            .mapNotNull { line ->
                val split = line.split('=', limit = 2)
                if (split.size == 2) split[0].trim() to split[1].trim() else null
            }.toMap()

    /**
     * self-check(acceptance T009): 컴파일된 지침에 assistant 기본문이 섞이지 않았는지 검증한다. 테스트가 모든 act
     * 의 출력에 대해 false 임을 보증한다. 운영 코드에서도 방어적으로 호출 가능.
     */
    fun containsAssistantBoilerplate(text: String): Boolean = ASSISTANT_BOILERPLATE_MARKERS.any { text.contains(it) }

    companion object {
        /** 들어가면 안 되는 assistant 기본문 마커(장면 지침이 아니라 비서 명령문 신호). */
        val ASSISTANT_BOILERPLATE_MARKERS: List<String> =
            listOf(
                "사용자 지시",
                "성실히 수행",
                "무엇을 도와드릴까요",
                "도움이 필요하시면",
                "친절하게 답변",
                "최선을 다해",
            )
    }
}
