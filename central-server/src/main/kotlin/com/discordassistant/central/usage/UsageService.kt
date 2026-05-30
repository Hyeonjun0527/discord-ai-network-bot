package com.discordassistant.central.usage

import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.persistence.AiRequestEntity
import com.discordassistant.central.persistence.AiRequestRepository
import com.discordassistant.central.persistence.ContributionLogEntity
import com.discordassistant.central.persistence.ContributionLogRepository
import com.discordassistant.central.persistence.ProviderHealthEntity
import com.discordassistant.central.persistence.ProviderHealthRepository
import com.discordassistant.central.persistence.UsageLogEntity
import com.discordassistant.central.persistence.UsageLogRepository
import com.discordassistant.central.routing.AiRequestInput
import com.discordassistant.central.routing.UsageRecorder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 사용량/기여/요청/헬스 기록 (LAUNCH 차수 11). usage_log(요청자)·contribution_log(provider)·
 * ai_request(상태 추적)·provider_health(실패율).
 */
@Service
class UsageService(
    private val usage: UsageLogRepository,
    private val contribution: ContributionLogRepository,
    private val requests: AiRequestRepository,
    private val health: ProviderHealthRepository,
) : UsageRecorder {

    @Transactional
    override fun recordSuccess(guildId: Long, userId: Long, providerId: Long, requestId: String) {
        val now = Instant.now()
        usage.save(UsageLogEntity(guildId = guildId, userId = userId, requestId = requestId, createdAt = now))
        contribution.save(ContributionLogEntity(providerId = providerId, requestId = requestId, createdAt = now))
    }

    @Transactional
    override fun recordRequest(
        input: AiRequestInput,
        state: RequestState,
        providerId: Long?,
        failReason: String?,
    ) {
        requests.save(
            AiRequestEntity(
                requestId = UUID.randomUUID().toString().replace("-", ""),
                guildId = input.guildId,
                channelId = input.channelId,
                userId = input.userId,
                providerId = providerId,
                state = state.name,
                failReason = failReason?.take(500),
                createdAt = Instant.now(),
            ),
        )
    }

    @Transactional
    override fun recordProviderFailure(providerId: Long) {
        val h = health.findByProviderId(providerId) ?: ProviderHealthEntity(providerId = providerId)
        h.failures += 1
        h.lastFailureAt = Instant.now()
        health.save(h)
    }

    fun userDailyCount(guildId: Long, userId: Long): Long = usage.countByGuildIdAndUserId(guildId, userId)

    fun providerContributionCount(providerId: Long): Long = contribution.countByProviderId(providerId)

    fun providerFailures(providerId: Long): Int = health.findByProviderId(providerId)?.failures ?: 0
}
