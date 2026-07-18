package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 잠정 발화 의도가 요구한 행위를 후보가 지금 수행하는지 검사한다.
 *
 * 이야기·설명·답변을 하겠다는 예고만 남기거나, 사과 의도에서 사과 없이 변명만 하는 후보를 탈락시킨다. 의도나
 * 행위 유형을 식별할 근거가 없는 장면은 통과시켜 이 규칙이 일반 대화를 과도하게 막지 않게 한다.
 */
class IntentFulfillmentCritic : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict {
        val intent =
            packet.speechIntent
                ?.lowercase()
                ?.trim()
                .orEmpty()
        if (intent.isBlank()) return CriticVerdict.ACCEPTED

        val text = candidate.joined.lowercase().trim()
        if (text.isBlank()) return CriticVerdict.reject(CriticReason.INTENT_NOT_FULFILLED)
        if (DEFERRAL_PATTERNS.any { it.containsMatchIn(text) }) {
            return CriticVerdict.reject(CriticReason.INTENT_NOT_FULFILLED)
        }

        return when (detectRequiredAct(intent)) {
            RequiredAct.STORY,
            RequiredAct.EXPLANATION,
            RequiredAct.ANSWER,
            -> evaluateContentAct(text)

            RequiredAct.APOLOGY ->
                if (APOLOGY_MARKERS.any(text::contains)) {
                    CriticVerdict.ACCEPTED
                } else {
                    CriticVerdict.reject(CriticReason.INTENT_NOT_FULFILLED)
                }

            null -> CriticVerdict.ACCEPTED
        }
    }

    private fun evaluateContentAct(text: String): CriticVerdict = CriticVerdict.ACCEPTED

    private fun detectRequiredAct(intent: String): RequiredAct? =
        when {
            STORY_INTENT_MARKERS.any(intent::contains) -> RequiredAct.STORY
            APOLOGY_INTENT_MARKERS.any(intent::contains) -> RequiredAct.APOLOGY
            EXPLANATION_INTENT_MARKERS.any(intent::contains) -> RequiredAct.EXPLANATION
            ANSWER_INTENT_MARKERS.any(intent::contains) -> RequiredAct.ANSWER
            else -> null
        }

    private enum class RequiredAct {
        STORY,
        EXPLANATION,
        ANSWER,
        APOLOGY,
    }

    companion object {
        private val STORY_INTENT_MARKERS = listOf("이야기", "재밌는 얘기", "썰", "story")
        private val EXPLANATION_INTENT_MARKERS = listOf("설명", "알려", "해설", "explain")
        private val ANSWER_INTENT_MARKERS = listOf("답변", "대답", "질문에 답", "answer")
        private val APOLOGY_INTENT_MARKERS = listOf("사과", "미안", "apolog")
        private val APOLOGY_MARKERS = listOf("미안", "죄송", "사과", "내가 잘못", "내 잘못", "sorry")
        private val DEFERRAL_PATTERNS =
            listOf(
                Regex("준비(?:해)?\\s*(?:볼게|할게|해서 올게)"),
                Regex("(?:나중에|이따가|잠시 후에?)"),
                Regex("(?:설명|이야기|얘기|대답|답변|알려)\\s*(?:해)?\\s*(?:줄게|볼게|할게)"),
                Regex("생각\\s*(?:해)?\\s*볼게"),
                Regex("(?:i(?:'ll| will)|let me)\\s+(?:prepare|explain|answer|tell|think)"),
            )
    }
}
