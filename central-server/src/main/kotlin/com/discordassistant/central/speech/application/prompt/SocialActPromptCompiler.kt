package com.discordassistant.central.speech.application.prompt

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
class SocialActPromptCompiler {
    /** [act] 를 장면 지침 문장으로 컴파일한다(assistant 명령문 금지). */
    fun compile(act: SpeechSocialAct): String =
        when (act) {
            SpeechSocialAct.ACKNOWLEDGE -> "상대 말을 가볍게 받아 주는 결이에요. 짧게 맞장구치듯, 길게 설명하지 말고."
            SpeechSocialAct.AGREE -> "공감하며 동의하는 결이에요. 같은 편이라는 느낌이 들도록 짧고 따뜻하게."
            SpeechSocialAct.DISAGREE -> "조심스럽게 다른 생각을 비추는 결이에요. 단정 짓지 말고 부드럽게, 상대를 누르지 않게."
            SpeechSocialAct.TEASE -> "친한 사이의 가벼운 장난 결이에요. 선을 넘지 않고, 상대가 웃을 만큼만 살짝."
            SpeechSocialAct.ASK -> "궁금해서 되묻는 결이에요. 심문이 아니라 대화를 잇는 한 가지 질문만."
            SpeechSocialAct.CORRECT -> "사실을 조용히 바로잡는 결이에요. 잘난 척 없이, 핵심만 담백하게 짚어요."
            SpeechSocialAct.SELF_DISCLOSE -> "자기 생각·상태를 슬쩍 내비치는 결이에요. 과하지 않게, 한두 마디로."
            SpeechSocialAct.CHANGE_TOPIC -> "흐름을 자연스럽게 다른 화제로 돌리는 결이에요. 끊는 느낌 없이 부드럽게."
            SpeechSocialAct.UNKNOWN -> "상황이 분명치 않으면 짧고 안전하게 반응해요. 길게 늘어놓지 말고 한 박자만."
        }

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
