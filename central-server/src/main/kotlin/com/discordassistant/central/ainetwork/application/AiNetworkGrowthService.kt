package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.shared.ModelBurden
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class AiNetworkGrowthService(
    private val foundation: AiNetworkFoundationService,
    private val events: AiNetworkEventRepository,
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val clock: Clock = Clock.systemUTC(),
    // 순수/읽기 협력자(god-class 분해). 기본값으로 직접 구성해 수동 생성(테스트) 호환을 유지하고,
    // Spring 컨텍스트에서는 동일 @Component 빈이 주입된다. write/@Transactional 라이프사이클은 파사드 잔존.
    private val planner: AiNetworkGrowthPlanner = AiNetworkGrowthPlanner(),
) {
    @Transactional
    fun syncProviderCapabilitiesFromHello(
        guildId: Long,
        providerUserId: Long,
        modelNames: List<String>,
        maxConcurrency: Int,
        remainingDailyRequests: Int,
    ): ProviderCapabilitySyncResult {
        val normalizedModels = planner.normalizeList(modelNames)
        val capabilityTags = planner.inferCapabilityTags(normalizedModels)
        val maxBurden = planner.inferMaxBurden(normalizedModels)
        val dailyLimit = remainingDailyRequests.coerceAtLeast(0)
        val existing = providerCapabilities.findByGuildIdAndProviderUserId(guildId, providerUserId)
        val shouldRecordGrowth =
            existing == null ||
                existing.providerState != ProviderAvailability.ONLINE ||
                planner.normalizeCsv(existing.modelNames) != normalizedModels ||
                existing.maxConcurrency != maxConcurrency.coerceAtLeast(1) ||
                existing.dailyLimit != dailyLimit
        val before = foundation.refreshOverview(guildId)
        val capability =
            foundation.upsertProviderCapability(
                guildId = guildId,
                providerUserId = providerUserId,
                providerState = ProviderAvailability.ONLINE,
                modelNames = normalizedModels,
                capabilityTags = capabilityTags,
                maxBurden = maxBurden,
                maxConcurrency = maxConcurrency,
                dailyLimit = dailyLimit,
                overloadRisk = existing?.overloadRisk ?: OverloadRisk.NORMAL,
            )
        val overview = foundation.refreshOverview(guildId)
        val event =
            if (shouldRecordGrowth) {
                recordProviderGrowthEvent(
                    guildId = guildId,
                    providerUserId = providerUserId,
                    levelBefore = before.networkLevel,
                    levelAfter = overview.networkLevel,
                    modelNames = normalizedModels,
                    capabilityTags = capabilityTags,
                    maxBurden = maxBurden.name,
                    maxConcurrency = maxConcurrency,
                    dailyLimit = dailyLimit,
                )
            } else {
                null
            }
        maybeRecordLevelUp(guildId, overview)
        return ProviderCapabilitySyncResult(
            providerCapabilityId = capability.id,
            eventId = event?.id,
            networkLevel = overview.networkLevel,
            changed = shouldRecordGrowth,
        )
    }

    @Transactional
    fun markProviderOffline(
        guildId: Long,
        providerUserId: Long,
    ) {
        val existing = providerCapabilities.findByGuildIdAndProviderUserId(guildId, providerUserId) ?: return
        foundation.upsertProviderCapability(
            guildId = guildId,
            providerUserId = providerUserId,
            providerState = ProviderAvailability.OFFLINE,
            modelNames = planner.normalizeCsv(existing.modelNames),
            capabilityTags = planner.normalizeCsv(existing.capabilityTags),
            maxBurden = existing.maxBurden,
            maxConcurrency = existing.maxConcurrency,
            dailyLimit = existing.dailyLimit,
            overloadRisk = existing.overloadRisk,
        )
        foundation.refreshOverview(guildId)
    }

    @Transactional
    fun recordProviderJoined(
        guildId: Long,
        providerUserId: Long,
        modelNames: List<String>,
        capabilityTags: List<String>,
        maxBurden: String,
        maxConcurrency: Int,
        dailyLimit: Int,
    ): ProviderGrowthResult {
        val before = foundation.refreshOverview(guildId)
        val capability =
            foundation.upsertProviderCapability(
                guildId = guildId,
                providerUserId = providerUserId,
                providerState = ProviderAvailability.ONLINE,
                modelNames = modelNames,
                capabilityTags = capabilityTags,
                maxBurden = ModelBurden.fromName(maxBurden) ?: ModelBurden.LIGHT,
                maxConcurrency = maxConcurrency,
                dailyLimit = dailyLimit,
                overloadRisk = OverloadRisk.NORMAL,
            )
        val overview = foundation.refreshOverview(guildId)
        val event =
            recordProviderGrowthEvent(
                guildId = guildId,
                providerUserId = providerUserId,
                levelBefore = before.networkLevel,
                levelAfter = overview.networkLevel,
                modelNames = modelNames,
                capabilityTags = capabilityTags,
                maxBurden = maxBurden,
                maxConcurrency = maxConcurrency,
                dailyLimit = dailyLimit,
            )
        maybeRecordLevelUp(guildId, overview)
        return ProviderGrowthResult(capability.id, event.id, overview.networkLevel)
    }

    @Transactional
    fun maybeRecordLevelUp(
        guildId: Long,
        overview: NetworkOverviewProjectionEntity = foundation.refreshOverview(guildId),
    ): AiNetworkEventEntity? {
        val levelEvents = events.findByGuildIdAndEventType(guildId, "network_level")
        val lastLevel =
            levelEvents
                .mapNotNull { it.metadata?.substringAfter("level=", "")?.toIntOrNull() }
                .maxOrNull() ?: 0
        if (overview.networkLevel <= lastLevel) return null
        return events.save(
            AiNetworkEventEntity(
                guildId = guildId,
                eventType = "network_level",
                title = "AI 네트워크 레벨 ${overview.networkLevel} 달성",
                summary = planner.levelDescription(overview.networkLevel),
                metadata = "level=${overview.networkLevel}",
                createdAt = Instant.now(clock),
            ),
        )
    }

    @Transactional
    fun levelStatus(guildId: Long): AiNetworkLevelStatus {
        val overview = foundation.refreshOverview(guildId)
        val milestones = planner.levelMilestones(overview)
        return AiNetworkLevelStatus(
            guildId = guildId,
            currentLevel = overview.networkLevel,
            currentTitle = planner.levelTitle(overview.networkLevel),
            currentDescription = planner.levelDescription(overview.networkLevel),
            nextMilestone = milestones.firstOrNull { !it.achieved },
            milestones = milestones,
        )
    }

    @Transactional
    fun growthPlan(guildId: Long): AiNetworkGrowthPlan {
        val overview = foundation.refreshOverview(guildId)
        return growthPlanFromOverview(guildId, overview)
    }

    fun growthPlanFromOverview(
        guildId: Long,
        overview: NetworkOverviewProjectionEntity,
    ): AiNetworkGrowthPlan = planner.growthPlanFromOverview(guildId, overview)

    fun timeline(guildId: Long): List<AiNetworkEventEntity> = events.findTop20ByGuildIdOrderByCreatedAtDesc(guildId)

    fun timelineCards(guildId: Long): List<NetworkGrowthEventCard> = timeline(guildId).map { planner.timelineCard(it) }

    private fun recordProviderGrowthEvent(
        guildId: Long,
        providerUserId: Long,
        levelBefore: Int,
        levelAfter: Int,
        modelNames: List<String>,
        capabilityTags: List<String>,
        maxBurden: String,
        maxConcurrency: Int,
        dailyLimit: Int,
    ): AiNetworkEventEntity {
        val title = "Provider가 AI 네트워크에 참여했어요"
        val summary =
            "이 서버는 ${modelNames.joinToString(",").ifBlank { "로컬 AI" }} 모델과 " +
                "${capabilityTags.joinToString(",").ifBlank { "일반" }} 능력을 사용할 수 있게 됐어요."
        val impact =
            planner.providerImpact(
                levelBefore = levelBefore,
                levelAfter = levelAfter,
                modelNames = modelNames,
                capabilityTags = capabilityTags,
                maxConcurrency = maxConcurrency,
                dailyLimit = dailyLimit,
            )
        return events.save(
            AiNetworkEventEntity(
                guildId = guildId,
                eventType = "provider_joined",
                providerUserId = providerUserId,
                title = title,
                summary = summary,
                metadata =
                    listOf(
                        "models=${modelNames.joinToString(",")}",
                        "tags=${capabilityTags.joinToString(",")}",
                        "maxBurden=$maxBurden",
                        "maxConcurrency=$maxConcurrency",
                        "dailyLimit=$dailyLimit",
                        "levelBefore=$levelBefore",
                        "levelAfter=$levelAfter",
                        "impact=${impact.joinToString("|")}",
                    ).joinToString(";"),
                createdAt = Instant.now(clock),
            ),
        )
    }
}
