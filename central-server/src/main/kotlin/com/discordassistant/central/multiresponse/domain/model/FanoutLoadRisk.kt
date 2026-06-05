package com.discordassistant.central.multiresponse.domain.model

/**
 * Provider fan-out 부하 위험 등급 (다중응답 리포팅).
 *
 * `MultiResponseReportingService.fanoutLoadRisk(...)` 에 흩어져 있던 **순수 결정 규칙**을
 * 도메인으로 승격한 값객체다. 입력은 primitive(Int/List<Int>)뿐이며 엔티티/DTO/IO 의존이
 * 없으므로 `domainIsIndependent` 를 위반하지 않는다.
 *
 * 응답 JSON/대시보드 계약은 소문자 와이어 값([wire]) 을 그대로 쓰므로 동작 불변
 * (`loadRisk` 필드 값은 `classify(...).wire` 와 동일). 정렬 비교에 쓰던 `riskRank(value)` 는
 * [rank] 로 보존한다.
 */
enum class FanoutLoadRisk(
    val wire: String,
    /** 정렬 우선순위(높을수록 위험). 기존 `riskRank` 와 동일한 정수 매핑. */
    val rank: Int,
) {
    NORMAL("normal", 1),
    WATCH("watch", 2),
    HIGH("high", 3),
    CRITICAL("critical", 4),
    ;

    companion object {
        /**
         * 후보/타임아웃/실패 수와 지연(ms) 목록으로 부하 위험을 분류한다.
         *
         * 기존 `MultiResponseReportingService.fanoutLoadRisk` 와 동일한 임계값/순서를 보존한다.
         */
        fun classify(
            candidateCount: Int,
            timeoutCount: Int,
            failedCount: Int,
            latencyValues: List<Int>,
        ): FanoutLoadRisk {
            val timeoutRate = if (candidateCount == 0) 0.0 else timeoutCount.toDouble() / candidateCount
            val failureRate = if (candidateCount == 0) 0.0 else (timeoutCount + failedCount).toDouble() / candidateCount
            val averageLatency = latencyValues.takeIf { it.isNotEmpty() }?.average() ?: 0.0
            return when {
                timeoutRate >= 0.5 || failureRate >= 0.75 -> CRITICAL
                timeoutRate >= 0.25 || failureRate >= 0.4 || averageLatency >= 10_000 -> HIGH
                candidateCount >= 5 || averageLatency >= 5_000 -> WATCH
                else -> NORMAL
            }
        }

        /**
         * 소문자 와이어 문자열의 정렬 우선순위. 기존 `riskRank(value: String)` 와 동일하게
         * 알 수 없는 값은 [NORMAL] 등급(1)으로 본다.
         */
        fun rankOf(wire: String): Int = entries.firstOrNull { it.wire == wire.lowercase() }?.rank ?: NORMAL.rank
    }
}
