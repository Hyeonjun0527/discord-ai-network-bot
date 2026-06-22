package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 반복·자기복제 비평가(NEXA-P14-T018, 순수 도메인 서비스).
 *
 * 후보가 **최근 NEXA(니아) 버스트**와 너무 비슷하면(같은 유행어·반응 반복) 탈락시킨다. 최근 니아 발화는
 * [SpeechScenePacket.recentTurns] 중 화자가 니아인 turn 에서 가져온다(별도 저장소 없이 패킷 안에서 — 도메인 순수).
 * 유사도는 **문자 n-gram Jaccard** 로 잰다(embedding 없이 결정론·순수).
 *
 * **acceptance(T018) — 같은 유행어·반응을 반복적으로 뿌리는 후보가 감점된다**: 후보와 최근 니아 발화의 n-gram
 * 유사도가 [SIMILARITY_THRESHOLD] 이상이면 [CriticReason.REPETITION] 으로 탈락한다. 최근 니아 발화가 없으면
 * 비교 대상이 없어 통과한다(첫 발화는 반복일 수 없다).
 *
 * 순수성: Spring/JPA/JDA 미참조. speech 도메인 타입·표준 타입만.
 */
class RepetitionDetector(
    /** 패킷 recentTurns 에서 니아 발화를 식별하는 가명 화자 라벨(기본 "nia"). */
    private val nexaSpeakerLabel: String = DEFAULT_NEXA_SPEAKER_LABEL,
) : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict {
        val candidateText = candidate.joined
        if (candidateText.isBlank()) return CriticVerdict.ACCEPTED

        val recentNexa =
            packet.recentTurns
                .filter { it.speakerLabel == nexaSpeakerLabel }
                .map { it.text }
                .filter { it.isNotBlank() }
        if (recentNexa.isEmpty()) return CriticVerdict.ACCEPTED // 비교할 과거 니아 발화 없음 → 반복 아님.

        val candidateGrams = nGrams(candidateText)
        if (candidateGrams.isEmpty()) return CriticVerdict.ACCEPTED // 너무 짧아 n-gram 없음 → 통과.

        val maxSimilarity = recentNexa.maxOf { jaccard(candidateGrams, nGrams(it)) }
        return if (maxSimilarity >= SIMILARITY_THRESHOLD) {
            CriticVerdict.reject(CriticReason.REPETITION)
        } else {
            CriticVerdict.ACCEPTED
        }
    }

    /** 정규화(공백 압축·소문자) 후 문자 [N] 그램 집합. */
    private fun nGrams(text: String): Set<String> {
        val normalized = text.lowercase().replace(WHITESPACE, " ").trim()
        if (normalized.length < N) return emptySet()
        return (0..normalized.length - N).map { normalized.substring(it, it + N) }.toSet()
    }

    /** 두 n-gram 집합의 Jaccard 유사도 [0,1]. 둘 다 비면 0(반복 아님으로 간주). */
    private fun jaccard(
        a: Set<String>,
        b: Set<String>,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        val union = a.size + b.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    companion object {
        const val DEFAULT_NEXA_SPEAKER_LABEL: String = "nia"

        /** 문자 n-gram 크기(짧은 한국어 발화에 적당한 trigram). */
        const val N: Int = 3

        /** 이 유사도 이상이면 반복으로 보고 탈락(0.6 = 상당히 겹침). */
        const val SIMILARITY_THRESHOLD: Double = 0.6

        private val WHITESPACE = Regex("\\s+")
    }
}
