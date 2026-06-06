package com.discordassistant.central.provider

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.platform.discord.BotChannelInfo
import com.discordassistant.central.platform.discord.BotGuildInfo
import com.discordassistant.central.platform.discord.BotGuildLister
import com.discordassistant.central.provider.adapter.inbound.web.AdminActionRequest
import com.discordassistant.central.provider.adapter.inbound.web.ProviderAdminController
import com.discordassistant.central.provider.application.DurableTokenService
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.TokenService
import com.discordassistant.central.provider.domain.model.ProviderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 데스크톱 앱 관리 채널 — durable 토큰 신원 + JDA 관리자 판정 2단 게이트.
 * 권한 상승 불가(내 토큰으로 내가 관리자인 서버만), 기존 ProviderRegistrationService 재사용 검증.
 */
class ProviderAdminControllerTest {
    private fun fakeBot(admin: Boolean) =
        object : BotGuildLister {
            override fun botGuildIds() = emptySet<Long>()

            override fun botGuilds() = emptyList<BotGuildInfo>()

            override fun botChannels(guildId: Long) = emptyList<BotChannelInfo>()

            override fun isGuildAdmin(guildId: Long, userId: Long) = admin
        }

    private fun setup(admin: Boolean): Triple<ProviderAdminController, ProviderRegistrationService, String> {
        val clock = Clock.fixed(Instant.ofEpochSecond(1_000_000), ZoneOffset.UTC)
        val durable = DurableTokenService("admin-test-secret-key", 86_400, clock)
        val tokens = TokenService(ttlSeconds = 600, durable = durable)
        val reg = ProviderRegistrationService(tokens, AuditLog())
        val ctrl = ProviderAdminController(tokens, reg, fakeBot(admin))
        val dtoken = durable.issueDurable(7L, 100L)!! // 요청자 userId=7, guild=100
        return Triple(ctrl, reg, dtoken)
    }

    @Test
    fun `관리자는 승인 대기 Provider 를 승인한다`() {
        val (ctrl, reg, dtoken) = setup(admin = true)
        reg.requestJoin(99L, 100L, autoApprove = false) // PENDING
        val res = ctrl.approve(AdminActionRequest(dtoken, 100L, 99L))
        assertTrue(res.ok)
        assertEquals(ProviderState.APPROVED, reg.stateOf(99L, 100L))
    }

    @Test
    fun `비관리자는 승인이 거부되고 상태가 불변이다`() {
        val (ctrl, reg, dtoken) = setup(admin = false)
        reg.requestJoin(99L, 100L, autoApprove = false)
        val res = ctrl.approve(AdminActionRequest(dtoken, 100L, 99L))
        assertFalse(res.ok)
        assertEquals(ProviderState.PENDING, reg.stateOf(99L, 100L))
    }

    @Test
    fun `durable 이 아닌 토큰은 거부된다`() {
        val (ctrl, reg, _) = setup(admin = true)
        reg.requestJoin(99L, 100L, autoApprove = false)
        val res = ctrl.approve(AdminActionRequest("ABCDE-FGHIJ-KLMNP", 100L, 99L))
        assertFalse(res.ok)
        assertEquals(ProviderState.PENDING, reg.stateOf(99L, 100L))
    }

    @Test
    fun `manage 는 승인 대기와 로스터를 반환한다`() {
        val (ctrl, reg, dtoken) = setup(admin = true)
        reg.requestJoin(99L, 100L, autoApprove = false) // PENDING
        reg.requestJoin(88L, 100L, autoApprove = true) // APPROVED
        val res = ctrl.manage(AdminActionRequest(dtoken, 100L))
        assertTrue(res.ok)
        assertTrue(99L in res.pending)
        assertTrue(res.roster.any { it.providerId == 88L && it.state == "APPROVED" })
    }

    @Test
    fun `관리자는 승인된 Provider 를 제거한다`() {
        val (ctrl, reg, dtoken) = setup(admin = true)
        reg.requestJoin(88L, 100L, autoApprove = true) // APPROVED
        val res = ctrl.remove(AdminActionRequest(dtoken, 100L, 88L))
        assertTrue(res.ok)
        assertEquals(ProviderState.REMOVED, reg.stateOf(88L, 100L))
    }
}
