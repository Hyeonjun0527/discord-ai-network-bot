package com.discordassistant.central.provider

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.platform.discord.BotChannelInfo
import com.discordassistant.central.platform.discord.BotGuildInfo
import com.discordassistant.central.platform.discord.BotGuildLister
import com.discordassistant.central.provider.adapter.inbound.web.AdminActionRequest
import com.discordassistant.central.provider.adapter.inbound.web.AdminPolicyRequest
import com.discordassistant.central.provider.adapter.inbound.web.ProviderAdminController
import com.discordassistant.central.provider.application.DurableTokenService
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.ProviderRosterInfo
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
 * 권한 상승 불가, 기존 서비스 재사용, 로스터 보강(이름·모델·오늘)·자동 승인 토글 검증.
 */
class ProviderAdminControllerTest {
    private class Ctx(
        val ctrl: ProviderAdminController,
        val reg: ProviderRegistrationService,
        val dtoken: String,
        val rosterState: MutableMap<String, Boolean>,
    )

    private fun fakeBot(admin: Boolean) =
        object : BotGuildLister {
            override fun botGuildIds() = emptySet<Long>()

            override fun botGuilds() = emptyList<BotGuildInfo>()

            override fun botChannels(guildId: Long) = emptyList<BotChannelInfo>()

            override fun isGuildAdmin(
                guildId: Long,
                userId: Long,
            ) = admin

            override fun memberName(
                guildId: Long,
                userId: Long,
            ) = "user_$userId"
        }

    private fun setup(admin: Boolean): Ctx {
        val clock = Clock.fixed(Instant.ofEpochSecond(1_000_000), ZoneOffset.UTC)
        val durable = DurableTokenService("admin-test-secret-key", 86_400, clock)
        val tokens = TokenService(ttlSeconds = 600, durable = durable)
        val reg = ProviderRegistrationService(tokens, AuditLog())
        val state = mutableMapOf("auto" to false)
        val roster =
            object : ProviderRosterInfo {
                override fun modelsByProvider(guildId: Long) = mapOf(88L to 2)

                override fun todayByProvider(guildId: Long) = mapOf(88L to 5L)

                override fun isAutoApprove(guildId: Long) = state["auto"]!!

                override fun setAutoApprove(
                    guildId: Long,
                    value: Boolean,
                    adminId: Long,
                ) {
                    state["auto"] = value
                }
            }
        val ctrl = ProviderAdminController(tokens, reg, fakeBot(admin), roster)
        val dtoken = durable.issueDurable(7L, 100L)!!
        return Ctx(ctrl, reg, dtoken, state)
    }

    @Test
    fun `관리자는 승인 대기 Provider 를 승인한다`() {
        val c = setup(admin = true)
        c.reg.requestJoin(99L, 100L, autoApprove = false)
        val res = c.ctrl.approve(AdminActionRequest(c.dtoken, 100L, 99L))
        assertTrue(res.ok)
        assertEquals(ProviderState.APPROVED, c.reg.stateOf(99L, 100L))
    }

    @Test
    fun `비관리자는 승인이 거부되고 상태가 불변이다`() {
        val c = setup(admin = false)
        c.reg.requestJoin(99L, 100L, autoApprove = false)
        val res = c.ctrl.approve(AdminActionRequest(c.dtoken, 100L, 99L))
        assertFalse(res.ok)
        assertEquals(ProviderState.PENDING, c.reg.stateOf(99L, 100L))
    }

    @Test
    fun `durable 이 아닌 토큰은 거부된다`() {
        val c = setup(admin = true)
        c.reg.requestJoin(99L, 100L, autoApprove = false)
        val res = c.ctrl.approve(AdminActionRequest("ABCDE-FGHIJ-KLMNP", 100L, 99L))
        assertFalse(res.ok)
        assertEquals(ProviderState.PENDING, c.reg.stateOf(99L, 100L))
    }

    @Test
    fun `manage 는 이름·모델·오늘·정책을 채운다`() {
        val c = setup(admin = true)
        c.reg.requestJoin(99L, 100L, autoApprove = false) // PENDING
        c.reg.requestJoin(88L, 100L, autoApprove = true) // APPROVED
        val res = c.ctrl.manage(AdminActionRequest(c.dtoken, 100L))
        assertTrue(res.ok)
        assertFalse(res.policy!!.autoApprove)
        assertTrue(res.pending.any { it.providerId == 99L && it.name == "user_99" })
        val r88 = res.roster.first { it.providerId == 88L }
        assertEquals("user_88", r88.name)
        assertEquals(2, r88.models)
        assertEquals(5L, r88.today)
    }

    @Test
    fun `setPolicy 는 자동 승인을 토글한다(관리자만)`() {
        val c = setup(admin = true)
        assertTrue(c.ctrl.setPolicy(AdminPolicyRequest(c.dtoken, 100L, autoApprove = true)).ok)
        assertEquals(true, c.rosterState["auto"])

        val denied = setup(admin = false)
        assertFalse(denied.ctrl.setPolicy(AdminPolicyRequest(denied.dtoken, 100L, autoApprove = true)).ok)
        assertEquals(false, denied.rosterState["auto"]) // 비관리자는 변경 못 함
    }

    @Test
    fun `관리자는 승인된 Provider 를 제거한다`() {
        val c = setup(admin = true)
        c.reg.requestJoin(88L, 100L, autoApprove = true)
        val res = c.ctrl.remove(AdminActionRequest(c.dtoken, 100L, 88L))
        assertTrue(res.ok)
        assertEquals(ProviderState.REMOVED, c.reg.stateOf(88L, 100L))
    }
}
