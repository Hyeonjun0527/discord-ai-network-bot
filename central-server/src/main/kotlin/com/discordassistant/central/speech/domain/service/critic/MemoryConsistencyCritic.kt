package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.MemoryRef
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 기억 일관성 비평가(NEXA-P14-T019, 순수 도메인 서비스).
 *
 * 후보가 패킷에 실린 **유효 기억**([SpeechScenePacket.memoryRefs])·정체성과 **모순**되면 탈락시킨다. memoryRefs 는
 * 이미 selector(T008)가 valid/decay 만 남긴 current-valid fact 다 — 만료·충돌·삭제 기억은 들어오지 않는다.
 *
 * **acceptance(T019) — 검사 실패 시 사실을 새로 발명해 수정하지 않고 후보를 폐기한다**: 이 비평가는 후보를 고치는
 * 메서드가 없다(평가-전용). 모순이 감지되면 [CriticReason.MEMORY_CONTRADICTION] 으로 후보를 **버린다** — 새 사실을
 * 지어내 문장을 교정하지 않는다(선택기가 다른 통과 후보를 고르거나, 전부 탈락이면 침묵).
 *
 * 모순 판정(순수·결정론, embedding/GLM 없음): 기억 claim 과 후보가 **같은 핵심 명사**를 공유하면서 한쪽만 부정어를
 * 가지면(polarity flip) 모순으로 본다. 공유 주제가 없으면 모순 아님(보수적 — 무관한 후보를 거르지 않는다).
 *
 * 순수성: Spring/JPA/JDA 미참조. speech 도메인 타입·표준 타입만.
 */
class MemoryConsistencyCritic : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict {
        val text = candidate.joined
        if (text.isBlank() || packet.memoryRefs.isEmpty()) return CriticVerdict.ACCEPTED

        val candidateTokens = significantTokens(text)
        val candidateNegated = hasNegation(text)
        if (candidateTokens.isEmpty()) return CriticVerdict.ACCEPTED

        val contradicts =
            packet.memoryRefs.any { ref ->
                contradicts(ref, candidateTokens, candidateNegated)
            }
        return if (contradicts) {
            CriticVerdict.reject(CriticReason.MEMORY_CONTRADICTION)
        } else {
            CriticVerdict.ACCEPTED
        }
    }

    /**
     * [ref] 와 후보가 모순되는가. 핵심 명사를 공유하면서 부정 극성이 어긋나면 모순으로 본다(polarity flip).
     * 공유 토큰이 없으면 무관 → 모순 아님(보수적).
     */
    private fun contradicts(
        ref: MemoryRef,
        candidateTokens: Set<String>,
        candidateNegated: Boolean,
    ): Boolean {
        val claimTokens = significantTokens(ref.claim)
        val shared = claimTokens.count { it in candidateTokens }
        if (shared < MIN_SHARED_TOKENS) return false // 같은 주제를 말하지 않으면 모순 판정 불가.
        val claimNegated = hasNegation(ref.claim)
        return claimNegated != candidateNegated // 같은 주제, 어긋난 극성 → 모순.
    }

    /** 부정 표현(안/못/아니/없 등 + 영어 not/never)이 들어 있는가. */
    private fun hasNegation(text: String): Boolean = NEGATION_PATTERN.containsMatchIn(text.lowercase())

    /** 비교에 쓸 의미 토큰(2자 이상, 불용어 제외). 정규화 후 추출. */
    private fun significantTokens(text: String): Set<String> =
        text
            .lowercase()
            .split(NON_WORD)
            .map { it.trim() }
            .filter { it.length >= MIN_TOKEN_LENGTH && it !in STOPWORDS }
            .toSet()

    companion object {
        /** 모순 판정에 필요한 최소 공유 핵심 토큰 수(주제 일치 확인 — 과탐 방지). */
        const val MIN_SHARED_TOKENS: Int = 1

        /** 의미 토큰 최소 길이. */
        const val MIN_TOKEN_LENGTH: Int = 2

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        private val NEGATION_PATTERN =
            Regex("(안\\s|못\\s|아니|없|말고|아냐|아니야|never|not\\b|n't|no\\b)")

        /** 극성·주제 비교에서 제외할 흔한 토큰(조사·기능어). */
        private val STOPWORDS: Set<String> =
            setOf(
                "그리고",
                "그래서",
                "근데",
                "진짜",
                "정말",
                "그냥",
                "조금",
                "지금",
                "오늘",
                "내일",
                "the",
                "and",
                "but",
                "for",
                "you",
                "are",
                "was",
                "this",
                "that",
                "really",
            )
    }
}
