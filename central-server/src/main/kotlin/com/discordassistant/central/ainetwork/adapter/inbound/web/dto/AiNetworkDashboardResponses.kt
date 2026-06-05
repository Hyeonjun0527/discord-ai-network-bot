package com.discordassistant.central.ainetwork.adapter.inbound.web.dto

import com.discordassistant.central.ainetwork.application.AiNetworkGrowthPlan
import com.discordassistant.central.ainetwork.application.DashboardAudience
import com.discordassistant.central.ainetwork.application.ModelQualitySummary
import com.discordassistant.central.ainetwork.application.NetworkGrowthEventCard
import com.discordassistant.central.ainetwork.application.ProviderSafetyDashboard
import com.discordassistant.central.ainetwork.application.ProviderSafetyExecutionPlan
import com.discordassistant.central.ainetwork.application.QualityReviewSummary
import com.discordassistant.central.ainetwork.application.QualitySummary
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.MultiResponseOperationsDashboardResponse

// AI Network 대시보드 read API 응답 DTO 모음(인바운드 웹 어댑터의 와이어 계약).
//
// - 응답 JSON 키·값·순서·null·중첩·조건부키는 분해 이전과 1바이트도 다르지 않다.
// - audience 기반 마스킹(보안)·providerLabel 프레젠테이션은 ProviderSafetyDashboardResponse.from 안에만 있다.
// - 엔티티→DTO 조립은 application(AiNetworkDashboardQueryService)에서 수행하고(controller↛persistence),
//   이 파일은 순수 DTO 와 DTO↔DTO 매핑만 가진다.

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
)

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
)

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
)

/** 어드민 (d) 기능 사용 유저 응답(userId·집계만, 프롬프트/메시지 본문 없음). */
data class FeatureUserResponse(
    val userId: Long,
    val requestCount: Long,
    val firstUsedAt: String?,
    val lastUsedAt: String?,
)

/** 어드민 (c) 프로바이더 참여 이력 응답(이벤트 메타데이터만). */
data class ProviderHistoryResponse(
    val id: Long,
    val eventType: String,
    val providerUserId: Long?,
    val title: String,
    val summary: String?,
    val createdAt: String,
)
