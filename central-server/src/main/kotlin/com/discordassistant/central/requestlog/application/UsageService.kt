package com.discordassistant.central.requestlog.application

import com.discordassistant.central.ainetwork.application.NiaAffinityService
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
import com.discordassistant.central.shared.ModelBurden
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
    private val niaAffinity: NiaAffinityService,
) : UsageRecorder {
    private val log = LoggerFactory.getLogger(UsageService::class.java)

    @Transactional
    override fun recordSuccess(
        guildId: Long,
        userId: Long,
        providerId: Long,
        requestId: String,
    ) {
        // requestId 멱등: 재전송된 완료 콜백이 usage_log/contribution_log 를 중복 insert 해 기여 건수를
        // 부풀리지 않도록, 같은 requestId 가 이미 기록됐으면 no-op(호감도 재적립도 건너뜀).
        if (usage.findByRequestId(requestId) != null) return
        val now = Instant.now()
        usage.save(UsageLogEntity(guildId = guildId, userId = userId, requestId = requestId, createdAt = now))
        contribution.save(ContributionLogEntity(guildId = guildId, providerId = providerId, requestId = requestId, createdAt = now))
        // 호감도 적립은 비핵심 UX라 답변/usage/contribution 기록과 실패 격리를 유지한다.
        runCatching { niaAffinity.awardInteraction(guildId, userId) }
            .onFailure { e -> log.warn("니아 호감도 적립 실패(userId={}): {}", userId, e.message) }
    }

    fun recordRequest(
        input: AiRequestInput,
        state: RequestState,
        providerId: Long?,
        failReason: String?,
    ) = recordRequest(input, state, providerId, failReason, requestId = null, requiredBurden = null)

    @Transactional
    override fun recordRequest(
        input: AiRequestInput,
        state: RequestState,
        providerId: Long?,
        failReason: String?,
        requestId: String?,
        requiredBurden: ModelBurden?,
    ) {
        // requestId 멱등: 재전송된 원장 기록이 ai_request 를 중복 insert 하지 않도록, 명시된 requestId 가
        // 이미 있으면 no-op. requestId 미지정(내부 신규 UUID)은 항상 새 행이라 중복 위험 없음.
        val effectiveRequestId = requestId?.trim()?.ifBlank { null }
        if (effectiveRequestId != null && requests.findByRequestId(effectiveRequestId) != null) return
        requests.save(
            AiRequestEntity(
                requestId = effectiveRequestId ?: UUID.randomUUID().toString().replace("-", ""),
                guildId = input.guildId,
                channelId = input.channelId,
                userId = input.userId,
                providerId = providerId,
                // 부하 가중 기여 분석(AnalyticsService.providerComputeScore)이 죽지 않도록 실효 부담을 저장한다.
                // 미상(거절·dedup 등)이면 기본 LIGHT.
                requiredBurden = (requiredBurden ?: ModelBurden.LIGHT).name,
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
    ): Map<Long, Long> = contribution.countByGuildIdSinceGrouped(guildId, since).associate { it.providerId to it.contributionCount }

    fun totalContributions(guildId: Long): Long = providerContributions(guildId).sumOf { it.second }

    fun providerFailures(providerId: Long): Int = health.findByProviderId(providerId)?.failures ?: 0
}
