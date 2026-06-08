package com.discordassistant.central.policy

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.shared.ModelBurden
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PolicyService::class, AuditLog::class)
class PolicyServiceTest
    @Autowired
    constructor(
        val policy: PolicyService,
    ) {
        @Test
        fun `채널 허용·거부·판정`() {
            // 허용 채널 미설정 → 제한 없음
            assertTrue(policy.isChannelAllowed(100, 999))
            policy.allowChannel(100, 200, adminId = 1)
            assertTrue(policy.isChannelAllowed(100, 200))
            assertFalse(policy.isChannelAllowed(100, 201))
            policy.denyChannel(100, 200, adminId = 1)
            assertTrue(policy.isChannelAllowed(100, 999)) // 다시 제한 없음
        }

        @Test
        fun `역할별 최대 부담 수준(다중 역할)`() {
            policy.setRolePolicy(100, roleId = 1, ModelBurden.STANDARD, dailyLimit = 30, adminId = 1)
            policy.setRolePolicy(100, roleId = 2, ModelBurden.HEAVY, dailyLimit = 50, adminId = 1)
            assertEquals(ModelBurden.HEAVY, policy.maxAllowedBurden(100, listOf(1, 2)))
            assertEquals(ModelBurden.STANDARD, policy.maxAllowedBurden(100, listOf(1)))
            assertEquals(ModelBurden.LIGHT, policy.maxAllowedBurden(100, listOf(99))) // 기본
            assertEquals(50, policy.dailyLimit(100, listOf(1, 2)))
        }

        @Test
        fun `유저 일일 한도 — 기본 20·길드 기본값·무제한(0) 우선`() {
            // 설정 없으면 기본 20
            assertEquals(20, policy.dailyLimit(700, listOf(99)))
            // 길드 기본값 설정 → 그 값 사용(역할 정책 없는 일반 멤버)
            policy.setGuildDefaults(700, defaultModel = null, language = null, adminId = 1, defaultDailyLimit = 100)
            assertEquals(100, policy.dailyLimit(700, listOf(99)))
            // 길드 기본값 0 = 무제한
            policy.setGuildDefaults(700, defaultModel = null, language = null, adminId = 1, defaultDailyLimit = 0)
            assertEquals(0, policy.dailyLimit(700, listOf(99)))
            // 역할 정책 0(무제한)은 다른 역할 한도(30)보다 우선 — 무제한이 묻히지 않는다
            policy.setRolePolicy(701, roleId = 1, ModelBurden.STANDARD, dailyLimit = 30, adminId = 1)
            policy.setRolePolicy(701, roleId = 2, ModelBurden.LIGHT, dailyLimit = 0, adminId = 1)
            assertEquals(0, policy.dailyLimit(701, listOf(1, 2)))
        }

        @Test
        fun `부담 수준 허용 판정`() {
            assertTrue(policy.isBurdenAllowed(ModelBurden.HEAVY, ModelBurden.STANDARD))
            assertFalse(policy.isBurdenAllowed(ModelBurden.LIGHT, ModelBurden.HEAVY))
            assertFalse(policy.isBurdenAllowed(ModelBurden.HEAVY, ModelBurden.RESTRICTED))
        }

        @Test
        fun `승인 방식 설정`() {
            assertTrue(policy.isAutoApprove(100)) // 기본 자동 승인 ON
            policy.setAutoApprove(100, false, adminId = 1)
            assertFalse(policy.isAutoApprove(100))
            policy.setAutoApprove(100, true, adminId = 1)
            assertTrue(policy.isAutoApprove(100))
        }

        @Test
        fun `길드 환영 메시지 설정·조회(#174)`() {
            assertEquals(null, policy.guildWelcomeMessage(305))
            policy.setWelcomeMessage(305, "환영합니다!", adminId = 1)
            assertEquals("환영합니다!", policy.guildWelcomeMessage(305))
        }

        @Test
        fun `길드 기본 모델·언어 설정(빈 값은 보존)`() {
            assertEquals(null, policy.guildDefaultModel(300))
            assertEquals("ko", policy.guildLanguage(300)) // 기본
            policy.setGuildDefaults(300, defaultModel = "llama3", language = "en", adminId = 1)
            assertEquals("llama3", policy.guildDefaultModel(300))
            assertEquals("en", policy.guildLanguage(300))
            // 빈 값으로 호출하면 기존 값 보존
            policy.setGuildDefaults(300, defaultModel = "", language = null, adminId = 1)
            assertEquals("llama3", policy.guildDefaultModel(300))
            assertEquals("en", policy.guildLanguage(300))
        }
    }
