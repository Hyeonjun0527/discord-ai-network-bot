package com.discordassistant.central.discord

import com.discordassistant.central.policy.PolicyService
import com.discordassistant.central.provider.application.ContributionPolicyService
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.ProviderScheduleService
import com.discordassistant.central.quota.application.BlocklistService
import com.discordassistant.central.relay.ConnectionRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 봇이 Discord 서버에서 제거될 때 해당 서버의 운영 상태를 정리한다. */
@Service
class GuildRemovalCleanupService(
    private val registry: ConnectionRegistry,
    private val registration: ProviderRegistrationService,
    private val contributionPolicies: ContributionPolicyService,
    private val schedules: ProviderScheduleService,
    private val policy: PolicyService,
    private val channelProfiles: ChannelAiProfileService,
    private val blocklist: BlocklistService,
) {
    private val log = LoggerFactory.getLogger(GuildRemovalCleanupService::class.java)

    fun cleanup(guildId: Long): GuildCleanupResult {
        val closedSessions = registry.closeGuild(guildId, "봇이 서버에서 제거되어 프로바이더 풀을 정리합니다")
        val removedProviders = registration.removeGuild(guildId)
        contributionPolicies.deleteProviders(removedProviders)
        schedules.deleteGuild(guildId)
        channelProfiles.clearGuild(guildId)
        blocklist.clearGuild(guildId)
        policy.cleanupGuild(guildId)
        val result = GuildCleanupResult(guildId, closedSessions, removedProviders.size)
        log.info(
            "길드 제거 정리 완료(guild={}, closedSessions={}, removedProviders={})",
            result.guildId,
            result.closedSessions,
            result.removedProviders,
        )
        return result
    }
}

data class GuildCleanupResult(
    val guildId: Long,
    val closedSessions: Int,
    val removedProviders: Int,
)
