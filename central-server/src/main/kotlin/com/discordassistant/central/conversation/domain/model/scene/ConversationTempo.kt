package com.discordassistant.central.conversation.domain.model.scene

import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import java.time.Duration

/**
 * 대화 템포(NEXA-P05-T015, 순수 도메인 값 객체·불변). 지금 채널이 **얼마나 빠르게/북적이며** 돌아가는지를 수치로
 * 요약한다 — 최근 burst rate, burst 시작 간 median gap, active speaker 수, 동시 발화(overlap) 비율.
 *
 * 행동을 결정하지 않는다(관찰값일 뿐) — participation 이 이 값을 읽어 "끼어들지/기다릴지" 를 판단한다.
 *
 * **acceptance(T015) — 조용한 채널과 빠른 난장판 구분**: 조용한 채널(긴 gap·1명·overlap 0)과 빠른 난장판(짧은 gap·
 * 여러 명·높은 overlap)은 [burstsPerMinute]·[medianGap]·[activeSpeakerCount]·[overlapRatio] 가 뚜렷이 갈린다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 [Duration] 만 쓴다.
 */
data class ConversationTempo(
    /** 관측 창 동안의 분당 burst 수(밀도). 클수록 빠른 대화. */
    val burstsPerMinute: Double,
    /** 연속 burst 시작 시각 간 gap 의 중앙값. 짧을수록 빠른 대화. burst 가 1개 이하면 null(gap 정의 불가). */
    val medianGap: Duration?,
    /** 관측 창에서 발화한 서로 다른 작성자 수. 1이면 독백, 클수록 난장판. */
    val activeSpeakerCount: Int,
    /** 시간적으로 겹친(overlap) burst 쌍 비율 [0,1]. 0이면 차례대로, 높을수록 동시다발. */
    val overlapRatio: Double,
) {
    init {
        require(burstsPerMinute >= 0.0) { "burstsPerMinute 는 음수일 수 없다" }
        require(activeSpeakerCount >= 0) { "activeSpeakerCount 는 음수일 수 없다" }
        require(overlapRatio in 0.0..1.0) { "overlapRatio 는 [0,1] 범위여야 한다" }
    }

    companion object {
        /** burst 가 없는 빈 장면의 템포(완전 정지). */
        val IDLE: ConversationTempo =
            ConversationTempo(
                burstsPerMinute = 0.0,
                medianGap = null,
                activeSpeakerCount = 0,
                overlapRatio = 0.0,
            )

        /**
         * [bursts] 로부터 템포를 계산한다(시간순 무관 — 내부에서 시작 시각으로 정렬). burst 가 비면 [IDLE].
         *
         * - burstsPerMinute: burst 수 / 관측 창(첫~마지막 시작 시각, 0이면 분모를 1분으로 보호).
         * - medianGap: 정렬된 시작 시각의 연속 gap 중앙값(burst ≤ 1 이면 null).
         * - activeSpeakerCount: 서로 다른 authorId 수.
         * - overlapRatio: [first.startedAt, last.lastFragmentAt] 구간이 겹치는 burst 쌍 / 전체 쌍.
         */
        fun from(bursts: List<UtteranceBurst>): ConversationTempo {
            if (bursts.isEmpty()) return IDLE
            val sorted = bursts.sortedBy { it.startedAt }

            val speakers = sorted.map { it.authorId }.toSet().size

            val starts = sorted.map { it.startedAt }
            val gaps =
                starts.zipWithNext { a, b -> Duration.between(a, b) }.sortedBy { it.toMillis() }
            val median = gaps.medianOrNull()

            val spanMillis =
                Duration.between(starts.first(), starts.last()).toMillis().coerceAtLeast(0)
            // 창이 0이면(동시 시작) 1분으로 보호해 0 나눗셈을 피하면서 밀도를 유한값으로 유지한다.
            val windowMinutes = (spanMillis.toDouble() / 60_000.0).coerceAtLeast(1.0 / 60.0)
            val perMinute = sorted.size.toDouble() / windowMinutes

            return ConversationTempo(
                burstsPerMinute = perMinute,
                medianGap = median,
                activeSpeakerCount = speakers,
                overlapRatio = overlapRatio(sorted),
            )
        }

        /** 정렬된 gap 리스트의 중앙값(빈 리스트면 null). 짝수면 가운데 두 값 평균. */
        private fun List<Duration>.medianOrNull(): Duration? {
            if (isEmpty()) return null
            val mid = size / 2
            return if (size % 2 == 1) {
                this[mid]
            } else {
                Duration.ofMillis((this[mid - 1].toMillis() + this[mid].toMillis()) / 2)
            }
        }

        /** [first.startedAt, last.lastFragmentAt] 구간이 겹치는 burst 쌍 비율. 쌍이 없으면 0. */
        private fun overlapRatio(sorted: List<UtteranceBurst>): Double {
            val n = sorted.size
            if (n < 2) return 0.0
            var overlapping = 0
            var total = 0
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    total++
                    val a = sorted[i]
                    val b = sorted[j]
                    // 두 구간 [start, lastFragmentAt] 이 겹치면(한쪽 시작이 다른쪽 끝보다 늦지 않음).
                    val overlaps =
                        !a.startedAt.isAfter(b.lastFragmentAt) && !b.startedAt.isAfter(a.lastFragmentAt)
                    if (overlaps) overlapping++
                }
            }
            return if (total == 0) 0.0 else overlapping.toDouble() / total.toDouble()
        }
    }
}
