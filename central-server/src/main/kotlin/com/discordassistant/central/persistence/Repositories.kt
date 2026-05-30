package com.discordassistant.central.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface GuildRepository : JpaRepository<GuildEntity, Long>

interface AllowedChannelRepository : JpaRepository<AllowedChannelEntity, Long> {
    fun findByGuildId(guildId: Long): List<AllowedChannelEntity>
    fun existsByGuildIdAndChannelId(guildId: Long, channelId: Long): Boolean
    fun deleteByGuildIdAndChannelId(guildId: Long, channelId: Long)
}

interface RolePolicyRepository : JpaRepository<RolePolicyEntity, Long> {
    fun findByGuildId(guildId: Long): List<RolePolicyEntity>
    fun findByGuildIdAndRoleId(guildId: Long, roleId: Long): RolePolicyEntity?
}

interface ProviderRepository : JpaRepository<ProviderEntity, Long> {
    fun findByProviderUserIdAndGuildId(providerUserId: Long, guildId: Long): ProviderEntity?
    fun findByGuildIdAndState(guildId: Long, state: String): List<ProviderEntity>
}

interface ProviderContributionPolicyRepository : JpaRepository<ProviderContributionPolicyEntity, Long> {
    fun findByProviderId(providerId: Long): List<ProviderContributionPolicyEntity>
}

interface AiRequestRepository : JpaRepository<AiRequestEntity, Long> {
    fun findByRequestId(requestId: String): AiRequestEntity?
}

interface UsageLogRepository : JpaRepository<UsageLogEntity, Long> {
    fun countByGuildIdAndUserId(guildId: Long, userId: Long): Long
    fun countByGuildIdAndUserIdAndCreatedAtAfter(guildId: Long, userId: Long, createdAt: Instant): Long
}

interface ContributionLogRepository : JpaRepository<ContributionLogEntity, Long> {
    fun countByProviderId(providerId: Long): Long
}

interface ProviderHealthRepository : JpaRepository<ProviderHealthEntity, Long> {
    fun findByProviderId(providerId: Long): ProviderHealthEntity?
}
