package com.discordassistant.central.dashboard

import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity
import com.discordassistant.central.multiresponse.adapter.inbound.web.MultiResponseOperationsDashboardResponse
import com.discordassistant.central.multiresponse.application.MultiResponseOperationsSummary
import com.discordassistant.central.multiresponse.application.MultiResponseService
import com.discordassistant.central.network.AiNetworkDashboardQueryService
import com.discordassistant.central.network.AiNetworkFeatureGate
import com.discordassistant.central.network.AiNetworkFoundationService
import com.discordassistant.central.network.AiNetworkGrowthPlan
import com.discordassistant.central.network.AiNetworkGrowthService
import com.discordassistant.central.network.AiNetworkReadinessService
import com.discordassistant.central.network.AiQualityFeedbackService
import com.discordassistant.central.network.ModelQualitySummary
import com.discordassistant.central.network.NetworkGrowthEventCard
import com.discordassistant.central.network.ProviderSafetyDashboard
import com.discordassistant.central.network.ProviderSafetyExecutionPlan
import com.discordassistant.central.network.ProviderSafetyService
import com.discordassistant.central.network.QualityReviewSummary
import com.discordassistant.central.network.QualitySummary
import com.discordassistant.central.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.requestlog.application.AnalyticsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** AI Network 대시보드 read API. 프롬프트/응답 본문 없이 네트워크 메타데이터만 노출한다. */
@RestController
@RequestMapping("/api/ai-network")
class AiNetworkDashboardController(
    private val foundation: AiNetworkFoundationService,
    private val aiLevel: com.discordassistant.central.network.AiLevelService,
    private val growth: AiNetworkGrowthService,
    private val qualityFeedback: AiQualityFeedbackService,
    private val providerSafety: ProviderSafetyService,
    private val query: AiNetworkDashboardQueryService,
    private val multiResponse: MultiResponseService,
    private val analytics: AnalyticsService,
    private val readinessService: AiNetworkReadinessService = AiNetworkReadinessService(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    @GetMapping("/{guildId}/dashboard")
    fun dashboard(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
        @RequestParam(defaultValue = "balanced") responseMode: String = "balanced",
        @RequestParam(defaultValue = "1") requestedCandidates: Int = 1,
        @RequestParam(defaultValue = "false") refreshOverview: Boolean = false,
    ): AiNetworkDashboardResponse {
        featureGate.requireDashboardEnabled()
        val overview = overview(guildId, refresh = refreshOverview)
        val channels = channels(guildId)
        val providers = providers(guildId, audience)
        val modelMap = modelMap(guildId)
        val knowledgeSpaces = knowledgeSpaces(guildId)
        val guildPresets = guildPresets(guildId)
        val publishedPresets = publishedPresets().take(10)
        val quality = qualityFeedback.guildSummary(guildId)
        val qualityReview = qualityFeedback.reviewSummary(guildId)
        val modelQuality = qualityFeedback.modelQuality(guildId)
        val changeApproval = changeApproval(guildId)
        val rawOverload = providerSafety.overloadAlerts(guildId)
        val overload = ProviderSafetyDashboardResponse.from(rawOverload, DashboardAudience.from(audience))
        val executionPlan = providerSafety.executionPlan(guildId, responseMode, requestedCandidates)
        val visibility = DashboardAudience.from(audience)
        val featureSnapshot = featureGate.snapshot()
        val overviewProjection = foundation.currentOverview(guildId) ?: foundation.emptyOverviewProjection(guildId)
        val multiResponseOperations =
            MultiResponseOperationsDashboardResponse.from(
                if (featureSnapshot.multiResponseDashboard) {
                    multiResponse.operationsSummary(guildId)
                } else {
                    MultiResponseOperationsSummary.disabled(guildId)
                },
                visibility,
            )
        val growthPlan = growth.growthPlanFromOverview(guildId, overviewProjection)
        val growthTimeline = growth.timelineCards(guildId).take(5)
        val readiness =
            readinessService.readiness(
                overview = overview,
                channels = channels,
                providers = providers,
                modelMap = modelMap,
                knowledgeSpaces = knowledgeSpaces,
                quality = quality,
                overload = rawOverload,
                changeApproval = changeApproval,
            )
        return AiNetworkDashboardResponse(
            metadata = DashboardMetadataResponse.from(overview),
            overview = overview,
            channels = channels,
            providers = providers,
            modelMap = modelMap,
            knowledgeSpaces = knowledgeSpaces,
            presets = guildPresets,
            publishedPresets = publishedPresets,
            quality = quality,
            qualityReview = qualityReview,
            modelQuality = modelQuality,
            changeApproval = changeApproval,
            overload = overload,
            executionPlan = executionPlan,
            multiResponseOperations = multiResponseOperations,
            growthPlan = growthPlan,
            growthTimeline = growthTimeline,
            readiness = readiness,
            nextActions =
                readinessService.nextActions(
                    overview = overview,
                    channels = channels,
                    modelMap = modelMap,
                    knowledgeSpaces = knowledgeSpaces,
                    quality = quality,
                    overload = rawOverload,
                    changeApproval = changeApproval,
                    growthPlan = growthPlan,
                ),
        )
    }

    @GetMapping("/{guildId}/launch-checklist")
    fun launchChecklist(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "admin") audience: String = "admin",
    ): AiNetworkLaunchChecklistResponse {
        featureGate.requireDashboardEnabled()
        val dashboard = dashboard(guildId, audience = audience)
        return readinessService.launchChecklist(dashboard, featureGate.snapshot())
    }

    @GetMapping("/{guildId}/overview")
    fun overview(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "true") refresh: Boolean = true,
    ): AiNetworkOverviewResponse {
        featureGate.requireDashboardEnabled()
        val profile = foundation.networkProfileView(guildId, refresh = refresh)
        val overview =
            if (refresh) {
                foundation.refreshOverview(guildId)
            } else {
                foundation.currentOverview(guildId) ?: foundation.emptyOverviewProjection(guildId)
            }
        val level = aiLevel.levelView(guildId)
        return AiNetworkOverviewResponse.from(
            guildId = profile.guildId,
            displayName = profile.displayName,
            tagline = profile.tagline,
            overview = overview,
            aiLevel = level.aiLevel,
            totalXp = level.totalXp,
            xpToNext = level.xpToNext,
            freshnessStatus = foundation.overviewFreshnessStatus(overview),
            degradedReason = foundation.overviewDegradedReason(overview),
        )
    }

    @GetMapping("/{guildId}/readiness")
    fun readiness(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): AiNetworkReadinessResponse {
        featureGate.requireDashboardEnabled()
        val overview = overview(guildId)
        return readinessService.readiness(
            overview = overview,
            channels = channels(guildId),
            providers = providers(guildId, audience),
            modelMap = modelMap(guildId),
            knowledgeSpaces = knowledgeSpaces(guildId),
            quality = qualityFeedback.guildSummary(guildId),
            overload = providerSafety.overloadAlerts(guildId),
            changeApproval = changeApproval(guildId),
        )
    }

    @GetMapping("/{guildId}/channels")
    fun channels(
        @PathVariable guildId: Long,
    ): List<ChannelAiCardResponse> {
        featureGate.requireDashboardEnabled()
        return query.channels(guildId)
    }

    @GetMapping("/{guildId}/channels/summary")
    fun channelsSummary(
        @PathVariable guildId: Long,
    ): ChannelAiFleetSummaryResponse {
        featureGate.requireDashboardEnabled()
        val channels = channels(guildId)
        val readinessCounts = channels.groupingBy { it.readinessStatus }.eachCount()
        val responseModeCounts = channels.groupingBy { it.responseMode }.eachCount()
        val knowledgeReadinessCounts = channels.groupingBy { it.knowledgeReadiness }.eachCount()
        val safetyLevelCounts = channels.groupingBy { it.safetyLevel ?: "unset" }.eachCount()
        val channelsNeedingAttention = channels.filter { it.readinessStatus != "ready" }
        return ChannelAiFleetSummaryResponse(
            guildId = guildId,
            totalChannelAiCount = channels.size,
            readyChannelAiCount = readinessCounts["ready"] ?: 0,
            channelsNeedingAttentionCount = channelsNeedingAttention.size,
            readinessCounts = readinessCounts,
            responseModeCounts = responseModeCounts,
            knowledgeReadinessCounts = knowledgeReadinessCounts,
            safetyLevelCounts = safetyLevelCounts,
            topAttentionItems =
                channelsNeedingAttention
                    .sortedWith(
                        compareBy<ChannelAiCardResponse> { readinessService.readinessRank(it.readinessStatus) }
                            .thenBy { it.channelId },
                    ).take(10)
                    .map {
                        ChannelAiAttentionItemResponse(
                            channelId = it.channelId,
                            name = it.name,
                            readinessStatus = it.readinessStatus,
                            missingParts = it.missingParts,
                            nextActions = it.nextActions,
                        )
                    },
        )
    }

    @GetMapping("/{guildId}/change-approval")
    fun changeApproval(
        @PathVariable guildId: Long,
    ): ChannelAiChangeApprovalDashboardResponse {
        featureGate.requireDashboardEnabled()
        return query.changeApproval(guildId)
    }

    @GetMapping("/{guildId}/providers")
    fun providers(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): List<ProviderCapabilityResponse> {
        featureGate.requireDashboardEnabled()
        return query.providers(guildId, audience)
    }

    @GetMapping("/{guildId}/model-map")
    fun modelMap(
        @PathVariable guildId: Long,
    ): List<ModelMapResponse> {
        featureGate.requireDashboardEnabled()
        return query.modelMap(guildId)
    }

    /** 어드민 (a): 채널 사용 현황 — 채널별 요청 수·고유 유저 수·마지막 사용 시각(집계만). */
    @GetMapping("/{guildId}/channel-usage")
    fun channelUsage(
        @PathVariable guildId: Long,
    ): List<ChannelUsageResponse> {
        featureGate.requireDashboardEnabled()
        return analytics.channelUsage(guildId).map { ChannelUsageResponse.from(it) }
    }

    /** 어드민 (d): 기능 사용 유저 목록 — userId·요청 수·첫/마지막 사용(프롬프트 본문 비노출, 집계만). */
    @GetMapping("/{guildId}/users")
    fun featureUsers(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "20") limit: Int = 20,
    ): List<FeatureUserResponse> {
        featureGate.requireDashboardEnabled()
        return analytics.featureUsers(guildId, limit.coerceIn(1, 200)).map { FeatureUserResponse.from(it) }
    }

    /** 어드민 (c): 프로바이더 참여 이력 타임라인. ?providerUserId= 로 특정 프로바이더만 조회 가능. */
    @GetMapping("/{guildId}/provider-history")
    fun providerHistory(
        @PathVariable guildId: Long,
        @RequestParam(required = false) providerUserId: Long? = null,
    ): List<ProviderHistoryResponse> {
        featureGate.requireDashboardEnabled()
        return analytics.providerHistoryTimeline(guildId, providerUserId).map { ProviderHistoryResponse.from(it) }
    }

    @GetMapping("/{guildId}/knowledge-spaces")
    fun knowledgeSpaces(
        @PathVariable guildId: Long,
    ): List<KnowledgeSpaceResponse> {
        featureGate.requireDashboardEnabled()
        return query.knowledgeSpaces(guildId)
    }

    @GetMapping("/{guildId}/presets")
    fun guildPresets(
        @PathVariable guildId: Long,
    ): Map<String, Any> {
        featureGate.requireDashboardEnabled()
        return query.guildPresets(guildId)
    }

    @GetMapping("/presets/published")
    fun publishedPresets(): List<PublishedPresetResponse> {
        featureGate.requireDashboardEnabled()
        return query.publishedPresets()
    }
}

