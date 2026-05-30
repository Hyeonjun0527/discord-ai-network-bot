package com.discordassistant.central.dashboard

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.persistence.AiRequestRepository
import com.discordassistant.central.persistence.UsageLogRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 사용량 트렌드(차수 15 #227) + 처리 부하 회계(차수 15 #228, 비금전 기여 측정).
 * 부하 회계는 완료 요청의 모델 부담 수준을 가중합한 점수(LIGHT=1·STANDARD=2·HEAVY=3·RESTRICTED=4)로,
 * 단순 건수보다 실제 기여한 "계산량"을 근사한다. 금전 가치가 아니라 기여 인정 지표다.
 */
@Service
class AnalyticsService(
    private val usage: UsageLogRepository,
    private val requests: AiRequestRepository,
) {
    /** 최근 [days]일의 일자별 요청 수(과거→오늘 순). UTC 자정 경계. */
    fun usageTrend(guildId: Long, days: Int = 7, clock: Clock = Clock.systemUTC()): List<DailyCount> {
        require(days in 1..90) { "days 는 1..90" }
        val today = Instant.now(clock).truncatedTo(ChronoUnit.DAYS)
        return (days - 1 downTo 0).map { back ->
            val start = today.minus(back.toLong(), ChronoUnit.DAYS)
            val end = start.plus(1, ChronoUnit.DAYS)
            DailyCount(start.toString(), usage.countByGuildIdAndCreatedAtBetween(guildId, start, end))
        }
    }

    /** 프로바이더의 부하 가중 기여 점수(완료 요청의 부담 수준 합). */
    fun providerComputeScore(providerId: Long): Long =
        requests.findByProviderIdAndState(providerId, "COMPLETED")
            .sumOf { burdenWeight(it.requiredBurden).toLong() }

    private fun burdenWeight(name: String): Int =
        when (runCatching { ModelBurden.valueOf(name) }.getOrNull()) {
            ModelBurden.LIGHT -> 1
            ModelBurden.STANDARD -> 2
            ModelBurden.HEAVY -> 3
            ModelBurden.RESTRICTED -> 4
            null -> 1
        }

    data class DailyCount(val date: String, val count: Long)
}
