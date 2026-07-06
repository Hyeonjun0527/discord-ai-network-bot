package com.discordassistant.central.requestlog.adapter.outbound.persistence

import com.discordassistant.central.shared.RequestState
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface AiRequestRepository : JpaRepository<AiRequestEntity, Long> {
    fun findByRequestId(requestId: String): AiRequestEntity?

    fun findByRequestIdIn(requestIds: Collection<String>): List<AiRequestEntity>

    fun countByGuildId(guildId: Long): Long

    fun findTop20ByGuildIdOrderByIdDesc(guildId: Long): List<AiRequestEntity>

    fun findByProviderIdAndState(
        providerId: Long,
        state: RequestState,
    ): List<AiRequestEntity>

    /**
     * 채널 사용 현황 집계(Phase 2 어드민 대시보드 (a)). 채널별 요청 수·고유 유저 수·마지막 사용 시각.
     * group by 집계로 N+1 을 피한다(idx_ai_request_guild 의 guild_id 프리픽스 활용).
     * 프라이버시: 프롬프트 본문·메시지 내용 미포함 — 집계 수치와 channelId/시각만.
     */
    @Query(
        """
        select r.channelId as channelId,
               count(r) as requestCount,
               count(distinct r.userId) as distinctUserCount,
               max(r.createdAt) as lastUsedAt
        from AiRequestEntity r
        where r.guildId = :guildId
        group by r.channelId
        order by count(r) desc, r.channelId asc
        """,
    )
    fun aggregateChannelUsageByGuild(guildId: Long): List<ChannelUsageSummary>

    /**
     * 기능 사용 유저 집계(Phase 2 어드민 대시보드 (d)). 유저별 요청 수·첫 사용·마지막 사용.
     * 프라이버시: userId 와 집계만 — 프롬프트 원문/메시지 본문은 일절 노출하지 않는다.
     * [pageable] 로 DB 레벨에서 상위 N 만 잘라 가져온다(수십만 유저 길드에서 전체 group-by 결과를
     * JVM 으로 다 보내지 않게). 정렬은 @Query 의 ORDER BY(요청수 desc, userId asc)를 유지한다.
     */
    @Query(
        """
        select r.userId as userId,
               count(r) as requestCount,
               min(r.createdAt) as firstUsedAt,
               max(r.createdAt) as lastUsedAt
        from AiRequestEntity r
        where r.guildId = :guildId
        group by r.userId
        order by count(r) desc, r.userId asc
        """,
    )
    fun aggregateUserUsageByGuild(
        guildId: Long,
        pageable: Pageable,
    ): List<UserUsageSummary>
}

/** 채널 사용 현황 projection(프라이버시: 집계만, 프롬프트/메시지 본문 없음). */
interface ChannelUsageSummary {
    val channelId: Long
    val requestCount: Long
    val distinctUserCount: Long
    val lastUsedAt: Instant?
}

/** 기능 사용 유저 projection(프라이버시: userId·집계만, 프롬프트/메시지 본문 없음). */
interface UserUsageSummary {
    val userId: Long
    val requestCount: Long
    val firstUsedAt: Instant?
    val lastUsedAt: Instant?
}

interface UsageLogRepository : JpaRepository<UsageLogEntity, Long> {
    /** requestId 멱등 기록용 — 같은 요청의 완료 콜백이 재전송돼도 usage/contribution 중복 insert 를 막는다. */
    fun findByRequestId(requestId: String): UsageLogEntity?

    fun countByGuildIdAndUserId(
        guildId: Long,
        userId: Long,
    ): Long

    fun countByGuildIdAndUserIdAndCreatedAtAfter(
        guildId: Long,
        userId: Long,
        createdAt: Instant,
    ): Long

    fun countByGuildIdAndCreatedAtBetween(
        guildId: Long,
        start: Instant,
        end: Instant,
    ): Long
}

interface ContributionLogRepository : JpaRepository<ContributionLogEntity, Long> {
    fun countByProviderId(providerId: Long): Long

    @org.springframework.data.jpa.repository.Query(
        """
        select c.providerId as providerId, count(c) as contributionCount
        from ContributionLogEntity c
        where c.guildId = :guildId
        group by c.providerId
        order by count(c) desc, c.providerId asc
        """,
    )
    fun countByGuildIdGrouped(guildId: Long): List<ProviderContributionSummary>

    @Query(
        """
        select c.providerId as providerId, count(c) as contributionCount
        from ContributionLogEntity c
        where c.guildId = :guildId and c.createdAt >= :since
        group by c.providerId
        """,
    )
    fun countByGuildIdSinceGrouped(
        guildId: Long,
        since: Instant,
    ): List<ProviderContributionSummary>
}

interface ProviderContributionSummary {
    val providerId: Long
    val contributionCount: Long
}