data class AiNetworkDashboardResponse(
    val metadata: DashboardMetadataResponse,
    val overview: AiNetworkOverviewResponse,
    val channels: List<ChannelAiCardResponse>,
    val providers: List<ProviderCapabilityResponse>,
    val modelMap: List<ModelMapResponse>,
    val knowledgeSpaces: List<KnowledgeSpaceResponse>,
    val presets: Map<String, Any>,
    val publishedPresets: List<PublishedPresetResponse>,
    val quality: QualitySummary,
    val qualityReview: QualityReviewSummary,
    val modelQuality: List<ModelQualitySummary>,
    val changeApproval: ChannelAiChangeApprovalDashboardResponse,
    val overload: ProviderSafetyDashboardResponse,
    val executionPlan: ProviderSafetyExecutionPlan,
    val multiResponseOperations: MultiResponseOperationsDashboardResponse,
    val growthPlan: AiNetworkGrowthPlan,
    val growthTimeline: List<NetworkGrowthEventCard>,
    val readiness: AiNetworkReadinessResponse,
    val nextActions: List<AiNetworkNextActionResponse>,
)

data class AiNetworkLaunchChecklistResponse(
    val guildId: Long,
    val status: String,
    val score: Int,
    val readyCount: Int,
    val warningCount: Int,
    val blockedCount: Int,
    val releaseGate: String,
    val items: List<AiNetworkLaunchChecklistItemResponse>,
    val nextActions: List<String>,
)

