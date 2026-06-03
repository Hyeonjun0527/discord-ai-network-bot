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
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

    /**
     * 채널 AI 행을 PESSIMISTIC_WRITE 로 잠근 채 조회한다(트랜잭션 필요).
     * behavior version 채번(`MAX(version)+1`)을 같은 채널 안에서 직렬화해
     * `uk_ai_behavior_version` 유니크 위반 race(동시 두 요청이 같은 version 으로 insert)를 막는 데 쓴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChannelAiEntity c where c.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
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

    /**
     * 제안 행을 PESSIMISTIC_WRITE 로 잠근 채 조회한다(트랜잭션 필요).
     * 동시 승인/거절(`approveProposal`/`rejectProposal`)을 직렬화해 이중 APPROVED·lost update 를 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AiChangeProposalEntity p where p.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): AiChangeProposalEntity?

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
}

interface GuildOnboardingConsentRepository : JpaRepository<GuildOnboardingConsentEntity, Long> {
    fun findByGuildIdOrderByCreatedAtDesc(guildId: Long): List<GuildOnboardingConsentEntity>

    fun deleteByGuildId(guildId: Long)
}

interface GuildOnboardingRunRepository : JpaRepository<GuildOnboardingRunEntity, Long> {
    fun findByGuildIdOrderByCreatedAtDesc(guildId: Long): List<GuildOnboardingRunEntity>

    fun findByProposalId(proposalId: Long): GuildOnboardingRunEntity?

    fun deleteByGuildId(guildId: Long)
}

interface GuildOnboardingOptOutRepository : JpaRepository<GuildOnboardingOptOutEntity, Long> {
    fun findByGuildId(guildId: Long): List<GuildOnboardingOptOutEntity>

    fun existsByGuildIdAndUserId(
        guildId: Long,
        userId: Long,
    ): Boolean

    @org.springframework.transaction.annotation.Transactional
    fun deleteByGuildIdAndUserId(
        guildId: Long,
        userId: Long,
    )

    fun deleteByGuildId(guildId: Long)
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

    /** 온보딩 백필 지식공간 재사용용 — 같은 채널 AI + 같은 표시이름의 기존 space(가장 먼저 만든 것)를 찾는다. */
    fun findFirstByChannelAiIdAndDisplayNameOrderByIdAsc(
        channelAiId: Long,
        displayName: String,
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
