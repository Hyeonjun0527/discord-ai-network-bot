package com.discordassistant.central.persistence

import com.discordassistant.central.domain.FeedbackStatus
import com.discordassistant.central.domain.KnowledgeChunkStatus
import com.discordassistant.central.domain.KnowledgeSourceStatus
import com.discordassistant.central.domain.PresetReportStatus
import com.discordassistant.central.domain.ProposalStatus
import com.discordassistant.central.domain.ProviderState
import com.discordassistant.central.domain.PublishedPresetStatus
import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.domain.RetrievalPolicyStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface GuildRepository : JpaRepository<GuildEntity, Long>

interface AllowedChannelRepository : JpaRepository<AllowedChannelEntity, Long> {
    fun findByGuildId(guildId: Long): List<AllowedChannelEntity>

    fun deleteByGuildId(guildId: Long)

    fun existsByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): Boolean

    fun deleteByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    )
}

interface RolePolicyRepository : JpaRepository<RolePolicyEntity, Long> {
    fun findByGuildId(guildId: Long): List<RolePolicyEntity>

    fun deleteByGuildId(guildId: Long)

    fun findByGuildIdAndRoleId(
        guildId: Long,
        roleId: Long,
    ): RolePolicyEntity?
}

interface AiAdminRoleRepository : JpaRepository<AiAdminRoleEntity, Long> {
    fun findByGuildId(guildId: Long): List<AiAdminRoleEntity>

    fun existsByGuildIdAndRoleId(
        guildId: Long,
        roleId: Long,
    ): Boolean

    fun deleteByGuildId(guildId: Long)
}

interface ProviderRepository : JpaRepository<ProviderEntity, Long> {
    fun findByProviderUserIdAndGuildId(
        providerUserId: Long,
        guildId: Long,
    ): ProviderEntity?

    fun findByGuildIdAndState(
        guildId: Long,
        state: ProviderState,
    ): List<ProviderEntity>

    fun deleteByProviderUserIdAndGuildId(
        providerUserId: Long,
        guildId: Long,
    )

    fun deleteByGuildId(guildId: Long)
}

interface ProviderContributionPolicyRepository : JpaRepository<ProviderContributionPolicyEntity, Long> {
    fun findByProviderId(providerId: Long): List<ProviderContributionPolicyEntity>

    fun findByProviderIdIn(providerIds: Collection<Long>): List<ProviderContributionPolicyEntity>

    fun deleteByProviderIdIn(providerIds: Collection<Long>)
}

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
}

interface ProviderContributionSummary {
    val providerId: Long
    val contributionCount: Long
}

interface ProviderHealthRepository : JpaRepository<ProviderHealthEntity, Long> {
    fun findByProviderId(providerId: Long): ProviderHealthEntity?
}

interface ProviderScheduleRepository : JpaRepository<ProviderScheduleEntity, Long> {
    fun findByProviderIdAndGuildId(
        providerId: Long,
        guildId: Long,
    ): ProviderScheduleEntity?

    fun deleteByGuildId(guildId: Long)

    fun deleteByProviderIdAndGuildId(
        providerId: Long,
        guildId: Long,
    )
}

interface ChannelAiRepository : JpaRepository<ChannelAiEntity, Long> {
    fun findByGuildId(guildId: Long): List<ChannelAiEntity>

    fun findByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): ChannelAiEntity?

    fun deleteByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    )

    fun deleteByGuildId(guildId: Long)
}

interface AiBehaviorVersionRepository : JpaRepository<AiBehaviorVersionEntity, Long> {
    fun findTopByChannelAiIdOrderByVersionDesc(channelAiId: Long): AiBehaviorVersionEntity?

    fun findByChannelAiIdAndId(
        channelAiId: Long,
        id: Long,
    ): AiBehaviorVersionEntity?

    fun findByChannelAiIdOrderByVersionDesc(channelAiId: Long): List<AiBehaviorVersionEntity>

    fun deleteByChannelAiId(channelAiId: Long)
}