data class AiNetworkLaunchChecklistItemResponse(
    val key: String,
    val title: String,
    val status: String,
    val evidence: List<String>,
    val nextAction: String,
    val blocking: Boolean,
)

data class ChannelAiChangeApprovalDashboardResponse(
    val guildId: Long,
    val status: String,
    val pendingCount: Int,
    val staleCount: Int,
    val rejectedCount: Int,
    val recentCount: Int,
    val pendingItems: List<ChannelAiChangeApprovalItemResponse>,
    val nextActions: List<String>,
)

data class ChannelAiChangeApprovalItemResponse(
    val id: Long,
    val channelId: Long,
    val channelAiId: Long?,
    val proposedBehaviorId: Long?,
    val requestedBy: Long?,
    val reason: String?,
    val createdAt: String,
) {
    companion object {
        fun from(entity: AiChangeProposalEntity): ChannelAiChangeApprovalItemResponse =
            ChannelAiChangeApprovalItemResponse(
                id = entity.id,
                channelId = entity.channelId,
                channelAiId = entity.channelAiId,
                proposedBehaviorId = entity.proposedBehaviorId,
                requestedBy = entity.requestedBy,
                reason = entity.reason,
                createdAt = entity.createdAt.toString(),
            )
    }
}

data class AiNetworkReadinessResponse(
    val guildId: Long,
    val status: String,
    val score: Int,
    val readyAreaCount: Int,
    val warningAreaCount: Int,
    val blockedAreaCount: Int,
    val areas: List<AiNetworkReadinessAreaResponse>,
    val topNextActions: List<String>,
)

