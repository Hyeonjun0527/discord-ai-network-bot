package com.discordassistant.central.policy

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.provider.AuditLog
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
class PolicyServiceTest @Autowired constructor(val policy: PolicyService) {

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
    fun `부담 수준 허용 판정`() {
        assertTrue(policy.isBurdenAllowed(ModelBurden.HEAVY, ModelBurden.STANDARD))
        assertFalse(policy.isBurdenAllowed(ModelBurden.LIGHT, ModelBurden.HEAVY))
        assertFalse(policy.isBurdenAllowed(ModelBurden.HEAVY, ModelBurden.RESTRICTED))
    }

    @Test
    fun `승인 방식 설정`() {
        assertFalse(policy.isAutoApprove(100))
        policy.setAutoApprove(100, true, adminId = 1)
        assertTrue(policy.isAutoApprove(100))
    }
}
