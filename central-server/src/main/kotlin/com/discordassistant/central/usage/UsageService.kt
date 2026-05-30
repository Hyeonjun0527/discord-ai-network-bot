package com.discordassistant.central.usage

import com.discordassistant.central.persistence.ContributionLogEntity
import com.discordassistant.central.persistence.ContributionLogRepository
import com.discordassistant.central.persistence.UsageLogEntity
import com.discordassistant.central.persistence.UsageLogRepository
import com.discordassistant.central.routing.UsageRecorder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 사용량/기여 기록 (K-차수 11/14). 요청 완료 시 usage_log(요청자)·contribution_log(provider) 기록.
 */
@Service
class UsageService(
    private val usage: UsageLogRepository,
    private val contribution: ContributionLogRepository,
) : UsageRecorder {

    @Transactional
    override fun recordSuccess(guildId: Long, userId: Long, providerId: Long, requestId: String) {
        val now = Instant.now()
        usage.save(UsageLogEntity(guildId = guildId, userId = userId, requestId = requestId, createdAt = now))
        contribution.save(ContributionLogEntity(providerId = providerId, requestId = requestId, createdAt = now))
    }

    fun userDailyCount(guildId: Long, userId: Long): Long = usage.countByGuildIdAndUserId(guildId, userId)

    fun providerContributionCount(providerId: Long): Long = contribution.countByProviderId(providerId)
}