data class AiNetworkReadinessAreaResponse(
    val key: String,
    val title: String,
    val status: String,
    val score: Int,
    val evidence: List<String>,
    val nextAction: String,
)

data class DashboardMetadataResponse(
    val generatedAt: String,
    val freshnessStatus: String,
    val stale: Boolean,
    val degradedReason: String?,
    val source: String = "network_overview_projection",
) {
    companion object {
        fun from(overview: AiNetworkOverviewResponse): DashboardMetadataResponse =
            DashboardMetadataResponse(
                generatedAt = overview.refreshedAt,
                freshnessStatus = overview.freshnessStatus,
                stale = overview.stale,
                degradedReason = overview.degradedReason,
            )
    }
}

data class ProviderSafetyDashboardResponse(
    val guildId: Long,
    val alertCount: Int,
    val highRiskCount: Int,
    val safeOnlineProviderCount: Int,
    val fanoutSafe: Boolean,
    val alerts: List<ProviderOverloadAlertResponse>,
) {
    companion object {
        fun from(
            dashboard: ProviderSafetyDashboard,
            audience: DashboardAudience,
        ): ProviderSafetyDashboardResponse =
            ProviderSafetyDashboardResponse(
                guildId = dashboard.guildId,
                alertCount = dashboard.alertCount,
                highRiskCount = dashboard.highRiskCount,
                safeOnlineProviderCount = dashboard.safeOnlineProviderCount,
                fanoutSafe = dashboard.fanoutSafe,
                alerts =
                    dashboard.alerts.mapIndexed { index, alert ->
                        ProviderOverloadAlertResponse(
                            providerUserId = if (audience.canSeeProviderIdentity) alert.providerUserId else null,
                            providerLabel =
                                if (audience.canSeeProviderIdentity) {
                                    "provider:${alert.providerUserId}"
                                } else {
                                    "Provider ${index + 1}"
                                },
                            providerState = audience.state(alert.providerState),
                            risk = audience.risk(alert.risk),
                            maxBurden = alert.maxBurden,
                            maxConcurrency = if (audience.canSeeProviderCapacity) alert.maxConcurrency else null,
                            dailyLimit = if (audience.canSeeProviderCapacity) alert.dailyLimit else null,
                            lastSeenAt = if (audience.canSeeProviderCapacity) alert.lastSeenAt?.toString() else null,
                            severityRank = alert.severityRank,
                            message =
                                if (audience.canSeeProviderIdentity) {
                                    alert.message
                                } else {
                                    alert.message.replace(Regex("Provider #\\d+"), "Provider")
                                },
                            recommendedAction = alert.recommendedAction,
                        )
                    },
            )
    }
}

