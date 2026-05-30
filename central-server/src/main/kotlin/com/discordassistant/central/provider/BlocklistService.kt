package com.discordassistant.central.provider

import com.discordassistant.central.routing.BlocklistChecker
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 사용자 차단 목록 (LAUNCH 차수 11, 어뷰즈 방지). 길드별 차단 유저 집합(인메모리).
 */
@Service
class BlocklistService(
    private val audit: AuditLog,
) : BlocklistChecker {
    private val blocked = ConcurrentHashMap<Long, MutableSet<Long>>()

    override fun isBlocked(
        guildId: Long,
        userId: Long,
    ): Boolean = blocked[guildId]?.contains(userId) == true

    fun block(
        guildId: Long,
        userId: Long,
        adminId: Long,
    ) {
        blocked.computeIfAbsent(guildId) { ConcurrentHashMap.newKeySet() }.add(userId)
        audit.record("user_block", "admin:$adminId", "guild:$guildId", "user:$userId")
    }

    fun unblock(
        guildId: Long,
        userId: Long,
        adminId: Long,
    ) {
        blocked[guildId]?.remove(userId)
        audit.record("user_unblock", "admin:$adminId", "guild:$guildId", "user:$userId")
    }

    fun blockedUsers(guildId: Long): Set<Long> = blocked[guildId]?.toSet() ?: emptySet()
}
