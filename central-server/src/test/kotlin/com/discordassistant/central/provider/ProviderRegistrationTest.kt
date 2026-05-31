package com.discordassistant.central.provider

import com.discordassistant.central.domain.ProviderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenServiceTest {
    @Test
    fun `발급→검증→단발성`() {
        val svc = TokenService(ttlSeconds = 600)
        val token = svc.issue(providerId = 1, guildId = 100)
        assertTrue(token.contains("-")) // 사람이 읽기 쉬운 그룹 포맷
        val binding = svc.verify(token)
        assertNotNull(binding)
        assertEquals(1L, binding!!.providerId)
        assertEquals(100L, binding.guildId)
        // 단발성: 두 번째 검증은 실패
        assertNull(svc.verify(token))
    }

    @Test
    fun `잘못된 토큰은 null`() {
        val svc = TokenService(ttlSeconds = 600)
        svc.issue(1, 100)
        assertNull(svc.verify("WRONG-TOKEN-XXXX"))
    }

    @Test
    fun `만료된 토큰은 null`() {
        val svc = TokenService(ttlSeconds = 0)
        val token = svc.issue(1, 100)
        Thread.sleep(2)
        assertNull(svc.verify(token))
    }

    @Test
    fun `평문 미저장(해시만) — revoke`() {
        val svc = TokenService(ttlSeconds = 600)
        val token = svc.issue(1, 100)
        assertEquals(1, svc.activeTokenCount())
        svc.revoke(token)
        assertNull(svc.verify(token))
    }
}

class ProviderRegistrationServiceTest {
    private fun service(): Triple<ProviderRegistrationService, TokenService, AuditLog> {
        val tokens = TokenService(ttlSeconds = 600)
        val audit = AuditLog()
        return Triple(ProviderRegistrationService(tokens, audit), tokens, audit)
    }

    @Test
    fun `수동 승인 흐름 — pending→approve→토큰`() {
        val (svc, tokens, audit) = service()
        val join = svc.requestJoin(providerId = 1, guildId = 100, autoApprove = false)
        assertEquals(ProviderState.PENDING, join.state)
        assertNull(join.token)
        assertEquals(listOf(1L), svc.pending(100))

        val token = svc.approve(providerId = 1, adminId = 999)
        assertNotNull(token)
        assertEquals(ProviderState.APPROVED, svc.stateOf(1))
        // 승인 토큰이 실제로 검증되어 올바른 binding 을 준다
        val binding = tokens.verify(token!!)
        assertEquals(1L, binding!!.providerId)
        assertEquals(100L, binding.guildId)
        assertTrue(audit.all().any { it.action == "provider_approve" })
    }

    @Test
    fun `자동 승인 — 즉시 토큰`() {
        val (svc, _, _) = service()
        val join = svc.requestJoin(providerId = 2, guildId = 100, autoApprove = true)
        assertEquals(ProviderState.APPROVED, join.state)
        assertNotNull(join.token)
    }

    @Test
    fun `거절·제거`() {
        val (svc, _, _) = service()
        svc.requestJoin(3, 100, autoApprove = false)
        assertTrue(svc.reject(3, adminId = 999))
        assertNull(svc.stateOf(3)) // 거절 시 제거

        svc.requestJoin(4, 100, autoApprove = true)
        assertTrue(svc.remove(4, adminId = 999))
        assertEquals(ProviderState.REMOVED, svc.stateOf(4))
    }

    @Test
    fun `비-pending 승인은 실패`() {
        val (svc, _, _) = service()
        svc.requestJoin(5, 100, autoApprove = true) // APPROVED
        assertNull(svc.approve(5, adminId = 999)) // 이미 APPROVED → 승인 불가
        assertFalse(svc.reject(5, adminId = 999)) // PENDING 아님 → 거절 불가
    }

    @Test
    fun `같은 사용자는 여러 서버에 독립적으로 등록 승인 제거된다`() {
        val (svc, tokens, _) = service()
        val token100 = svc.requestJoin(77, 100, autoApprove = true).token!!
        val token200 = svc.requestJoin(77, 200, autoApprove = true).token!!

        assertEquals(ProviderState.APPROVED, svc.stateOf(77, 100))
        assertEquals(ProviderState.APPROVED, svc.stateOf(77, 200))
        assertNull(svc.stateOf(77)) // 멀티 서버면 단일 providerId 조회는 모호하므로 null

        assertTrue(svc.remove(77, 100, adminId = 999))

        assertEquals(ProviderState.REMOVED, svc.stateOf(77, 100))
        assertEquals(ProviderState.APPROVED, svc.stateOf(77, 200))
        assertNull(tokens.verify(token100))
        assertEquals(200L, tokens.verify(token200)!!.guildId)
    }

    @Test
    fun `멤버 이탈 정리는 해당 서버 등록과 토큰만 제거한다`() {
        val (svc, tokens, _) = service()
        val token100 = svc.requestJoin(88, 100, autoApprove = true).token!!
        val token200 = svc.requestJoin(88, 200, autoApprove = true).token!!

        assertTrue(svc.removeMemberFromGuild(88, 100))

        assertNull(svc.stateOf(88, 100))
        assertEquals(ProviderState.APPROVED, svc.stateOf(88, 200))
        assertNull(tokens.verify(token100))
        assertEquals(200L, tokens.verify(token200)!!.guildId)
    }

    @Test
    fun `길드 제거 시 해당 길드 등록과 미사용 토큰 폐기`() {
        val (svc, tokens, _) = service()
        val token100 = svc.requestJoin(10, 100, autoApprove = true).token!!
        svc.requestJoin(11, 100, autoApprove = false)
        val token200 = svc.requestJoin(20, 200, autoApprove = true).token!!

        val removed = svc.removeGuild(100)

        assertEquals(setOf(10L, 11L), removed.toSet())
        assertNull(svc.stateOf(10))
        assertNull(svc.stateOf(11))
        assertEquals(ProviderState.APPROVED, svc.stateOf(20))
        assertNull(tokens.verify(token100))
        assertEquals(20L, tokens.verify(token200)!!.providerId)
    }
}
