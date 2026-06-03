package com.discordassistant.central.usage

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.persistence.AiRequestRepository
import com.discordassistant.central.persistence.UsageLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    @Transactional(readOnly = true)
    fun usageTrend(
        guildId: Long,
        days: Int = 7,
        clock: Clock = Clock.systemUTC(),
    ): List<DailyCount> {
        require(days in 1..90) { "days 는 1..90" }
        val today = Instant.now(clock).truncatedTo(ChronoUnit.DAYS)
        return (days - 1 downTo 0).map { back ->
            val start = today.minus(back.toLong(), ChronoUnit.DAYS)
            val end = start.plus(1, ChronoUnit.DAYS)
            DailyCount(start.toString(), usage.countByGuildIdAndCreatedAtBetween(guildId, start, end))
        }
    }

    /** 프로바이더의 부하 가중 기여 점수(완료 요청의 부담 수준 합). */
    @Transactional(readOnly = true)
    fun providerComputeScore(providerId: Long): Long =
        requests
            .findByProviderIdAndState(providerId, RequestState.COMPLETED)
            .sumOf { burdenWeight(it.requiredBurden).toLong() }

    /**
     * 프로바이더 본인 처리 내역(차수 12 #166). 프라이버시: **프롬프트 본문·요청 유저 id 미포함**.
     * 처리한 요청의 식별자/부담/시각만 노출(최신순).
     */
    @Transactional(readOnly = true)
    fun providerHistory(providerId: Long): List<Map<String, Any?>> =
        requests
            .findByProviderIdAndState(providerId, RequestState.COMPLETED)
            .sortedByDescending { it.id }
            .take(20)
            .map { mapOf("requestId" to it.requestId, "burden" to it.requiredBurden, "createdAt" to it.createdAt.toString()) }

    /** 길드 총 요청 수(대시보드 개요). 컨트롤러가 리포지토리를 직접 보지 않도록 서비스가 집계한다. */
    @Transactional(readOnly = true)
    fun guildRequestCount(guildId: Long): Long = requests.countByGuildId(guildId)

    /**
     * 길드 최근 요청 로그(최대 20건, 최신순). 프라이버시: 프롬프트 본문·요청 유저 id 미포함.
     * 엔티티가 아니라 [RequestLogEntry] DTO 로 반환해 web 어댑터가 persistence 에 의존하지 않게 한다.
     */
    @Transactional(readOnly = true)
    fun recentGuildRequests(guildId: Long): List<RequestLogEntry> =
        requests.findTop20ByGuildIdOrderByIdDesc(guildId).map {
            RequestLogEntry(
                requestId = it.requestId,
                state = it.state.name,
                requiredBurden = it.requiredBurden,
                providerId = it.providerId,
                failReason = it.failReason,
                createdAt = it.createdAt.toString(),
            )
        }

    private fun burdenWeight(name: String): Int =
        when (runCatching { ModelBurden.valueOf(name) }.getOrNull()) {
            ModelBurden.LIGHT -> 1
            ModelBurden.STANDARD -> 2
            ModelBurden.HEAVY -> 3
            ModelBurden.RESTRICTED -> 4
            null -> 1
        }

    data class DailyCount(
        val date: String,
        val count: Long,
    )

    /** 대시보드 요청 로그 한 줄(프롬프트 본문·유저 id 제외). */
    data class RequestLogEntry(
        val requestId: String,
        val state: String,
        val requiredBurden: String,
        val providerId: Long?,
        val failReason: String?,
        val createdAt: String,
    )
}
