package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct

/**
 * 자연스러운 멤버 채팅의 경계를 벗어난 후보를 거른다.
 *
 * 이 critic 은 "위로" 같은 상황 enum 을 만들지 않는다. 원문/intent/recent turn 에서 보이는 직접 반응 요구와 후보
 * 텍스트만 보고, 장문 위로·설명식 답변·과한 친밀감·사용자 감정 단정을 하나의 대화 경계 위반으로 평가한다.
 */
class ConversationalBoundaryCritic : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict {
        val text = candidate.joined
        if (text.isBlank()) return CriticVerdict.ACCEPTED
        return if (
            isOverlongSupport(text, packet) ||
            isLectureStyle(text, packet) ||
            isOverFamiliar(text) ||
            assertsUserEmotion(text)
        ) {
            CriticVerdict.reject(CriticReason.CONVERSATIONAL_BOUNDARY)
        } else {
            CriticVerdict.ACCEPTED
        }
    }

    private fun isOverlongSupport(
        text: String,
        packet: SpeechScenePacket,
    ): Boolean = supportRequested(packet) && text.length > SUPPORT_REPLY_LIMIT

    private fun supportRequested(packet: SpeechScenePacket): Boolean =
        evidenceText(packet).containsAny(SUPPORT_MARKERS) ||
            packet.socialAct in setOf(SpeechSocialAct.ACKNOWLEDGE, SpeechSocialAct.AGREE)

    private fun isLectureStyle(
        text: String,
        packet: SpeechScenePacket,
    ): Boolean =
        packet.socialAct.requiresFactualLookup.not() &&
            LECTURE_PATTERNS.any { it.containsMatchIn(text) }

    private fun isOverFamiliar(text: String): Boolean = OVER_FAMILIAR_PATTERNS.any { it.containsMatchIn(text) }

    private fun assertsUserEmotion(text: String): Boolean = EMOTION_ASSERTION_PATTERNS.any { it.containsMatchIn(text) }

    private fun evidenceText(packet: SpeechScenePacket): String =
        buildString {
            packet.speechIntent?.let { append(it).append('\n') }
            packet.rawContextSceneData?.let { append(it).append('\n') }
            packet.recentTurns.forEach { append(it.text).append('\n') }
        }

    private fun String.containsAny(markers: List<String>): Boolean = markers.any { it in this }

    companion object {
        private const val SUPPORT_REPLY_LIMIT = 120

        private val SUPPORT_MARKERS =
            listOf("위로", "반응", "답장", "무시", "ㅠ", "ㅜ", "힘들", "괜찮")

        private val LECTURE_PATTERNS =
            listOf(
                Regex("첫째|둘째|셋째"),
                Regex("\\b[1-9][.)]\\s"),
                Regex("단계(로|는|를)|방법은|원인은|결론적으로"),
                Regex("정리하자면|요약하자면"),
            )

        private val OVER_FAMILIAR_PATTERNS =
            listOf(
                Regex("우리\\s*(사이|절친|베프)"),
                Regex("항상\\s*네\\s*편"),
                Regex("널?\\s*제일\\s*잘\\s*알"),
                Regex("내가\\s*너를?\\s*다\\s*알"),
                Regex("사랑해|소중한\\s*사람"),
            )

        private val EMOTION_ASSERTION_PATTERNS =
            listOf(
                Regex("너(?:는|가|도)?\\s*(지금\\s*)?(슬프|외롭|불안|화났|우울|상처받)"),
                Regex("네가\\s*(슬픔|외로움|불안|분노|우울)을\\s*느끼"),
                Regex("너무\\s*(외로운|불안한|슬픈)\\s*거(야|구나)"),
            )
    }
}
