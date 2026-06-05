package com.discordassistant.central.persistence

import com.discordassistant.central.domain.FeedbackStatus
import com.discordassistant.central.domain.KnowledgeChunkStatus
import com.discordassistant.central.domain.KnowledgeSourceStatus
import com.discordassistant.central.domain.PresetReportStatus
import com.discordassistant.central.domain.PublishedPresetStatus
import com.discordassistant.central.domain.RetrievalPolicyStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

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

    fun findByGuildIdAndProviderUserIdIn(
        guildId: Long,
        providerUserIds: Collection<Long>,
    ): List<ProviderCapabilityProfileEntity>
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
