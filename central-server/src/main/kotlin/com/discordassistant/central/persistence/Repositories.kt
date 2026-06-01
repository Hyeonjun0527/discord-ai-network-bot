package com.discordassistant.central.persistence

import org.springframework.data.jpa.repository.JpaRepository
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

interface ProviderRepository : JpaRepository<ProviderEntity, Long> {
    fun findByProviderUserIdAndGuildId(
        providerUserId: Long,
        guildId: Long,
    ): ProviderEntity?

    fun findByGuildIdAndState(
        guildId: Long,
        state: String,
    ): List<ProviderEntity>
}

interface ProviderContributionPolicyRepository : JpaRepository<ProviderContributionPolicyEntity, Long> {
    fun findByProviderId(providerId: Long): List<ProviderContributionPolicyEntity>

    fun deleteByProviderIdIn(providerIds: Collection<Long>)
}

interface AiRequestRepository : JpaRepository<AiRequestEntity, Long> {
    fun findByRequestId(requestId: String): AiRequestEntity?

    fun countByGuildId(guildId: Long): Long

    fun findTop20ByGuildIdOrderByIdDesc(guildId: Long): List<AiRequestEntity>

    fun findByProviderIdAndState(
        providerId: Long,
        state: String,
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

interface ChannelAiProfileRepository : JpaRepository<ChannelAiProfileEntity, Long> {
    fun findByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): ChannelAiProfileEntity?

    fun deleteByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    )

    fun deleteByGuildId(guildId: Long)
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
        status: String,
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
        status: String,
    ): List<KnowledgeChunkEntity>

    fun findByGuildIdAndKnowledgeSpaceIdInAndStatus(
        guildId: Long,
        knowledgeSpaceIds: Collection<Long>,
        status: String,
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
        status: String,
    ): RetrievalPolicyEntity?

    fun findByGuildIdAndChannelIdAndStatus(
        guildId: Long,
        channelId: Long?,
        status: String,
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
        status: String,
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
    fun findByStatusOrderByLikeCountDescPublishedAtDesc(status: String): List<PublishedPresetEntity>

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
    fun findByStatus(status: String): List<PresetReportEntity>

    fun findByPublishedPresetIdAndReporterUserIdAndStatus(
        publishedPresetId: Long,
        reporterUserId: Long,
        status: String,
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
