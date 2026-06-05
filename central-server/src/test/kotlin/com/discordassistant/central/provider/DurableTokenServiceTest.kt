package com.discordassistant.central.provider

import com.discordassistant.central.provider.application.DurableTokenRevocations
import com.discordassistant.central.provider.application.DurableTokenService
import com.discordassistant.central.provider.application.TokenService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DurableTokenServiceTest {
    private val secret = "test-secret-0123456789abcdef"

    private fun svc(
        sec: String = secret,
        ttl: Long = 1000,
        nowEpoch: Long = 1_000_000,
    ) = DurableTokenService(sec, ttl, Clock.fixed(Instant.ofEpochSecond(nowEpoch), ZoneOffset.UTC))

    @Test
    fun `발급-검증 round-trip → OwnerBinding 복원`() {
        val s = svc()
        val t = s.issueDurable(42, 100)
        assertNotNull(t)
        assertTrue(t!!.startsWith("dv1."))
        val b = s.verify(t)
        assertNotNull(b)
        assertEquals(42L, b!!.providerId)
        assertEquals(100L, b.guildId)
    }

    @Test
    fun `변조된 payload·서명은 거부(위조 방지)`() {
        val s = svc()
        val t = s.issueDurable(42, 100)!!
        val parts = t.split(".")
        // 서명 변조
        assertNull(s.verify("${parts[0]}.${parts[1]}.${parts[2].dropLast(2)}AA"))
        // payload 변조(서명 불일치)
        val forgedPayload =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("999:100:9999999999".toByteArray())
        assertNull(s.verify("dv1.$forgedPayload.${parts[2]}"))
        // 형식 오류
        assertNull(s.verify("not-a-token"))
        assertNull(s.verify("dv1.only-two"))
    }

    @Test
    fun `다른 시크릿으로 만든 토큰은 거부`() {
        val issuer = svc(sec = "secret-A")
        val verifier = svc(sec = "secret-B")
        val t = issuer.issueDurable(1, 1)!!
        assertNull(verifier.verify(t))
    }

    @Test
    fun `만료된 토큰은 거부`() {
        val issuer = svc(ttl = 10, nowEpoch = 1000)
        val t = issuer.issueDurable(1, 1)!!
        assertNotNull(svc(ttl = 10, nowEpoch = 1005).verify(t)) // TTL 내
        assertNull(svc(ttl = 10, nowEpoch = 1020).verify(t)) // TTL 초과
    }

    @Test
    fun `시크릿 미설정이면 비활성(발급·검증 모두 null)`() {
        val s = svc(sec = "")
        assertFalse(s.isEnabled())
        assertNull(s.issueDurable(1, 1))
        assertNull(s.verify("dv1.x.y"))
    }

    @Test
    fun `guildId null 이면 발급 안 함`() {
        assertNull(svc().issueDurable(1, null))
    }

    @Test
    fun `TokenService 가 dv1 토큰을 durable 로 위임`() {
        val durable = svc()
        val ts = TokenService(ttlSeconds = 600, durable = durable)
        val t = ts.issueDurable(7, 70)!!
        val b = ts.verify(t) // dv1. → durable 위임, 소모 안 함
        assertEquals(7L, b!!.providerId)
        // 재검증도 가능(재사용)
        assertNotNull(ts.verify(t))
    }

    /** 테스트용 인메모리 폐기 저장소. */
    private class FakeRevocations : DurableTokenRevocations {
        val map = HashMap<Pair<Long, Long>, Long>()

        override fun revokedAtEpoch(
            providerId: Long,
            guildId: Long,
        ): Long? = map[providerId to guildId]

        override fun revoke(
            providerId: Long,
            guildId: Long,
            atEpochSec: Long,
        ) {
            map[providerId to guildId] = maxOf(map[providerId to guildId] ?: Long.MIN_VALUE, atEpochSec)
        }
    }

    private fun svcWith(
        rev: DurableTokenRevocations,
        ttl: Long = 1000,
        nowEpoch: Long = 1_000_000,
    ) = DurableTokenService(secret, ttl, Clock.fixed(Instant.ofEpochSecond(nowEpoch), ZoneOffset.UTC), rev)

    @Test
    fun `폐기하면 그 이전 발급 토큰은 거부, 재발급분은 통과`() {
        val rev = FakeRevocations()
        // t=1000 에 발급
        val issued = svcWith(rev, nowEpoch = 1_000).issueDurable(42, 100)!!
        // 폐기 전에는 통과
        assertNotNull(svcWith(rev, nowEpoch = 1_010).verify(issued))
        // t=1005 에 폐기 → issuedAt(1000) <= revokedAt(1005) → 거부
        svcWith(rev, nowEpoch = 1_005).revoke(42, 100)
        assertNull(svcWith(rev, nowEpoch = 1_010).verify(issued))
        // 재페어링: t=1010 에 새로 발급된 토큰은 revokedAt(1005) 보다 늦어 통과
        val reissued = svcWith(rev, nowEpoch = 1_010).issueDurable(42, 100)!!
        assertNotNull(svcWith(rev, nowEpoch = 1_011).verify(reissued))
    }

    @Test
    fun `폐기는 해당 provider-guild 에만 영향`() {
        val rev = FakeRevocations()
        val tA = svcWith(rev, nowEpoch = 1_000).issueDurable(1, 10)!!
        val tB = svcWith(rev, nowEpoch = 1_000).issueDurable(2, 20)!!
        svcWith(rev, nowEpoch = 1_005).revoke(1, 10)
        assertNull(svcWith(rev, nowEpoch = 1_010).verify(tA)) // 폐기됨
        assertNotNull(svcWith(rev, nowEpoch = 1_010).verify(tB)) // 무관
    }
}
