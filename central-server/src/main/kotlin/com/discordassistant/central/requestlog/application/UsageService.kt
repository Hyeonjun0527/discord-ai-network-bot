package com.discordassistant.central.requestlog.application

import com.discordassistant.central.ainetwork.application.AiLevelService
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderHealthEntity
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderHealthRepository
import com.discordassistant.central.requestlog.adapter.outbound.persistence.AiRequestEntity
import com.discordassistant.central.requestlog.adapter.outbound.persistence.AiRequestRepository
import com.discordassistant.central.requestlog.adapter.outbound.persistence.ContributionLogEntity
import com.discordassistant.central.requestlog.adapter.outbound.persistence.ContributionLogRepository
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogEntity
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogRepository
import com.discordassistant.central.routing.application.port.UsageRecorder
import com.discordassistant.central.routing.domain.model.AiRequestInput
import com.discordassistant.central.shared.RequestState
import org.slf4j.LoggerFactory
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
    private val aiLevel: AiLevelService,
) : UsageRecorder {
    private val log = LoggerFactory.getLogger(UsageService::class.java)

    @Transactional
    override fun recordSuccess(
        guildId: Long,
        userId: Long,
        providerId: Long,
        requestId: String,
    ) {
        val now = Instant.now()
        usage.save(UsageLogEntity(guildId = guildId, userId = userId, requestId = requestId, createdAt = now))
        contribution.save(ContributionLogEntity(guildId = guildId, providerId = providerId, requestId = requestId, createdAt = now))
        // 게이미피케이션 경험치 적립은 비핵심 — 실패가 답변/usage 기록을 롤백/실패시키지 않도록 best-effort 격리.
        runCatching { aiLevel.awardAskXp(guildId) }
            .onFailure { e -> log.warn("AI 경험치 적립 실패(guildId={}): {}", guildId, e.message) }
    }

    fun recordRequest(
        input: AiRequestInput,
        state: RequestState,
        providerId: Long?,
        failReason: String?,
    ) = recordRequest(input, state, providerId, failReason, requestId = null)

    @Transactional
    override fun recordRequest(
        input: AiRequestInput,
        state: RequestState,
        providerId: Long?,
        failReason: String?,
        requestId: String?,
    ) {
        requests.save(
            AiRequestEntity(
                requestId = requestId?.trim()?.ifBlank { null } ?: UUID.randomUUID().toString().replace("-", ""),
                guildId = input.guildId,
                channelId = input.channelId,
                userId = input.userId,
                providerId = providerId,
                state = state,
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

    fun userDailyCount(
        guildId: Long,
        userId: Long,
    ): Long = usage.countByGuildIdAndUserId(guildId, userId)

    fun providerContributionCount(providerId: Long): Long = contribution.countByProviderId(providerId)

    fun providerContributions(guildId: Long): List<Pair<Long, Long>> =
        contribution.countByGuildIdGrouped(guildId).map { it.providerId to it.contributionCount }

    /** 서버의 provider 별 기여 건수(since 이후) — 관리 화면 '오늘 N건' 용. */
    fun providerContributionsSince(
        guildId: Long,
        since: Instant,
    ): Map<Long, Long> =
        contribution.countByGuildIdSinceGrouped(guildId, since).associate { it.providerId to it.contributionCount }

    fun totalContributions(guildId: Long): Long = providerContributions(guildId).sumOf { it.second }

    fun providerFailures(providerId: Long): Int = health.findByProviderId(providerId)?.failures ?: 0
}
