package com.discordassistant.central.dashboard

import com.discordassistant.central.policy.PolicyService
import com.discordassistant.central.provider.AuditLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/** 대시보드 쓰기 API(차수 14 #203/#204) — 정책 위임 검증(직접 호출). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["central.oauth.enabled=true"]) // 쓰기 컨트롤러 조건 활성
@Import(DashboardWriteController::class, PolicyService::class, AuditLog::class)
class DashboardWriteControllerTest
    @Autowired
    constructor(
        val write: DashboardWriteController,
        val policy: PolicyService,
    ) {
        @Test
        fun `auto-approve 토글이 정책에 반영`() {
            write.setAutoApprove(900, enabled = true, user = null)
            assertTrue(policy.isAutoApprove(900))
            write.setAutoApprove(900, enabled = false, user = null)
            assertTrue(!policy.isAutoApprove(900))
        }

        @Test
        fun `welcome 메시지 설정 반영`() {
            write.setWelcome(901, message = "어서오세요", user = null)
            assertEquals("어서오세요", policy.guildWelcomeMessage(901))
        }

        @Test
        fun `role-policy 설정 반영`() {
            val r = write.setRolePolicy(902, roleId = 5, level = "standard", dailyLimit = 30, user = null)
            assertEquals("STANDARD", r["level"])
        }
    }
