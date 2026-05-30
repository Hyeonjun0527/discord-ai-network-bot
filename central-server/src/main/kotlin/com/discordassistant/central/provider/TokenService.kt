package com.discordassistant.central.provider

import com.discordassistant.central.relay.OwnerBinding
import com.discordassistant.central.relay.TokenVerifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 일회용 페어링/호스트 토큰 발급·검증 (K-차수 4). `TokenVerifier` 를 실제 구현해 스텁을 대체한다.
 *
 * 보안: 평문 토큰은 절대 저장하지 않고 SHA-256 해시만 보관한다. 검증은 토큰을 해시해 맵에서
 * 조회하므로(원시 비밀 바이트 비교 없음) 타이밍 누출이 없다. 토큰은 단발성(1회 검증 후 폐기)이며
 * TTL 후 만료된다.
 */
@Component
class TokenService(
    @param:Value("\${central.token.ttl-seconds:600}") private val ttlSeconds: Long,
) : TokenVerifier {
    private data class Record(
        val providerId: Long,
        val guildId: Long?,
        val expiresAtNanos: Long,
    )

    private val store = ConcurrentHashMap<String, Record>()
    private val random = SecureRandom()

    // 혼동되는 글자(0/O/1/I) 제외 알파벳.
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 평문 토큰을 발급한다(DM 으로 한 번만 보여줌). 해시만 저장. */
    fun issue(
        providerId: Long,
        guildId: Long?,
    ): String {
        val token =
            (0 until 15)
                .map { alphabet[random.nextInt(alphabet.length)] }
                .joinToString("")
                .chunked(5)
                .joinToString("-")
        store[hash(token)] =
            Record(
                providerId,
                guildId,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(ttlSeconds),
            )
        return token
    }

    override fun verify(token: String): OwnerBinding? {
        val h = hash(token)
        val rec = store[h] ?: return null
        if (System.nanoTime() > rec.expiresAtNanos) {
            store.remove(h)
            return null
        }
        store.remove(h) // 단발성
        return OwnerBinding(rec.providerId, rec.guildId)
    }

    /** 폐기(revoke). 발급한 토큰을 무효화한다. 발급 측은 평문을 알 때만 호출 가능. */
    fun revoke(token: String) {
        store.remove(hash(token))
    }

    fun activeTokenCount(): Int = store.size

    private fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