interface AiChangeProposalRepository : JpaRepository<AiChangeProposalEntity, Long> {
    fun findByGuildIdAndStatus(
        guildId: Long,
        status: ProposalStatus,
    ): List<AiChangeProposalEntity>

    fun findByGuildIdOrderByCreatedAtDesc(guildId: Long): List<AiChangeProposalEntity>

    fun findByGuildIdAndChannelIdOrderByCreatedAtDesc(
        guildId: Long,
        channelId: Long,
    ): List<AiChangeProposalEntity>

    fun deleteByGuildId(guildId: Long)

    fun deleteByChannelAiId(channelAiId: Long)
}

interface CustomizationAuditLogRepository : JpaRepository<CustomizationAuditLogEntity, Long> {
    fun findTop10ByGuildIdAndChannelIdOrderByCreatedAtDesc(
        guildId: Long,
        channelId: Long,
    ): List<CustomizationAuditLogEntity>

    fun deleteByGuildId(guildId: Long)

    fun deleteByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    )
}

interface AiNetworkProfileRepository : JpaRepository<AiNetworkProfileEntity, Long> {
    fun findByGuildId(guildId: Long): AiNetworkProfileEntity?

    /**
     * 활동 경험치를 원자적으로 증가시킨다(read-modify-write 없음, 동시 적립 안전).
     * @return 갱신된 행 수(프로필이 없으면 0).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE AiNetworkProfileEntity p SET p.totalXp = p.totalXp + :amount, p.lastXpAt = :at " +
            "WHERE p.guildId = :guildId",
    )
    fun addXp(
        @Param("guildId") guildId: Long,
        @Param("amount") amount: Long,
        @Param("at") at: Instant,
    ): Int

    /**
     * 활동 레벨을 조건부로 상향한다 — 현재 레벨이 newLevel 보다 낮을 때만 1행에 영향.
     * 동시 요청 중 레벨을 실제로 올린 트랜잭션만 1행을 반환하므로 ai_level_up 이벤트 중복을 막는다.
     * @return 갱신된 행 수(올라간 경우 1, 아니면 0).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE AiNetworkProfileEntity p SET p.aiLevel = :newLevel " +
            "WHERE p.guildId = :guildId AND p.aiLevel < :newLevel",
    )
    fun raiseLevel(
        @Param("guildId") guildId: Long,
        @Param("newLevel") newLevel: Int,
    ): Int
}

interface ProviderCapabilityProfileRepository : JpaRepository<ProviderCapabilityProfileEntity, Long> {
    fun findByGuildId(guildId: Long): List<ProviderCapabilityProfileEntity>

    fun findByGuildIdAndProviderUserId(
        guildId: Long,
        providerUserId: Long,
    ): ProviderCapabilityProfileEntity?
}

interface KnowledgeSpaceRepository : JpaRepository<KnowledgeSpaceEntity, Long> {
    fun findByGuildId(guildId: Long): List<KnowledgeSpaceEntity>

    fun findByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): List<KnowledgeSpaceEntity>

    fun findByGuildIdAndId(
        guildId: Long,
        id: Long,
    ): KnowledgeSpaceEntity?
}

interface KnowledgeSourceRepository : JpaRepository<KnowledgeSourceEntity, Long> {
    fun findByGuildId(guildId: Long): List<KnowledgeSourceEntity>

    fun findByGuildIdAndKnowledgeSpaceIdInAndStatusAndRiskLevelIn(
        guildId: Long,
        knowledgeSpaceIds: Collection<Long>,
        status: KnowledgeSourceStatus,
        riskLevels: Collection<String>,
    ): List<KnowledgeSourceEntity>

    fun findByKnowledgeSpaceId(knowledgeSpaceId: Long): List<KnowledgeSourceEntity>

    fun findByKnowledgeSpaceIdAndId(
        knowledgeSpaceId: Long,
        id: Long,
    ): KnowledgeSourceEntity?
}

interface KnowledgeDocumentRepository : JpaRepository<KnowledgeDocumentEntity, Long> {
    fun findByKnowledgeSpaceId(knowledgeSpaceId: Long): List<KnowledgeDocumentEntity>

    fun findByKnowledgeSourceId(knowledgeSourceId: Long): List<KnowledgeDocumentEntity>
}

interface KnowledgeChunkRepository : JpaRepository<KnowledgeChunkEntity, Long> {
    fun findByKnowledgeSpaceIdAndStatus(
        knowledgeSpaceId: Long,
        status: KnowledgeChunkStatus,
    ): List<KnowledgeChunkEntity>

    fun findByGuildIdAndKnowledgeSpaceIdInAndStatus(
        guildId: Long,
        knowledgeSpaceIds: Collection<Long>,
        status: KnowledgeChunkStatus,
    ): List<KnowledgeChunkEntity>

    fun findByKnowledgeDocumentIdOrderByChunkIndex(knowledgeDocumentId: Long): List<KnowledgeChunkEntity>
}

interface EmbeddingIndexJobRepository : JpaRepository<EmbeddingIndexJobEntity, Long> {
    fun findTop20ByGuildIdOrderByQueuedAtDesc(guildId: Long): List<EmbeddingIndexJobEntity>

    fun findTop10ByGuildIdAndKnowledgeSpaceIdOrderByQueuedAtDesc(
        guildId: Long,
        knowledgeSpaceId: Long,
    ): List<EmbeddingIndexJobEntity>
}

interface RetrievalPolicyRepository : JpaRepository<RetrievalPolicyEntity, Long> {
    fun findByGuildIdAndChannelIdAndKnowledgeSpaceIdAndStatus(
        guildId: Long,
        channelId: Long?,
        knowledgeSpaceId: Long?,
        status: RetrievalPolicyStatus,
    ): RetrievalPolicyEntity?

    fun findByGuildIdAndChannelIdAndStatus(
        guildId: Long,
        channelId: Long?,
        status: RetrievalPolicyStatus,
    ): List<RetrievalPolicyEntity>
}

interface NetworkOverviewProjectionRepository : JpaRepository<NetworkOverviewProjectionEntity, Long> {
    fun findByGuildId(guildId: Long): NetworkOverviewProjectionEntity?
}

interface AiFeedbackRepository : JpaRepository<AiFeedbackEntity, Long> {
    fun findTop20ByGuildIdAndChannelIdOrderByCreatedAtDesc(
        guildId: Long,
        channelId: Long,
    ): List<AiFeedbackEntity>

    fun findTop50ByGuildIdOrderByCreatedAtDesc(guildId: Long): List<AiFeedbackEntity>

    fun findTop200ByGuildIdOrderByCreatedAtDesc(guildId: Long): List<AiFeedbackEntity>

    fun findByGuildIdAndRequestIdAndUserId(
        guildId: Long,
        requestId: String,
        userId: Long,
    ): AiFeedbackEntity?

    fun countByGuildId(guildId: Long): Long

    fun findByGuildIdAndId(
        guildId: Long,
        id: Long,
    ): AiFeedbackEntity?

    fun findTop50ByGuildIdAndStatusOrderByCreatedAtDesc(
        guildId: Long,
        status: FeedbackStatus,
    ): List<AiFeedbackEntity>
}

interface MultiResponsePolicyRepository : JpaRepository<MultiResponsePolicyEntity, Long> {
    fun findByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long?,
    ): MultiResponsePolicyEntity?

    fun findByGuildIdAndChannelIdIsNull(guildId: Long): MultiResponsePolicyEntity?
}

interface MultiResponseRunRepository : JpaRepository<MultiResponseRunEntity, Long> {
    fun findByRequestId(requestId: String): MultiResponseRunEntity?

    fun findTop20ByGuildIdOrderByStartedAtDesc(guildId: Long): List<MultiResponseRunEntity>
}

interface CandidateAnswerRepository : JpaRepository<CandidateAnswerEntity, Long> {
    fun findByRunId(runId: Long): List<CandidateAnswerEntity>

    fun findByRunIdAndId(
        runId: Long,
        id: Long,
    ): CandidateAnswerEntity?
}

interface SynthesisResultRepository : JpaRepository<SynthesisResultEntity, Long> {
    fun findByRunId(runId: Long): SynthesisResultEntity?
}

interface AiPresetRepository : JpaRepository<AiPresetEntity, Long> {
    fun findByGuildId(guildId: Long): List<AiPresetEntity>
}

interface PresetRevisionRepository : JpaRepository<PresetRevisionEntity, Long> {
    fun findByPresetIdOrderByRevisionDesc(presetId: Long): List<PresetRevisionEntity>

    fun deleteByPresetId(presetId: Long)
}

interface PublishedPresetRepository : JpaRepository<PublishedPresetEntity, Long> {
    fun findByStatusOrderByLikeCountDescPublishedAtDesc(status: PublishedPresetStatus): List<PublishedPresetEntity>

    fun findBySlug(slug: String): PublishedPresetEntity?
}

interface PresetImportRepository : JpaRepository<PresetImportEntity, Long> {
    fun findByTargetGuildId(targetGuildId: Long): List<PresetImportEntity>
}

interface PresetReactionRepository : JpaRepository<PresetReactionEntity, Long> {
    fun findByPublishedPresetIdAndUserIdAndReaction(
        publishedPresetId: Long,
        userId: Long,
        reaction: String,
    ): PresetReactionEntity?
}

interface PresetReportRepository : JpaRepository<PresetReportEntity, Long> {
    fun findByStatus(status: PresetReportStatus): List<PresetReportEntity>

    fun findByPublishedPresetIdAndReporterUserIdAndStatus(
        publishedPresetId: Long,
        reporterUserId: Long,
        status: PresetReportStatus,
    ): PresetReportEntity?
}

interface AiNetworkEventRepository : JpaRepository<AiNetworkEventEntity, Long> {
    fun findTop20ByGuildIdOrderByCreatedAtDesc(guildId: Long): List<AiNetworkEventEntity>

    fun findByGuildIdAndEventType(
        guildId: Long,
        eventType: String,
    ): List<AiNetworkEventEntity>

    /** 프로바이더별 참여 이력 타임라인(Phase 2 어드민 대시보드 (c)). 최신순 최대 50건. */
    fun findTop50ByGuildIdAndProviderUserIdOrderByCreatedAtDesc(
        guildId: Long,
        providerUserId: Long,
    ): List<AiNetworkEventEntity>

