package com.discordassistant.central.guild.application

import com.discordassistant.central.channelai.application.AutoRespondChannelRegistry
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.participation.application.NexaParticipationFlagService
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
    private val autoRespondChannels: AutoRespondChannelRegistry,
    private val blocklist: BlocklistService,
    private val participationFlags: NexaParticipationFlagService,
) {
    private val log = LoggerFactory.getLogger(GuildRemovalCleanupService::class.java)

    fun cleanup(guildId: Long): GuildCleanupResult {
        val closedSessions = registry.closeGuild(guildId, "봇이 서버에서 제거되어 프로바이더 풀을 정리합니다")
        val removedProviders = registration.removeGuild(guildId)
        contributionPolicies.deleteProviders(removedProviders)
        schedules.deleteGuild(guildId)
        channelProfiles.clearGuild(guildId)
        autoRespondChannels.invalidateGuild(guildId) // 자동응답 채널 캐시(Set)도 비워 미세 누수 방지
        blocklist.clearGuild(guildId)
        policy.cleanupGuild(guildId)
        participationFlags.cleanupGuild(guildId) // stale NEXA LIVE override 잔존→재입장 시 무단 발화 방지
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
