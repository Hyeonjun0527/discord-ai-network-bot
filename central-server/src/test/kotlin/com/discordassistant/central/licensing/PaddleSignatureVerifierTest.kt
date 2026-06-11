package com.discordassistant.central.licensing

import com.discordassistant.central.licensing.application.PaddleSignatureVerifier
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Paddle webhook 서명 검증 — 정상/위조/리플레이/비활성. 순수 HMAC(Clock.fixed). */
class PaddleSignatureVerifierTest {
    private val secret = "pdl_ntfset_test_0123456789abcdef"
    private val nowEpoch = 1_900_000_000L
    private val clock = Clock.fixed(Instant.ofEpochSecond(nowEpoch), ZoneOffset.UTC)

    private fun verifier(sec: String = secret) = PaddleSignatureVerifier(sec, 300, clock)

    private fun sign(
        body: String,
        ts: Long = nowEpoch,
        sec: String = secret,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sec.toByteArray(), "HmacSHA256"))
        val h1 = mac.doFinal("$ts:$body".toByteArray()).joinToString("") { "%02x".format(it) }
        return "ts=$ts;h1=$h1"
    }

    @Test
    fun `올바른 서명은 통과`() {
        val body = """{"event_id":"evt_1"}"""
        assertTrue(verifier().verify(body, sign(body)))
    }

    @Test
    fun `body 변조는 거부`() {
        val header = sign("""{"event_id":"evt_1"}""")
        assertFalse(verifier().verify("""{"event_id":"evt_TAMPERED"}""", header))
    }

    @Test
    fun `다른 시크릿 서명은 거부`() {
        val body = "{}"
        assertFalse(verifier().verify(body, sign(body, sec = "wrong-secret")))
    }

    @Test
    fun `리플레이(오래된 타임스탬프)는 거부`() {
        val body = "{}"
        assertFalse(verifier().verify(body, sign(body, ts = nowEpoch - 1000))) // 윈도 300초 초과
    }

    @Test
    fun `시크릿 미설정이면 비활성(항상 거부)`() {
        val v = verifier("")
        assertFalse(v.isEnabled())
        assertFalse(v.verify("{}", sign("{}")))
    }

    @Test
    fun `헤더 없음·형식 오류는 거부`() {
        assertFalse(verifier().verify("{}", null))
        assertFalse(verifier().verify("{}", "garbage"))
        assertFalse(verifier().verify("{}", "ts=abc;h1=xyz"))
    }
}
