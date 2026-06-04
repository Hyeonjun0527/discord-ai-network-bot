package com.discordassistant.central.relay

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private data class SessionKey(
    val guildId: Long?,
    val providerId: Long,
)

/**
 * 연결 레지스트리: (guildId, providerId) → 세션, guildId → 세션 풀(Provider Pool).
 *
 * 같은 사용자가 여러 서버에 프로바이더로 등록해도 서버별 세션은 서로 교체하지 않는다. 같은 서버/같은 provider 의
 * 재연결만 이전 세션을 graceful close 후 교체한다.
 */
@Component
class ConnectionRegistry {
    private val log = LoggerFactory.getLogger(ConnectionRegistry::class.java)

    private val byProviderGuild = ConcurrentHashMap<SessionKey, ProviderSession>()
    private val byGuild = ConcurrentHashMap<Long, MutableSet<ProviderSession>>()

    /** 세션 등록. 같은 providerId+guildId 재연결이면 이전 세션을 graceful close 후 교체. */
    fun register(session: ProviderSession) {
        val key = SessionKey(session.guildId, session.providerId)
        val old = byProviderGuild.put(key, session)
        if (old != null && old !== session) {
            evict(old, "다른 연결로 교체됨")
            removeFromGuildPool(old)
        }
        session.guildId?.let { gid ->
            byGuild.computeIfAbsent(gid) { ConcurrentHashMap.newKeySet() }.add(session)
        }
        log.info("provider {} 세션 등록(guild={})", session.providerId, session.guildId)
    }

    private fun evict(
        session: ProviderSession,
        reason: String,
    ) {
        session.closeAndFailPending(reason)
        try {
            session.connection.close(reason)
        } catch (e: Exception) {
            log.debug("이전 연결 close 실패(무시): {}", e.message)
        }
    }

    private fun removeFromGuildPool(session: ProviderSession) {
        session.guildId?.let { gid ->
            byGuild[gid]?.let { set ->
                set.remove(session)
                if (set.isEmpty()) byGuild.remove(gid)
            }
        }
    }

    /** 세션 해제(보관된 것과 동일 객체일 때만). */
    fun unregister(session: ProviderSession) {
        val key = SessionKey(session.guildId, session.providerId)
        if (byProviderGuild[key] === session) {
            byProviderGuild.remove(key)
        }
        removeFromGuildPool(session)
        log.info("provider {} 세션 해제(guild={})", session.providerId, session.guildId)
    }

    fun byProvider(
        guildId: Long,
        providerId: Long,
    ): ProviderSession? = byProviderGuild[SessionKey(guildId, providerId)]

    /** 기존 호출 보호용: 한 provider 의 활성 세션이 하나일 때만 반환한다. */
    fun byProvider(providerId: Long): ProviderSession? {
        val matches = byProviderGuild.values.filter { it.providerId == providerId }
        return matches.singleOrNull()
    }

    /**
     * 이 Discord 사용자가 **현재 에이전트로 연결돼 있는가**(= ‘연동됨’). 어느 길드/DM 풀이든 활성 세션이 하나라도
     * 있으면 true. `/provider참여` 가 연동된 사용자에게 가이드 대신 자동 참여를 안내할지 판정한다.
     */
    fun isProviderLinked(providerId: Long): Boolean = byProviderGuild.values.any { it.providerId == providerId }

    /** 이 provider 가 현재 연결돼 있는 길드 집합(에이전트 동기화에서 ‘이미 연결된 길드’ 제외용). */
    fun providerGuilds(providerId: Long): Set<Long> =
        byProviderGuild.values
            .filter { it.providerId == providerId }
            .mapNotNull { it.guildId }
            .toSet()

    /** 길드의 프로바이더 풀(스냅샷 복사). */
    fun byGuild(guildId: Long): List<ProviderSession> = byGuild[guildId]?.toList() ?: emptyList()

    /** 봇이 길드에서 제거된 경우 해당 길드에 묶인 프로바이더 세션을 모두 종료하고 풀에서 제거한다. */
    fun closeGuild(
        guildId: Long,
        reason: String,
    ): Int {
        val sessions = byGuild(guildId)
        sessions.forEach {
            evict(it, reason)
            unregister(it)
        }
        return sessions.size
    }

    /** 서버 멤버가 나간 경우 그 서버의 해당 provider 세션만 종료한다. */
    fun closeProviderInGuild(
        guildId: Long,
        providerId: Long,
        reason: String,
    ): Boolean {
        val session = byProvider(guildId, providerId) ?: return false
        evict(session, reason)
        unregister(session)
        return true
    }

    fun activeCount(): Int = byProviderGuild.size

    /** 전체 활성 세션 스냅샷(메트릭 API 용). */
    fun snapshotSessions(): List<ProviderSession> = byProviderGuild.values.toList()

    fun snapshot(): Map<String, Any> =
        mapOf(
            "providers" to byProviderGuild.values.map { it.providerId }.sorted(),
            "guildPools" to byGuild.mapValues { it.value.map { s -> s.providerId }.sorted() },
            "active" to activeCount(),
        )

    /** heartbeat 만료 세션을 닫고 제거한다. 제거 수 반환. */
    fun reapStale(timeoutSeconds: Long): Int {
        val now = System.nanoTime()
        val stale = byProviderGuild.values.filter { it.isStale(timeoutSeconds, now) }
        stale.forEach {
            evict(it, "heartbeat 만료")
            unregister(it)
        }
        if (stale.isNotEmpty()) log.info("좀비 세션 {}개 정리", stale.size)
        return stale.size
    }
}
