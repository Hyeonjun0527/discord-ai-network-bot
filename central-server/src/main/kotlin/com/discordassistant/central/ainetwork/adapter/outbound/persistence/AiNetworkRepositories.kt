package com.discordassistant.central.ainetwork.adapter.outbound.persistence

import com.discordassistant.central.ainetwork.domain.model.FeedbackStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/** ai-network Spring Data JPA 리포지토리(adapter/out). 길드 구성 프로필과 유저 호감도 원자 갱신을 보존. */

interface AiNetworkProfileRepository : JpaRepository<AiNetworkProfileEntity, Long> {
    fun findByGuildId(guildId: Long): AiNetworkProfileEntity?
}

interface UserAffinityRepository : JpaRepository<UserAffinityEntity, Long> {
    fun findByUserId(userId: Long): UserAffinityEntity?

    /**
     * 호감도를 원자적으로 증가시킨다(read-modify-write 없음, 동시 적립 안전).
     * @return 갱신된 행 수(행이 없으면 0).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE UserAffinityEntity a SET a.score = a.score + :amount, " +
            "a.lastInteractionAt = :at, a.updatedAt = :at WHERE a.userId = :userId",
    )
    fun addScore(
        @Param("userId") userId: Long,
        @Param("amount") amount: Long,
        @Param("at") at: Instant,
    ): Int

    /**
     * 단계를 조건부로 상향한다 — 현재 단계 ordinal 이 newOrdinal 보다 낮을 때만 1행에 영향.
     * 동시 적립 중 단계를 실제로 올린 트랜잭션만 1행을 반환하므로 멱등하다.
     * @return 갱신된 행 수(올라간 경우 1, 아니면 0).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE UserAffinityEntity a SET a.stage = :stage, a.stageOrdinal = :newOrdinal " +
            "WHERE a.userId = :userId AND a.stageOrdinal < :newOrdinal",
    )
    fun raiseStage(
        @Param("userId") userId: Long,
        @Param("stage") stage: String,
        @Param("newOrdinal") newOrdinal: Int,
    ): Int
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