    /**
     * 길드 전체 프로바이더 참여 이력 타임라인(특정 프로바이더 필터 없이). 최신순 최대 50건.
     * "프로바이더 참여 이력" 의미에 맞게 providerUserId 가 있는 이벤트(provider_joined/overload 등)만 —
     * ai_level_up·network_level 등 프로바이더 무관(providerUserId=null) 이벤트는 제외한다.
     */
    fun findTop50ByGuildIdAndProviderUserIdIsNotNullOrderByCreatedAtDesc(guildId: Long): List<AiNetworkEventEntity>
}

interface ChannelAiRoutingPolicyRepository : JpaRepository<ChannelAiRoutingPolicyEntity, Long> {
    fun findByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): ChannelAiRoutingPolicyEntity?

    fun findByGuildId(guildId: Long): List<ChannelAiRoutingPolicyEntity>
}

interface ProviderDurableRevocationRepository : JpaRepository<ProviderDurableRevocationEntity, Long> {
    fun findByProviderIdAndGuildId(
        providerId: Long,
        guildId: Long,
    ): ProviderDurableRevocationEntity?
}

interface BlocklistRepository : JpaRepository<BlocklistEntity, Long> {
    fun findByGuildIdAndUserId(
        guildId: Long,
        userId: Long,
    ): BlocklistEntity?

    fun findByGuildId(guildId: Long): List<BlocklistEntity>

    fun deleteByGuildIdAndUserId(
        guildId: Long,
        userId: Long,
    )

    fun deleteByGuildId(guildId: Long)
}
