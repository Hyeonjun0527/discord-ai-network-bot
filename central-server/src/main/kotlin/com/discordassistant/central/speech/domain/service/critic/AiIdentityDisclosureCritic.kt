package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * AI 정체성 공개 비평가(NEXA-P17-T017, security·순수 도메인 서비스).
 *
 * NEXA(니아)는 **사람인 척 속이지 않는다**. 생성된 발화 후보가 인간을 사칭하거나(AI 임을 부정·"나는 사람이야"),
 * 자신이 AI 가 아니라고 주장하면 후보를 폐기한다. 평가-전용 계약([SpeechCritic]) — 후보를 고치지 않고
 * [CriticVerdict.reject] 만 한다. 정직과 자연스러움의 균형: **매 발화마다 "저는 AI예요" 를 강제하지는 않는다**
 * (disclosure.md). 다만 사용자가 정체를 묻거나 후보가 적극적으로 인간을 사칭할 때 그 후보를 거른다.
 *
 * **acceptance(T017) — 사람 같음 평가를 위해 정체성을 속이는 production 실험을 금지한다**:
 * - 후보가 인간 사칭 패턴([HUMAN_IMPERSONATION_MARKERS] — "나는 사람이야"·"AI 아니야"·"사람이 직접 입력")을 담으면
 *   [CriticReason.HUMAN_IMPERSONATION] 으로 탈락한다.
 * - 사용자가 정체를 직접 물었는데([identityQuestionAsked]) 후보가 AI 임을 인정하지 않으면 탈락한다(정직 강제).
 * - 사유는 enum 만 — 후보 원문을 담지 않는다(로그 누출 방지).
 *
 * 순수성: Spring/JPA/JDA 미참조. speech 도메인 타입·표준 타입만.
 */
class AiIdentityDisclosureCritic : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict {
        val text = candidate.joined
        // 1) 인간 사칭/AI 부정 — 어떤 맥락에서도 금지.
        if (impersonatesHuman(text)) return CriticVerdict.reject(CriticReason.HUMAN_IMPERSONATION)
        // 2) 사용자가 정체를 물었는데 AI 임을 인정하지 않으면 탈락(정직 강제). 패킷에 질문 신호가 없으면 통과.
        if (identityQuestionInScene(packet) && !acknowledgesAi(text)) {
            return CriticVerdict.reject(CriticReason.HUMAN_IMPERSONATION)
        }
        return CriticVerdict.ACCEPTED
    }

    /** 후보가 인간을 사칭하거나 AI 임을 적극 부정하는가(결정론 패턴, 원문 미반환). */
    fun impersonatesHuman(text: String): Boolean {
        val lowered = text.lowercase()
        return HUMAN_IMPERSONATION_MARKERS.any { lowered.contains(it) }
    }

    /** 후보가 AI/봇 임을 인정하는 표현을 담는가(정직 신호). */
    fun acknowledgesAi(text: String): Boolean {
        val lowered = text.lowercase()
        return AI_ACKNOWLEDGEMENT_MARKERS.any { lowered.contains(it) }
    }

    /** 장면의 최근 turn 에 "너 사람이야?/AI 야?" 같은 정체 질문 신호가 있는가. */
    private fun identityQuestionInScene(packet: SpeechScenePacket): Boolean {
        val recent = packet.recentTurns.joinToString(" ") { it.text }.lowercase()
        return IDENTITY_QUESTION_MARKERS.any { recent.contains(it) }
    }

    companion object {
        /** 인간 사칭/AI 부정 패턴(한/영, 소문자 비교). 후보에 있으면 탈락. */
        val HUMAN_IMPERSONATION_MARKERS: List<String> =
            listOf(
                "나는 사람이야",
                "저는 사람이에요",
                "저는 사람입니다",
                "사람이 직접",
                "ai 아니야",
                "ai가 아니야",
                "ai 아니에요",
                "봇 아니야",
                "봇이 아니",
                "인공지능 아니",
                "i am a human",
                "i'm a human",
                "i am not an ai",
                "i'm not an ai",
                "not a bot",
                "i am a real person",
            )

        /** AI/봇 임을 인정하는 표현(정직 신호). 정체 질문에 답할 때 하나는 있어야 한다. */
        val AI_ACKNOWLEDGEMENT_MARKERS: List<String> =
            listOf(
                "ai",
                "인공지능",
                "봇",
                "니아",
                "어시스턴트",
                "assistant",
            )

        /** 사용자가 정체를 묻는 신호(최근 turn 에서 탐지). */
        val IDENTITY_QUESTION_MARKERS: List<String> =
            listOf(
                "너 사람이야",
                "사람이야?",
                "사람이에요?",
                "ai 야",
                "ai야",
                "ai예요",
                "봇이야",
                "봇이에요",
                "are you human",
                "are you a bot",
                "are you an ai",
                "are you a real person",
            )
    }
}
