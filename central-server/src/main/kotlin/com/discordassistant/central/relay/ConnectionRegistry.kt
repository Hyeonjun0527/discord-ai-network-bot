package com.discordassistant.central.relay

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 연결 레지스트리: providerId → 세션, guildId → 세션 풀(Provider Pool).
 *
 * Provider Pool(ADR 0003)에서 한 길드는 여러 프로바이더 세션을 가질 수 있다. 라우터(K-차수 9/10)는
 * guildId 로 후보 세션 목록을 받아 필터+점수로 1개를 고른다.
 */
@Component
class ConnectionRegistry {
    private val log = LoggerFactory.getLogger(ConnectionRegistry::class.java)

    private val byProvider = ConcurrentHashMap<Long, ProviderSession>()
    private val byGuild = ConcurrentHashMap<Long, MutableSet<ProviderSession>>()

    /** 세션 등록. 같은 providerId 재연결이면 이전 세션을 graceful close 후 교체. */
    fun register(session: ProviderSession) {
        val old = byProvider.put(session.providerId, session)
        if (old != null && old !== session) {
            evict(old, "다른 연결로 교체됨")
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

    /** 세션 해제(보관된 것과 동일 객체일 때만). */
    fun unregister(session: ProviderSession) {
        if (byProvider[session.providerId] === session) {
            byProvider.remove(session.providerId)
        }
        session.guildId?.let { gid ->
            byGuild[gid]?.let { set ->
                set.remove(session)
                if (set.isEmpty()) byGuild.remove(gid)
            }
        }
        log.info("provider {} 세션 해제", session.providerId)
    }

    fun byProvider(providerId: Long): ProviderSession? = byProvider[providerId]

    /** 길드의 프로바이더 풀(스냅샷 복사). */
    fun byGuild(guildId: Long): List<ProviderSession> = byGuild[guildId]?.toList() ?: emptyList()

    fun activeCount(): Int = byProvider.size

    /** 전체 활성 세션 스냅샷(메트릭 API 용). */
    fun snapshotSessions(): List<ProviderSession> = byProvider.values.toList()

    fun snapshot(): Map<String, Any> =
        mapOf(
            "providers" to byProvider.keys.sorted(),
            "guildPools" to byGuild.mapValues { it.value.map { s -> s.providerId }.sorted() },
            "active" to activeCount(),
        )

    /** heartbeat 만료 세션을 닫고 제거한다. 제거 수 반환. */
    fun reapStale(timeoutSeconds: Long): Int {
        val now = System.nanoTime()
        val stale = byProvider.values.filter { it.isStale(timeoutSeconds, now) }
        stale.forEach {
            evict(it, "heartbeat 만료")
            unregister(it)
        }
        if (stale.isNotEmpty()) log.info("좀비 세션 {}개 정리", stale.size)
        return stale.size
    }
}
