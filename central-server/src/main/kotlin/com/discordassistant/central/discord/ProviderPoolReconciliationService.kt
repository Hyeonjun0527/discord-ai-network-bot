package com.discordassistant.central.discord

import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.guild.application.GuildRemovalCleanupService
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.ProviderScheduleService
import com.discordassistant.central.relay.ConnectionRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Discord 이벤트 지연/누락, 멤버·채널 삭제, DB 복원 뒤 stale 상태를 정리하는 정합성 서비스. */
@Service
class ProviderPoolReconciliationService(
    private val registry: ConnectionRegistry,
    private val registration: ProviderRegistrationService,
    private val schedules: ProviderScheduleService,
    private val policy: PolicyService,
    private val channelProfiles: ChannelAiProfileService,
    private val guildCleanup: GuildRemovalCleanupService,
) {
    private val log = LoggerFactory.getLogger(ProviderPoolReconciliationService::class.java)

    /** 서버 멤버가 나가거나 삭제된 계정으로 확인되면 해당 서버의 provider 상태만 정리한다. 기여 로그는 유지한다. */
    fun cleanupMember(
        guildId: Long,
        providerId: Long,
    ): Boolean {
        val closed = registry.closeProviderInGuild(guildId, providerId, "프로바이더가 서버를 떠나 해당 서버 풀에서 정리됩니다")
        val removed = registration.removeMemberFromGuild(providerId, guildId)
        schedules.deleteProviderGuild(providerId, guildId)
        // contribution policy 는 현재 provider 단위 설정이라 멤버 이탈만으로 삭제하지 않는다.
        if (closed || removed) log.info("멤버 프로바이더 정리(guild={}, provider={})", guildId, providerId)
        return closed || removed
    }

    /** 채널 삭제/정합성 검사에서 해당 채널 정책과 AI 프로필을 정리한다. */
    fun cleanupChannel(
        guildId: Long,
        channelId: Long,
    ) {
        policy.cleanupChannel(guildId, channelId)
        channelProfiles.clear(guildId, channelId)
        log.info("채널 설정 정리(guild={}, channel={})", guildId, channelId)
    }

    /** 현재 봇이 실제로 속한 서버 목록에 없는 길드 상태를 정리한다. DB 복원/이벤트 누락 방어용. */
    fun cleanupMissingGuilds(
        knownGuildIds: Set<Long>,
        candidateGuildIds: Set<Long>,
    ): Int {
        val missing = candidateGuildIds - knownGuildIds
        missing.forEach { guildCleanup.cleanup(it) }
        return missing.size
    }
}
