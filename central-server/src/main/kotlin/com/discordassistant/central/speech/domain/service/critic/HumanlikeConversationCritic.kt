package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 사람 같은 장난은 허용하되 사용자를 밀어내는 말투, 실패한 대화 수습, 니아수다의 기능 경계 위반을 거른다.
 *
 * 생성 프롬프트가 태도를 유도하는 1차 방어라면 이 critic 은 전송 직전의 결정론적 최소 품질선이다. 후보를 고치거나
 * 새 답을 만들지 않고, 최신 사용자 turn 과 후보 텍스트만 비교한다.
 */
class HumanlikeConversationCritic : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict {
        val response = candidate.joined
        if (response.isBlank()) return CriticVerdict.ACCEPTED
        if (DISMISSIVE_PATTERNS.any { it.containsMatchIn(response) }) {
            return CriticVerdict.reject(CriticReason.DISMISSIVE_TONE)
        }

        val latestHumanText = latestHumanText(packet)
        if (asksForRepair(latestHumanText) && REPAIR_MARKERS.none { it in response }) {
            return CriticVerdict.reject(CriticReason.REPAIR_MISSED)
        }
        if (isFeatureRequest(latestHumanText) && FEATURE_CHANNEL_MARKER !in response.lowercase()) {
            return CriticVerdict.reject(CriticReason.FEATURE_CHANNEL_REDIRECT_MISSING)
        }
        return CriticVerdict.ACCEPTED
    }

    private fun latestHumanText(packet: SpeechScenePacket): String =
        packet.recentTurns
            .asReversed()
            .firstOrNull { it.speakerLabel.lowercase() !in NIA_SPEAKER_LABELS }
            ?.text
            .orEmpty()

    private fun asksForRepair(text: String): Boolean = REPAIR_REQUEST_MARKERS.any { it in text.lowercase() }

    private fun isFeatureRequest(text: String): Boolean {
        val normalized = text.lowercase()
        return TECHNICAL_TOPIC_MARKERS.any { it in normalized } &&
            TECHNICAL_REQUEST_MARKERS.any { it in normalized }
    }

    companion object {
        private const val FEATURE_CHANNEL_MARKER = "ai채팅"

        private val NIA_SPEAKER_LABELS = setOf("nia", "니아")

        private val DISMISSIVE_PATTERNS =
            listOf(
                Regex("뭐라도\\s*하자면"),
                Regex("뭐가\\s*궁금한데"),
                Regex("알았으니까"),
                Regex("왜\\s*자꾸\\s*불러"),
                Regex("할\\s*말\\s*있으면\\s*해봐"),
                Regex("어쩌라고"),
                Regex("그만\\s*좀\\s*불러"),
                Regex("귀찮"),
            )

        private val REPAIR_REQUEST_MARKERS =
            listOf("??", "뭔 말", "뭔말", "무슨 말", "무슨말", "뭐라는", "뭔 소리", "뭔소리", "왜 그런 말", "이상한 말", "헛소리")

        private val REPAIR_MARKERS =
            listOf("미안", "내 말", "방금", "뜻", "그러니까", "다시 말", "잘못", "이상했", "뜬금없")

        private val TECHNICAL_TOPIC_MARKERS =
            listOf("알고리즘", "다익스트라", "코드", "코딩", "프로그래밍", "python", "파이썬", "java", "자바", "kotlin", "코틀린", "sql")

        private val TECHNICAL_REQUEST_MARKERS =
            listOf("알려", "설명", "작성", "짜줘", "만들어", "풀어", "구현")
    }
}
