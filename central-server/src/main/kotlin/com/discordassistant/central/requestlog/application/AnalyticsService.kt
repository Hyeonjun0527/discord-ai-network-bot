package com.discordassistant.central.requestlog.application

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.persistence.AiNetworkEventRepository
import com.discordassistant.central.requestlog.adapter.outbound.persistence.AiRequestRepository
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogRepository
import org.springframework.data.domain.PageRequest
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
    private val networkEvents: AiNetworkEventRepository,
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

    /**
     * 채널 사용 현황(Phase 2 어드민 대시보드 (a)). 어떤 디스코드 채널이 AI/풀을 쓰는지 —
     * 채널별 요청 수·고유 유저 수·마지막 사용 시각. group by 집계라 N+1 없음.
     * 프라이버시: 프롬프트 본문·메시지 내용 미포함, 집계 수치와 channelId/시각만.
     */
    @Transactional(readOnly = true)
    fun channelUsage(guildId: Long): List<ChannelUsage> =
        requests.aggregateChannelUsageByGuild(guildId).map {
            ChannelUsage(
                channelId = it.channelId,
                requestCount = it.requestCount,
                distinctUsers = it.distinctUserCount,
                lastUsedAt = it.lastUsedAt?.toString(),
            )
        }

    /**
     * 기능 사용 유저 목록(Phase 2 어드민 대시보드 (d)). 우리 기능을 쓰는 유저 상위 [limit]명 —
     * userId·요청 수·첫 사용·마지막 사용. 프라이버시: 집계만, **프롬프트 원문/메시지 본문 절대 미노출**.
     */
    @Transactional(readOnly = true)
    fun featureUsers(
        guildId: Long,
        limit: Int = 20,
    ): List<UserUsage> {
        require(limit in 1..200) { "limit 은 1..200" }
        // DB 레벨에서 상위 limit 만 잘라 가져온다(.take 메모리 절단 제거 — @Query 의 ORDER BY 가 정렬 유지).
        return requests
            .aggregateUserUsageByGuild(guildId, PageRequest.of(0, limit))
            .map {
                UserUsage(
                    userId = it.userId,
                    requestCount = it.requestCount,
                    firstUsedAt = it.firstUsedAt?.toString(),
                    lastUsedAt = it.lastUsedAt?.toString(),
                )
            }
    }

    /**
     * 프로바이더 참여 이력 타임라인(Phase 2 어드민 대시보드 (c)). join/approve/overload 등 프로바이더
     * 관련 네트워크 이벤트를 최신순으로(최대 50건). [providerUserId] 가 있으면 해당 프로바이더만 필터,
     * 없으면 프로바이더 관련 이벤트(providerUserId IS NOT NULL)만 — ai_level_up/network_level 같은
     * 프로바이더 무관 이벤트는 제외한다. 프라이버시: 이벤트 메타데이터만(요청 프롬프트·유저 메시지 본문 없음).
     */
    @Transactional(readOnly = true)
    fun providerHistoryTimeline(
        guildId: Long,
        providerUserId: Long? = null,
    ): List<ProviderHistoryEntry> {
        val events =
            if (providerUserId != null) {
                networkEvents.findTop50ByGuildIdAndProviderUserIdOrderByCreatedAtDesc(guildId, providerUserId)
            } else {
                networkEvents.findTop50ByGuildIdAndProviderUserIdIsNotNullOrderByCreatedAtDesc(guildId)
            }
        return events.map {
            ProviderHistoryEntry(
                id = it.id,
                eventType = it.eventType,
                providerUserId = it.providerUserId,
                title = it.title,
                summary = it.summary,
                createdAt = it.createdAt.toString(),
            )
        }
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

    /** 채널 사용 현황 한 줄(프롬프트/메시지 본문 제외, 집계만). */
    data class ChannelUsage(
        val channelId: Long,
        val requestCount: Long,
        val distinctUsers: Long,
        val lastUsedAt: String?,
    )

    /** 기능 사용 유저 한 줄(프롬프트/메시지 본문 제외, userId·집계만). */
    data class UserUsage(
        val userId: Long,
        val requestCount: Long,
        val firstUsedAt: String?,
        val lastUsedAt: String?,
    )

    /** 프로바이더 참여 이력 타임라인 한 줄(이벤트 메타데이터만). */
    data class ProviderHistoryEntry(
        val id: Long,
        val eventType: String,
        val providerUserId: Long?,
        val title: String,
        val summary: String?,
        val createdAt: String,
    )
}
