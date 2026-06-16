package com.discordassistant.central.discord

import com.discordassistant.central.channelai.application.AutoRespondChannelRegistry
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.guild.application.GuildRemovalCleanupService
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.provider.application.ContributionPolicyService
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.ProviderScheduleService
import com.discordassistant.central.provider.application.TokenService
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.shared.ModelBurden
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

private class CleanupConn : AgentConnection {
    override val remoteId = "cleanup"
    var closed: String? = null

    override fun sendFrame(frame: Frame) {}

    override fun close(reason: String) {
        closed = reason
    }
}

@SpringBootTest
@Transactional
class GuildRemovalCleanupServiceTest
    @Autowired
    constructor(
        val cleanup: GuildRemovalCleanupService,
        val registry: ConnectionRegistry,
        val registration: ProviderRegistrationService,
        val tokens: TokenService,
        val contributionPolicies: ContributionPolicyService,
        val schedules: ProviderScheduleService,
        val policy: PolicyService,
        val channelProfiles: ChannelAiProfileService,
        val autoRespondChannels: AutoRespondChannelRegistry,
    ) {
        @Test
        fun `길드 제거 정리는 프로바이더 세션 등록 토큰과 서버 설정을 제거한다`() {
            val guildId = 555_001L
            val conn = CleanupConn()
            val session = ProviderSession(conn, providerId = 9_001L, guildId = guildId)
            registry.register(session)
            val token = registration.requestJoin(9_001L, guildId, autoApprove = true).token!!
            registration.requestJoin(9_002L, guildId, autoApprove = false)
            contributionPolicies.setModels(9_001L, listOf("llama3"), ModelBurden.LIGHT)
            schedules.setSchedule(9_001L, guildId, fromHour = 9, toHour = 18)
            policy.allowChannel(guildId, channelId = 777, adminId = 1)
            policy.setWelcomeMessage(guildId, "안녕", adminId = 1)
            channelProfiles.set(guildId, channelId = 777, displayName = "니아", avatarUrl = null)

            val result = cleanup.cleanup(guildId)

            assertEquals(guildId, result.guildId)
            assertEquals(1, result.closedSessions)
            assertEquals(2, result.removedProviders)
            assertNotNull(conn.closed)
            assertEquals(0, registry.byGuild(guildId).size)
            assertNull(registration.stateOf(9_001L))
            assertNull(registration.stateOf(9_002L))
            assertNull(tokens.verify(token))
            assertTrue(contributionPolicies.policies(9_001L).isEmpty())
            assertTrue(schedules.isAvailableNow(9_001L, guildId))
            assertTrue(policy.allowedChannelIds(guildId).isEmpty())
            assertNull(policy.guildWelcomeMessage(guildId))
            assertNull(channelProfiles.get(guildId, 777))
        }

        @Test
        fun `길드 제거 정리는 자동응답 채널 캐시를 무효화한다`() {
            val guildId = 555_002L
            val channelId = 888L
            autoRespondChannels.setAutoRespond(guildId, channelId, on = true, actorId = 1L)
            assertTrue(autoRespondChannels.isAutoRespond(guildId, channelId)) // 캐시 적재(이후 stale 가능)

            cleanup.cleanup(guildId)

            // invalidateGuild 가 호출되지 않으면 캐시가 stale 한 true 를 계속 답해 미세 누수가 된다.
            assertFalse(autoRespondChannels.isAutoRespond(guildId, channelId))
        }
    }
