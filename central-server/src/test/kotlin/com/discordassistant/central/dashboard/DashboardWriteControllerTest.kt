package com.discordassistant.central.dashboard

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.guild.adapter.inbound.web.AutoApproveDashboardWriteResult
import com.discordassistant.central.guild.adapter.inbound.web.DashboardWriteController
import com.discordassistant.central.guild.adapter.inbound.web.RolePolicyDashboardWriteResult
import com.discordassistant.central.guild.adapter.inbound.web.WelcomeDashboardWriteResult
import com.discordassistant.central.guild.application.PolicyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

/** 대시보드 쓰기 API(차수 14 #203/#204) — 정책 위임 검증(직접 호출). 컨트롤러는 항상 등록(조건 없음). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DashboardWriteController::class, PolicyService::class, AuditLog::class)
class DashboardWriteControllerTest
    @Autowired
    constructor(
        val write: DashboardWriteController,
        val policy: PolicyService,
    ) {
        @Test
        fun `auto-approve 토글이 정책에 반영`() {
            val enabled = write.setAutoApprove(900, enabled = true, user = null)
            assertEquals(AutoApproveDashboardWriteResult(guildId = "900", autoApprove = true), enabled)
            assertTrue(policy.isAutoApprove(900))
            val disabled = write.setAutoApprove(900, enabled = false, user = null)
            assertEquals(AutoApproveDashboardWriteResult(guildId = "900", autoApprove = false), disabled)
            assertTrue(!policy.isAutoApprove(900))
        }

        @Test
        fun `welcome 메시지 설정 반영`() {
            val response = write.setWelcome(901, message = "어서오세요", user = null)
            assertEquals(WelcomeDashboardWriteResult(guildId = "901", ok = true), response)
            assertEquals("어서오세요", policy.guildWelcomeMessage(901))
        }

        @Test
        fun `role-policy 설정 반영`() {
            val r = write.setRolePolicy(902, roleId = 5, level = "standard", dailyLimit = 30, user = null)
            assertEquals(
                RolePolicyDashboardWriteResult(
                    guildId = "902",
                    roleId = "5",
                    level = "STANDARD",
                    dailyLimit = 30,
                    dailyLimitUnlimited = false,
                    dailyLimitLabel = "30 회",
                ),
                r,
            )
        }

        @Test
        fun `role-policy dailyLimit 0 은 API 에서 무제한으로 명시된다`() {
            val r = write.setRolePolicy(903, roleId = 5, level = "standard", dailyLimit = 0, user = null)

            assertEquals(0, r.dailyLimit)
            assertEquals(true, r.dailyLimitUnlimited)
            assertEquals("무제한", r.dailyLimitLabel)
            assertEquals(0, policy.dailyLimit(903, listOf(5)))
        }
    }
