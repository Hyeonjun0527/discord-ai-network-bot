package com.discordassistant.central.platform.discord.admin

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 확인 게이트(CONFIRM 위험 액션)용 in-memory pending 저장소. 버튼 클릭 시 토큰으로 꺼내 실행한다.
 * TTL([ttlMillis], 기본 5분)이 지난 토큰은 만료로 간주(get 시 제거). [AskCommandHandler.inflightImages]
 * 의 ConcurrentHashMap 패턴을 따른다(과제 안전장치 5).
 */
@Component
class PendingAdminActionStore(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 확인 대기 중인 한 액션(누가 요청했는지 + 어느 길드 + 무엇). */
    data class Pending(
        val plan: AdminActionPlan,
        val requesterUserId: Long,
        val guildId: Long,
        val createdAtMillis: Long,
    )

    private val store = ConcurrentHashMap<String, Pending>()
    private val rng = SecureRandom()

    /** pending 을 저장하고 버튼 componentId 에 실릴 토큰을 돌려준다(128비트 SecureRandom, URL-safe). */
    fun put(
        plan: AdminActionPlan,
        requesterUserId: Long,
        guildId: Long,
    ): String {
        purgeExpired()
        val bytes = ByteArray(16)
        rng.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        store[token] = Pending(plan, requesterUserId, guildId, clock())
        return token
    }

    /**
     * 토큰으로 pending 을 한 번만 꺼낸다(consume — 재실행/리플레이 방지). 없거나 만료면 null.
     */
    fun consume(token: String): Pending? {
        val pending = store.remove(token) ?: return null
        return if (isExpired(pending)) null else pending
    }

    private fun purgeExpired() {
        store.entries.removeIf { isExpired(it.value) }
    }

    private fun isExpired(pending: Pending): Boolean = clock() - pending.createdAtMillis > ttlMillis

    companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
    }
}
