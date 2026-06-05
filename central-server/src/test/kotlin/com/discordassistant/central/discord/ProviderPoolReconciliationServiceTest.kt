package com.discordassistant.central.discord

import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.platform.discord.ProviderPoolReconciliationService
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

private class ReconcileConn : AgentConnection {
    override val remoteId = "reconcile"
    var closed: String? = null

    override fun sendFrame(frame: Frame) {}

    override fun close(reason: String) {
        closed = reason
    }
}

@SpringBootTest
@Transactional
class ProviderPoolReconciliationServiceTest
    @Autowired
    constructor(
        val reconciliation: ProviderPoolReconciliationService,
        val registry: ConnectionRegistry,
        val registration: ProviderRegistrationService,
        val tokens: TokenService,
        val schedules: ProviderScheduleService,
        val policies: ContributionPolicyService,
        val policy: PolicyService,
        val channelProfiles: ChannelAiProfileService,
    ) {
        @Test
        fun `멤버 이탈은 해당 길드 provider 상태만 정리하고 다른 길드는 유지한다`() {
            val conn100 = ReconcileConn()
            val conn200 = ReconcileConn()
            registry.register(ProviderSession(conn100, providerId = 77, guildId = 100))
            registry.register(ProviderSession(conn200, providerId = 77, guildId = 200))
            val token100 = registration.requestJoin(77, 100, autoApprove = true).token!!
            val token200 = registration.requestJoin(77, 200, autoApprove = true).token!!
            schedules.setSchedule(77, 100, 9, 18)
            policies.setModels(77, listOf("llama3"), ModelBurden.LIGHT)

            assertTrue(reconciliation.cleanupMember(100, 77))

            assertNull(registry.byProvider(100, 77))
            assertEquals(conn200, registry.byProvider(200, 77)!!.connection)
            assertNull(registration.stateOf(77, 100))
            assertEquals(com.discordassistant.central.provider.domain.model.ProviderState.APPROVED, registration.stateOf(77, 200))
            assertNull(tokens.verify(token100))
            assertEquals(200L, tokens.verify(token200)!!.guildId)
            assertTrue(schedules.isAvailableNow(77, 100))
            assertTrue(policies.policies(77).isNotEmpty()) // 멀티 길드 보호: 전역 provider 정책은 멤버 이탈만으로 삭제하지 않음
            assertTrue(conn100.closed!!.contains("서버를 떠나"))
            assertNull(conn200.closed)
        }

        @Test
        fun `채널 삭제 정리는 허용 채널과 채널 AI 프로필을 제거한다`() {
            policy.allowChannel(300, 400, adminId = 1)
            channelProfiles.set(300, 400, "냥시스턴트", avatarUrl = "https://example.com/cat.png")

            reconciliation.cleanupChannel(300, 400)

            assertTrue(policy.allowedChannelIds(300).isEmpty())
            assertNull(channelProfiles.get(300, 400))
        }
    }