data class ProviderOverloadAlertResponse(
    val providerUserId: Long?,
    val providerLabel: String,
    val providerState: String,
    val risk: String,
    val maxBurden: String,
    val maxConcurrency: Int?,
    val dailyLimit: Int?,
    val lastSeenAt: String?,
    val severityRank: Int,
    val message: String,
    val recommendedAction: String,
)

data class AiNetworkNextActionResponse(
    val priority: Int,
    val severity: String,
    val actionType: String,
    val title: String,
    val description: String,
    val ctaLabel: String,
    val discordCommand: String?,
    val dashboardPath: String,
)

data class AiNetworkOverviewResponse(
    val guildId: Long,
    val displayName: String,
    val tagline: String,
    val onlineProviderCount: Int,
    val approvedProviderCount: Int,
    val modelCount: Int,
    val channelAiCount: Int,
    val knowledgeSpaceCount: Int,
    val feedbackCount: Int,
    val overloadAlertCount: Int,
    val networkLevel: Int,
    val aiLevel: Int,
    val totalXp: Long,
    val xpToNext: Long,
    val healthStatus: String,
    val refreshedAt: String,
    val staleAfter: String?,
    val freshnessStatus: String,
    val stale: Boolean,
    val degradedReason: String?,
) {
    companion object {
        fun from(
            guildId: Long,
            displayName: String,
            tagline: String,
            overview: NetworkOverviewProjectionEntity,
            aiLevel: Int,
            totalXp: Long,
            xpToNext: Long,
            freshnessStatus: String,
            degradedReason: String?,
        ): AiNetworkOverviewResponse =
            AiNetworkOverviewResponse(
                guildId = guildId,
                displayName = displayName,
                tagline = tagline,
                onlineProviderCount = overview.onlineProviderCount,
                approvedProviderCount = overview.approvedProviderCount,
                modelCount = overview.modelCount,
                channelAiCount = overview.channelAiCount,
                knowledgeSpaceCount = overview.knowledgeSpaceCount,
                feedbackCount = overview.feedbackCount,
                overloadAlertCount = overview.overloadAlertCount,
                networkLevel = overview.networkLevel,
                aiLevel = aiLevel,
                totalXp = totalXp,
                xpToNext = xpToNext,
                healthStatus = overview.healthStatus,
                refreshedAt = overview.refreshedAt.toString(),
                staleAfter = overview.staleAfter?.toString(),
                freshnessStatus = freshnessStatus,
                stale = freshnessStatus == "stale",
                degradedReason = degradedReason,
            )
    }
}

data class ChannelAiCardResponse(
    val channelId: Long,
    val name: String,
    val avatarUrl: String?,
    val activeBehaviorVersionId: Long?,
    val source: String,
    val purpose: String?,
    val tone: String?,
    val answerLength: String?,
    val safetyLevel: String?,
    val responseMode: String,
    val preferredModel: String?,
    val allowedModels: List<String>,
    val minQualityTier: String,
    val knowledgeReadiness: String,
    val knowledgeSpaceCount: Int,
    val indexedKnowledgeSourceCount: Int,
    val blockedKnowledgeSourceCount: Int,
    val multiResponseMode: String,
    val multiResponseMaxCandidates: Int,
    val multiResponseSynthesisEnabled: Boolean,
    val updatedAt: String,
    val readinessStatus: String = "unknown",
    val missingParts: List<String> = emptyList(),
    val nextActions: List<String> = emptyList(),
)

data class ChannelAiFleetSummaryResponse(
    val guildId: Long,
    val totalChannelAiCount: Int,
    val readyChannelAiCount: Int,
    val channelsNeedingAttentionCount: Int,
    val readinessCounts: Map<String, Int>,
    val responseModeCounts: Map<String, Int>,
    val knowledgeReadinessCounts: Map<String, Int>,
    val safetyLevelCounts: Map<String, Int>,
    val topAttentionItems: List<ChannelAiAttentionItemResponse>,
)

