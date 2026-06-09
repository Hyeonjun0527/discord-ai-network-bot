package com.discordassistant.central.quota.application

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.quota.adapter.outbound.persistence.BlocklistEntity
import com.discordassistant.central.quota.adapter.outbound.persistence.BlocklistRepository
import com.discordassistant.central.routing.application.port.BlocklistChecker
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 사용자 차단 목록 (LAUNCH 차수 11, 어뷰즈 방지). **DB 영속 + 인메모리 캐시**.
 *
 * 이전엔 인메모리만이라 재시작 시 차단이 모두 풀렸다(안전 회귀). 이제 `blocklist` 테이블에 저장하고
 * 시작 시 캐시로 로드해, isBlocked 는 O(1)(/ask 핫패스 보존)이면서 재시작에도 차단이 유지된다.
 */
@Service
class BlocklistService(
    private val audit: AuditLog,
    private val repo: BlocklistRepository,
    private val clock: Clock = Clock.systemUTC(),
) : BlocklistChecker {
    private val blocked = ConcurrentHashMap<Long, MutableSet<Long>>()
    private val log = LoggerFactory.getLogger(BlocklistService::class.java)

    @PostConstruct
    fun load() {
        // 전체 차단 목록을 캐시로 적재(차단은 소수라 부담 적음).
        try {
            repo.findAll().forEach { e ->
                blocked.computeIfAbsent(e.guildId) { ConcurrentHashMap.newKeySet() }.add(e.userId)
            }
        } catch (e: DataAccessException) {
            // 적재 실패 = 어뷰즈 차단이 빈 채로 시작될 수 있다(보안 저하) — 조용히 넘기지 않고 크게 남긴다(예외 원칙 3).
            log.error("차단 목록 적재 실패 — 차단이 적용되지 않을 수 있습니다(운영 확인 필요)", e)
        }
    }

    override fun isBlocked(
        guildId: Long,
        userId: Long,
    ): Boolean = blocked[guildId]?.contains(userId) == true

    @Transactional
    fun block(
        guildId: Long,
        userId: Long,
        adminId: Long,
    ) {
        if (repo.findByGuildIdAndUserId(guildId, userId) == null) {
            repo.save(BlocklistEntity(guildId = guildId, userId = userId, blockedBy = adminId, createdAt = Instant.now(clock)))
        }
        blocked.computeIfAbsent(guildId) { ConcurrentHashMap.newKeySet() }.add(userId)
        audit.record("user_block", "admin:$adminId", "guild:$guildId", "user:$userId")
    }

    @Transactional
    fun unblock(
        guildId: Long,
        userId: Long,
        adminId: Long,
    ) {
        repo.deleteByGuildIdAndUserId(guildId, userId)
        blocked[guildId]?.remove(userId)
        audit.record("user_unblock", "admin:$adminId", "guild:$guildId", "user:$userId")
    }

    fun blockedUsers(guildId: Long): Set<Long> = blocked[guildId]?.toSet() ?: emptySet()

    @Transactional
    fun clearGuild(guildId: Long) {
        repo.deleteByGuildId(guildId)
        blocked.remove(guildId)
        audit.record("user_blocklist_cleanup", "system", "guild:$guildId", "removed")
    }
}
