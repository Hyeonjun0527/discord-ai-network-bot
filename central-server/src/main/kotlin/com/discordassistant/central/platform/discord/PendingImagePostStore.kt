package com.discordassistant.central.platform.discord

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 게시 확인 게이트가 켜진 유저의 **완성 이미지를 잠깐 보관**하는 인메모리 임시 저장소(차수: 본인 확인 게이트).
 *
 * /그림 완성 → 본인만 보이는(ephemeral) 미리보기 + 버튼 3개를 띄우는 동안, 채널 게시에 필요한 PNG/캡션/대상 채널을
 * 토큰으로 보관한다. 버튼(게시/버리기/게시하고 안 묻기)을 누르면 토큰으로 꺼내 처리하고 제거한다.
 * IdempotencyGuard 와 동일하게 ConcurrentHashMap + nowNanos 주입(테스트) + 상한 초과 시 sweep 패턴을 따른다.
 */
@Component
class PendingImagePostStore(
    @param:Value("\${central.imagine.post-confirm-ttl-millis:600000}") private val ttlMillis: Long = 600_000,
    @param:Value("\${central.imagine.post-confirm-max:200}") private val maxEntries: Int = 200,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    /** 채널 게시에 필요한 보관 항목. 토큰 발급 시각(nowNanos)으로 TTL 만료를 판정한다. */
    class Pending(
        val pngBytes: ByteArray,
        val caption: String,
        val guildId: Long,
        val channelId: Long,
        val userId: Long,
        val createdNanos: Long,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /** 이미지를 보관하고 토큰을 돌려준다. 상한을 넘으면 가장 오래된 항목부터 비운다(메모리 보호). */
    fun put(
        pngBytes: ByteArray,
        caption: String,
        guildId: Long,
        channelId: Long,
        userId: Long,
    ): String {
        if (pending.size >= maxEntries) sweep()
        // sweep 후에도 가득 차 있으면(전부 미만료) 가장 오래된 항목을 강제로 제거해 상한을 지킨다.
        if (pending.size >= maxEntries) {
            pending.entries
                .minByOrNull { it.value.createdNanos }
                ?.let { pending.remove(it.key) }
        }
        val token = UUID.randomUUID().toString()
        pending[token] = Pending(pngBytes, caption, guildId, channelId, userId, nowNanos())
        return token
    }

    /** 토큰으로 항목을 꺼내고 제거한다(채널 게시용). 소유자 불일치·만료·부재면 null. */
    fun take(
        token: String,
        userId: Long,
    ): Pending? = consume(token, userId)

    /** 토큰 항목을 버린다(채널 게시 없이 폐기). 소유자 본인의 유효 항목을 제거했으면 true. */
    fun discard(
        token: String,
        userId: Long,
    ): Boolean = consume(token, userId) != null

    private fun consume(
        token: String,
        userId: Long,
    ): Pending? {
        val entry = pending[token] ?: return null
        if (entry.userId != userId) return null // 소유자 검증(다른 유저의 토큰으로 게시/폐기 금지)
        pending.remove(token)
        if (nowNanos() - entry.createdNanos > ttlMillis * 1_000_000) return null // 만료된 항목은 제거만 하고 무효
        return entry
    }

    private fun sweep() {
        val now = nowNanos()
        val ttlNanos = ttlMillis * 1_000_000
        pending.entries.removeIf { now - it.value.createdNanos > ttlNanos }
    }
}
