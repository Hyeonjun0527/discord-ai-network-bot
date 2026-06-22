package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * AI 도우미 말투 비평가(NEXA-P14-T017, 순수 도메인 서비스).
 *
 * "도와드릴까요 / 언제든 말씀하세요 / 좋은 질문입니다" 같은 **assistant 패턴**을 rule score 로 탐지해 후보를
 * 탈락시킨다. 니아는 서버의 한 멤버이지 고객 응대 도우미가 아니다(human-likeness gate 약점: AI 말투 직접 겨냥).
 *
 * **acceptance(T017) — 후보를 평가할 뿐 사용자 문장을 무조건 비꼬게 만들지 않는다**: 이 비평가는 [evaluate] 로
 * 후보 텍스트만 본다. 사용자 입력([SpeechScenePacket.recentTurns])을 변형하거나 빈정대는 문장을 생성하는 경로가
 * 없다 — 도우미 패턴이 임계 [SCORE_THRESHOLD] 이상이면 후보를 거를 뿐이다.
 *
 * 순수성: Spring/JPA/JDA 미참조. speech 도메인 타입·표준 타입만.
 */
class AssistantStyleDetector : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict {
        val text = candidate.joined
        if (text.isBlank()) return CriticVerdict.ACCEPTED // 빈 후보는 fallback(T016)이 따로 처리.
        val score = assistantScore(text)
        return if (score >= SCORE_THRESHOLD) {
            CriticVerdict.reject(CriticReason.ASSISTANT_STYLE)
        } else {
            CriticVerdict.ACCEPTED
        }
    }

    /** 후보 텍스트에서 assistant 패턴 점수를 매긴다(매칭된 패턴 수 — 0 이면 도우미 말투 아님). */
    private fun assistantScore(text: String): Int = ASSISTANT_PATTERNS.count { it.containsMatchIn(text) }

    companion object {
        /** 이 수 이상의 도우미 패턴이 매칭되면 탈락(단일 강한 신호도 거른다 — 기본 1). */
        const val SCORE_THRESHOLD: Int = 1

        /**
         * 도우미 말투 신호 패턴. 정중한 응대·메타 제안·결론 요약 같은 "고객지원 봇" 시그니처를 잡는다.
         * 일상 대화에는 거의 안 나오는 표현만 고른다(과탈락 방지).
         */
        private val ASSISTANT_PATTERNS: List<Regex> =
            listOf(
                Regex("도와드릴(까요|게요|까)"),
                Regex("도움이?\\s*되었?으면"),
                Regex("도움이?\\s*필요(하시|하면)"),
                Regex("언제든(지)?\\s*말씀"),
                Regex("말씀해\\s*주세요"),
                Regex("무엇을\\s*도와"),
                Regex("좋은\\s*질문(이에요|입니다|이네요)"),
                Regex("다른\\s*궁금(한|하신)\\s*(점|것)"),
                Regex("더\\s*궁금한\\s*(점|것)이?\\s*있으(면|시면)"),
                Regex("기꺼이\\s*(도와|돕)"),
                Regex("정리하자면[,\\s]"),
                Regex("요약하자면[,\\s]"),
                Regex("아래(와\\s*같이|의)\\s*(단계|순서|방법)"),
                Regex("다음과\\s*같(이|은)\\s*(단계|순서|방법|절차)"),
                Regex("궁금한\\s*점이?\\s*있(으|다)면\\s*(언제든|편하게)"),
                Regex("how\\s+can\\s+i\\s+help", RegexOption.IGNORE_CASE),
                Regex("let\\s+me\\s+know\\s+if\\s+you\\s+(need|have)", RegexOption.IGNORE_CASE),
                Regex("happy\\s+to\\s+help", RegexOption.IGNORE_CASE),
                Regex("feel\\s+free\\s+to\\s+ask", RegexOption.IGNORE_CASE),
            )
    }
}
