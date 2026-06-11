package com.discordassistant.central.ainetwork.domain.model

/**
 * 유저-니아 호감도 단계(P16). 사용할수록(질문/그림 성공마다 +1) 점수가 단조 증가하고, 누적 점수가
 * 임계를 넘으면 다음 단계로 올라간다. 순위/비교가 없는 개인 진척도 — 순수 함수 모음(의존성 0, 정수 연산).
 *
 * 단계 곡선: 낯섦(0)→알아가는중(10)→친근(50)→단짝(150). 임계는 단조 증가하므로 점수로 단계를 역산한다.
 */
enum class AffinityStage(
    val threshold: Long,
    val displayName: String,
) {
    STRANGER(0L, "낯섦"),
    GETTING_TO_KNOW(10L, "알아가는중"),
    FRIENDLY(50L, "친근"),
    BEST_FRIEND(150L, "단짝"),
    ;

    companion object {
        /** 질문(/ask)·그림(/imagine) 성공 1건당 오르는 호감도. 운영 중 조정 가능하도록 상수화. */
        const val SCORE_PER_INTERACTION = 1L

        /** 누적 점수 [score] 에 해당하는 단계(최소 STRANGER). 음수 입력은 STRANGER 로 처리. */
        fun forScore(score: Long): AffinityStage {
            var stage = STRANGER
            for (candidate in entries) {
                if (score >= candidate.threshold) stage = candidate
            }
            return stage
        }

        /** 다음 단계(있으면). 최고 단계면 null. */
        fun next(stage: AffinityStage): AffinityStage? = entries.getOrNull(stage.ordinal + 1)

        /** 다음 단계까지 남은 점수(>=0). 최고 단계면 0. */
        fun scoreToNext(score: Long): Long {
            val safe = if (score < 0L) 0L else score
            val next = next(forScore(safe)) ?: return 0L
            val remaining = next.threshold - safe
            return if (remaining < 0L) 0L else remaining
        }
    }
}
