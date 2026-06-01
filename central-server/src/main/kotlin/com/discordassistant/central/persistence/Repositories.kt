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