data class ChannelAiAttentionItemResponse(
    val channelId: Long,
    val name: String,
    val readinessStatus: String,
    val missingParts: List<String>,
    val nextActions: List<String>,
)

data class ProviderCapabilityResponse(
    val providerUserId: Long?,
    val providerLabel: String,
    val state: String,
    val modelCount: Int,
    val models: List<String>,
    val tags: List<String>,
    val qualityTier: String,
    val maxBurden: String,
    val maxConcurrency: Int?,
    val dailyLimit: Int?,
    val overloadRisk: String,
    // 가용시간(UTC 시)·마지막 활동 — 운영 식별 정보이므로 capacity 가시성(provider/admin)에서만 채움.
    val availableFromHour: Int? = null,
    val availableToHour: Int? = null,
    val lastSeenAt: String?,
)

data class ModelMapResponse(
    val modelName: String,
    val totalProviderCount: Int,
    val onlineProviderCount: Int,
    val protectedProviderCount: Int,
    val qualityTiers: List<String>,
    val maxBurdens: List<String>,
    val tags: List<String>,
    val channelCount: Int,
    val channels: List<Long>,
)

enum class DashboardAudience(
    val canSeeProviderIdentity: Boolean,
    val canSeeProviderCapacity: Boolean,
) {
    PUBLIC(false, false),
    PROVIDER(false, true),
    ADMIN(true, true),
    ;

    fun state(value: String): String =
        if (canSeeProviderCapacity) {
            value
        } else if (value.equals("ONLINE", ignoreCase = true)) {
            "available"
        } else {
            "unavailable"
        }

    fun risk(value: String): String =
        if (canSeeProviderCapacity) {
            value
        } else if (value.equals("high", ignoreCase = true) || value.equals("critical", ignoreCase = true)) {
            "protected"
        } else {
            "normal"
        }

    companion object {
        fun from(value: String): DashboardAudience = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PUBLIC
    }
}

data class KnowledgeSpaceResponse(
    val id: Long,
    val channelId: Long?,
    val channelAiId: Long?,
    val name: String,
    val status: String,
    val sourceCount: Int,
    val chunkCount: Int,
    val embeddingModel: String?,
    val indexName: String?,
    val updatedAt: String,
)

data class PublishedPresetResponse(
    val id: Long,
    val slug: String,
    val title: String,
    val description: String?,
    val publisherGuildId: Long?,
    val publisherLabel: String,
    val likeCount: Int,
    val importCount: Int,
    val reportCount: Int,
    val publishedAt: String,
)

/** 어드민 (a) 채널 사용 현황 응답(집계만, 프롬프트/메시지 본문 없음). */
data class ChannelUsageResponse(
    val channelId: Long,
    val requestCount: Long,
    val distinctUsers: Long,
    val lastUsedAt: String?,
) {
    companion object {
        fun from(usage: AnalyticsService.ChannelUsage): ChannelUsageResponse =
            ChannelUsageResponse(
                channelId = usage.channelId,
                requestCount = usage.requestCount,
                distinctUsers = usage.distinctUsers,
                lastUsedAt = usage.lastUsedAt,
            )
    }
}

/** 어드민 (d) 기능 사용 유저 응답(userId·집계만, 프롬프트/메시지 본문 없음). */
data class FeatureUserResponse(
    val userId: Long,
    val requestCount: Long,
    val firstUsedAt: String?,
    val lastUsedAt: String?,
) {
    companion object {
        fun from(usage: AnalyticsService.UserUsage): FeatureUserResponse =
            FeatureUserResponse(
                userId = usage.userId,
                requestCount = usage.requestCount,
                firstUsedAt = usage.firstUsedAt,
                lastUsedAt = usage.lastUsedAt,
            )
    }
}

/** 어드민 (c) 프로바이더 참여 이력 응답(이벤트 메타데이터만). */
data class ProviderHistoryResponse(
    val id: Long,
    val eventType: String,
    val providerUserId: Long?,
    val title: String,
    val summary: String?,
    val createdAt: String,
) {
    companion object {
        fun from(entry: AnalyticsService.ProviderHistoryEntry): ProviderHistoryResponse =
            ProviderHistoryResponse(
                id = entry.id,
                eventType = entry.eventType,
                providerUserId = entry.providerUserId,
                title = entry.title,
                summary = entry.summary,
                createdAt = entry.createdAt,
            )
    }
}
