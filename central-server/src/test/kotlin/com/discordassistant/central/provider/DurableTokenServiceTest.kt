package com.discordassistant.central.provider

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
}
