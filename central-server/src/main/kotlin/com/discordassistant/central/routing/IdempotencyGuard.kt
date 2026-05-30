package com.discordassistant.central.routing

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 멱등성/중복 요청 방지(차수 16 #243). 짧은 윈도우 안에 동일 (guild,user,prompt) 가 다시 오면
 * 중복으로 보고 막는다(더블클릭·재시도 폭주 방어). 시간원천은 테스트에서 주입 가능.
 */
@Component
class IdempotencyGuard(
    @param:Value("\${central.idempotency.window-millis:10000}") private val windowMillis: Long = 10_000,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val seen = ConcurrentHashMap<String, Long>()

    private fun key(guildId: Long, userId: Long, prompt: String): String =
        "$guildId:$userId:${prompt.hashCode()}"

    /** 처리 시작 가능하면 true(키 기록). 윈도우 내 중복이면 false. */
    fun tryBegin(guildId: Long, userId: Long, prompt: String): Boolean {
        val now = nowNanos()
        val windowNanos = windowMillis * 1_000_000
        val k = key(guildId, userId, prompt)
        // 만료 정리(가벼운 청소).
        if (seen.size > 10_000) seen.entries.removeIf { now - it.value > windowNanos }
        val prev = seen[k]
        if (prev != null && now - prev < windowNanos) return false
        seen[k] = now
        return true
    }
}
