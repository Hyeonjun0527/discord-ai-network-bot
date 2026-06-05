package com.discordassistant.central.provider.application

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
    private val durable: DurableTokenService = DurableTokenService("", 0),
) : TokenVerifier,
    DurableTokenIssuer {
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
        pruneExpired() // 만료된 미사용 토큰을 발급 시점에 정리(무한 증가 방지).
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

    /** 만료된(미사용) 페어링 토큰 제거. 검증 시에만 정리되던 것을 발급 시점에도 정리한다. */
    private fun pruneExpired() {
        val now = System.nanoTime()
        store.entries.removeIf { now > it.value.expiresAtNanos }
    }

    override fun verify(token: String): OwnerBinding? {
        // durable(dv1.) 토큰은 재사용 가능 — 소모하지 않고 HMAC 검증.
        if (token.startsWith("dv1.")) return durable.verify(token)
        val h = hash(token)
        val rec = store[h] ?: return null
        if (System.nanoTime() > rec.expiresAtNanos) {
            store.remove(h)
            return null
        }
        store.remove(h) // 일회용 페어링 토큰은 단발성
        return OwnerBinding(rec.providerId, rec.guildId)
    }

    override fun issueDurable(
        providerId: Long,
        guildId: Long?,
    ): String? = durable.issueDurable(providerId, guildId)

    /** (provider,guild) 의 durable(재사용) 토큰을 즉시 폐기한다(제거/거절 시). */
    fun revokeDurable(
        providerId: Long,
        guildId: Long,
    ) = durable.revoke(providerId, guildId)

    /** 폐기(revoke). 발급한 토큰을 무효화한다. 발급 측은 평문을 알 때만 호출 가능. */
    fun revoke(token: String) {
        store.remove(hash(token))
    }

    /** 길드가 제거될 때 아직 사용되지 않은 해당 길드 토큰을 모두 폐기한다. */
    fun revokeGuild(guildId: Long): Int {
        val keys = store.entries.filter { it.value.guildId == guildId }.map { it.key }
        keys.forEach { store.remove(it) }
        return keys.size
    }

    /** 특정 provider/guild 조합의 미사용 토큰을 폐기한다. */
    fun revokeProviderGuild(
        providerId: Long,
        guildId: Long,
    ): Int {
        val keys =
            store.entries
                .filter { it.value.providerId == providerId && it.value.guildId == guildId }
                .map { it.key }
        keys.forEach { store.remove(it) }
        return keys.size
    }

    fun activeTokenCount(): Int = store.size

    private fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
