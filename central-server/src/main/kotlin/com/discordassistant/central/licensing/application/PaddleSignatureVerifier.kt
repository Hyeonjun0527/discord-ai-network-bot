package com.discordassistant.central.licensing.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Paddle webhook 서명 검증(ADR 0005). 헤더 `Paddle-Signature: ts=<unix>;h1=<hex hmac>`.
 * h1 = HMAC-SHA256(secret, "<ts>:<rawBody>"). 상수시간 비교 + 타임스탬프 리플레이 윈도(±5분).
 *
 * 시크릿(`paddle.webhook-secret`) 미설정이면 비활성 → 항상 false(결제 기능 off, fail-closed).
 */
@Component
class PaddleSignatureVerifier(
    @param:Value("\${paddle.webhook-secret:}") private val secret: String,
    @param:Value("\${paddle.replay-window-seconds:300}") private val replayWindowSeconds: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun isEnabled(): Boolean = secret.isNotBlank()

    /** 서명 검증. 시크릿 미설정·형식 오류·불일치·리플레이는 모두 false. */
    fun verify(
        rawBody: String,
        signatureHeader: String?,
    ): Boolean {
        if (secret.isBlank() || signatureHeader.isNullOrBlank()) return false
        val parts =
            signatureHeader
                .split(";")
                .mapNotNull {
                    val kv = it.split("=", limit = 2)
                    if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
                }.toMap()
        val ts = parts["ts"]?.toLongOrNull() ?: return false
        val h1 = parts["h1"] ?: return false
        // 리플레이 방어: 타임스탬프가 허용 윈도를 벗어나면 거부.
        if (abs(clock.instant().epochSecond - ts) > replayWindowSeconds) return false
        val expected = hmacHex("$ts:$rawBody")
        val actual = h1.lowercase()
        // 상수시간 비교(길이 다르면 즉시 false 지만 MessageDigest 가 안전 처리).
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }

    private fun hmacHex(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
