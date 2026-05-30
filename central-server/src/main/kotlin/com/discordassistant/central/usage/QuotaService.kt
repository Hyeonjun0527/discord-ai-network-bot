package com.discordassistant.central.usage

import com.discordassistant.central.persistence.UsageLogRepository
import com.discordassistant.central.policy.PolicyService
import com.discordassistant.central.routing.QuotaChecker
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 공정 사용 쿼터 (LAUNCH 차수 11/144). 오늘(UTC 자정 기준) 사용량이 역할 일일 한도를 넘으면 차단.
 * 한도 0 은 무제한.
 */
@Service
class QuotaService(
    private val usage: UsageLogRepository,
    private val policy: PolicyService,
) : QuotaChecker {
    override fun exceededQuota(
        guildId: Long,
        userId: Long,
        roleIds: Set<Long>,
    ): Boolean {
        val limit = policy.dailyLimit(guildId, roleIds)
        if (limit <= 0) return false // 무제한
        val since = Instant.now().truncatedTo(ChronoUnit.DAYS) // UTC 자정 = 일일 리셋
        val usedToday = usage.countByGuildIdAndUserIdAndCreatedAtAfter(guildId, userId, since)
        return usedToday >= limit
    }
}
