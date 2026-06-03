package com.discordassistant.central.provider

import com.discordassistant.central.relay.OwnerBinding
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 재사용 가능한 durable 프로바이더 토큰 발급/검증(상태 비저장 HMAC). */
interface DurableTokenIssuer {
    /** 인증 성공 후 재연결·재시작에 쓸 durable 토큰을 발급한다. 시크릿 미설정이면 null. */
    fun issueDurable(
        providerId: Long,
        guildId: Long?,
    ): String?
}

/**
 * **상태 비저장(HMAC) durable 프로바이더 토큰** — 일회용 페어링 토큰으로 한 번 인증한 뒤,
 * 에이전트가 저장해 **재연결·재시작에도 재사용**하는 토큰. DB 불필요(서버 재시작에도 유효).
 *
 * 형식: `dv1.<b64url(providerId:guildId:expiryEpochSec)>.<b64url(HMAC-SHA256)>`.
 * HMAC 은 비밀키로 서명하므로 위조 불가. 비교는 상수시간(MessageDigest.isEqual).
 * 시크릿(`central.durable.secret`) 미설정이면 기능 비활성(하위호환 — 기존 일회용 동작 유지).
 *
 * 폐기: 만료(기본 30일) 또는 서버 시크릿 교체(전체 무효화). per-provider 즉시 폐기는 후속(DB).
 */
@Component
class DurableTokenService(
    @param:Value("\${central.durable.secret:}") private val secret: String,
    @param:Value("\${central.durable.ttl-seconds:2592000}") private val ttlSeconds: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val enabled = secret.isNotBlank()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    fun issueDurable(
        providerId: Long,
        guildId: Long?,
    ): String? {
        if (!enabled || guildId == null) return null
        val expiry = clock.instant().epochSecond + ttlSeconds
        val payloadB64 = b64.encodeToString("$providerId:$guildId:$expiry".toByteArray(Charsets.UTF_8))
        val signed = "dv1.$payloadB64"
        return "$signed.${b64.encodeToString(hmac(signed))}"
    }

    /** durable 토큰이면 검증해 OwnerBinding 반환. 아니면(형식 불일치/위조/만료) null. */
    fun verify(token: String): OwnerBinding? {
        if (!enabled) return null
        val parts = token.split(".")
        if (parts.size != 3 || parts[0] != "dv1") return null
        val signed = "dv1.${parts[1]}"
        val expectedSig = hmac(signed)
        val actualSig = runCatching { b64d.decode(parts[2]) }.getOrNull() ?: return null
        if (!java.security.MessageDigest.isEqual(expectedSig, actualSig)) return null // 상수시간 비교
        val payload = runCatching { String(b64d.decode(parts[1]), Charsets.UTF_8) }.getOrNull() ?: return null
        val fields = payload.split(":")
        if (fields.size != 3) return null
        val providerId = fields[0].toLongOrNull() ?: return null
        val guildId = fields[1].toLongOrNull() ?: return null
        val expiry = fields[2].toLongOrNull() ?: return null
        if (clock.instant().epochSecond > expiry) return null // 만료
        return OwnerBinding(providerId, guildId)
    }

    fun isEnabled(): Boolean = enabled

    private fun hmac(data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }
}
