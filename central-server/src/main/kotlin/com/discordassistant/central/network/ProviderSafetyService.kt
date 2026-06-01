package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiNetworkEventEntity
import com.discordassistant.central.persistence.AiNetworkEventRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class ProviderSafetyService(
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val events: AiNetworkEventRepository,
    private val foundation: AiNetworkFoundationService,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun overloadAlerts(guildId: Long): ProviderSafetyDashboard {
        val providers = providerCapabilities.findByGuildId(guildId)
        val alerts =
            providers
                .filter { it.overloadRisk.isOverloadRisk() || it.providerState.equals("OVERLOADED", ignoreCase = true) }
                .map { it.toAlert() }
                .sortedWith(
                    compareByDescending<ProviderOverloadAlert> { it.severityRank }
                        .thenBy { it.providerUserId },
                )
        return ProviderSafetyDashboard(
            guildId = guildId,
            alertCount = alerts.size,
            highRiskCount = alerts.count { it.risk == "high" || it.risk == "critical" },
            safeOnlineProviderCount =
                providers.count {
                    it.providerState.equals("ONLINE", ignoreCase = true) && !it.overloadRisk.isOverloadRisk()
                },
            fanoutSafe = alerts.none { it.risk == "critical" } && alerts.size < providers.size,
            alerts = alerts,
        )
    }

    fun guardFanout(
        guildId: Long,
        requestedCandidates: Int,
    ): ProviderSafetyGuard {
        val dashboard = overloadAlerts(guildId)
        val safeCapacity = dashboard.safeOnlineProviderCount.coerceAtLeast(0)
        val requested = requestedCandidates.coerceAtLeast(1)
        val allowedCandidates = requested.coerceAtMost(safeCapacity.coerceAtLeast(1))
        val blocked = safeCapacity == 0 || dashboard.alerts.any { it.risk == "critical" }
        return ProviderSafetyGuard(
            guildId = guildId,
            requestedCandidates = requested,
            allowed = !blocked,
            maxSafeCandidates = if (blocked) 0 else allowedCandidates,
            reason =
                when {
                    safeCapacity == 0 -> "사용 가능한 안전 Provider가 없습니다. Provider 보호를 위해 요청을 줄였어요."
                    dashboard.alerts.any { it.risk == "critical" } -> "심각한 과부하 알림이 있어 고급 요청을 잠시 멈췄어요."
                    requested > allowedCandidates -> "Provider 보호를 위해 후보 수를 $allowedCandidates 개로 제한해야 해요."
                    else -> "요청을 처리할 수 있는 안전 용량이 있습니다."
                },
        )
    }

    @Transactional
    fun markOverload(
        guildId: Long,
        providerUserId: Long,
        overloadRisk: String,
        reason: String?,
    ): ProviderSafetyMutationResult {
        val now = Instant.now(clock)
        val provider =
            providerCapabilities.findByGuildIdAndProviderUserId(guildId, providerUserId)
                ?: ProviderCapabilityProfileEntity(guildId = guildId, providerUserId = providerUserId)
        provider.overloadRisk = overloadRisk.normalizedRisk()
        provider.providerState = if (provider.overloadRisk.isOverloadRisk()) "OVERLOADED" else "ONLINE"
        provider.updatedAt = now
        provider.lastSeenAt = now
        val saved = providerCapabilities.save(provider)
        val event =
            events.save(
                AiNetworkEventEntity(
                    guildId = guildId,
                    eventType = "provider_overload",
                    providerUserId = providerUserId,
                    title = provider.overloadTitle(),
                    summary = reason?.trim()?.take(300)?.ifBlank { null } ?: provider.overloadSummary(),
                    metadata = "risk=${provider.overloadRisk};state=${provider.providerState}",
                    createdAt = now,
                ),
            )
        val overview = foundation.refreshOverview(guildId)
        return ProviderSafetyMutationResult(saved.id, event.id, overview)
    }

    private fun ProviderCapabilityProfileEntity.toAlert(): ProviderOverloadAlert =
        ProviderOverloadAlert(
            providerUserId = providerUserId,
            providerState = providerState,
            risk = overloadRisk.normalizedRisk(),
            maxBurden = maxBurden,
            maxConcurrency = maxConcurrency,
            dailyLimit = dailyLimit,
            lastSeenAt = lastSeenAt,
            severityRank = overloadRisk.normalizedRisk().severityRank(),
            message = overloadSummary(),
            recommendedAction = recommendedAction(overloadRisk.normalizedRisk()),
        )

    private fun ProviderCapabilityProfileEntity.overloadTitle(): String =
        if (overloadRisk.isOverloadRisk()) {
            "Provider 과부하 보호가 켜졌어요"
        } else {
            "Provider 과부하 보호가 해제됐어요"
        }

    private fun ProviderCapabilityProfileEntity.overloadSummary(): String =
        if (overloadRisk.isOverloadRisk()) {
            "Provider #$providerUserId 보호를 위해 고급/다중 응답 요청에서 제외합니다."
        } else {
            "Provider #$providerUserId 를 다시 일반 라우팅 후보로 사용할 수 있습니다."
        }

    private fun recommendedAction(risk: String): String =
        when (risk) {
            "critical" -> "즉시 수신정지하고, 다중응답/깊은답변을 끈 뒤 상태가 안정되면 재개하세요."
            "high" -> "다중응답 후보에서 제외하고, 요청량을 줄인 뒤 Provider에게 휴식을 권장하세요."
            else -> "일반 라우팅에 포함할 수 있지만, 실패율과 응답 지연을 계속 관찰하세요."
        }

    private fun String.normalizedRisk(): String =
        trim().lowercase().ifBlank { "normal" }.let {
            when (it) {
                "critical", "high", "normal", "low" -> it
                "overload", "overloaded" -> "high"
                else -> "normal"
            }
        }

    private fun String.isOverloadRisk(): Boolean = normalizedRisk() == "high" || normalizedRisk() == "critical"

    private fun String.severityRank(): Int =
        when (this) {
            "critical" -> 3
            "high" -> 2
            "low" -> 1
            else -> 0
        }
}

data class ProviderSafetyDashboard(
    val guildId: Long,
    val alertCount: Int,
    val highRiskCount: Int,
    val safeOnlineProviderCount: Int,
    val fanoutSafe: Boolean,
    val alerts: List<ProviderOverloadAlert>,
)

data class ProviderOverloadAlert(
    val providerUserId: Long,
    val providerState: String,
    val risk: String,
    val maxBurden: String,
    val maxConcurrency: Int,
    val dailyLimit: Int,
    val lastSeenAt: Instant?,
    val severityRank: Int,
    val message: String,
    val recommendedAction: String,
)

data class ProviderSafetyGuard(
    val guildId: Long,
    val requestedCandidates: Int,
    val allowed: Boolean,
    val maxSafeCandidates: Int,
    val reason: String,
)

data class ProviderSafetyMutationResult(
    val providerCapabilityId: Long,
    val eventId: Long,
    val overview: NetworkOverviewProjectionEntity,
)
