package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkOverviewResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiCardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiChangeApprovalDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiChangeApprovalItemResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelUsageResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.FeatureUserResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.KnowledgeSpaceResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ModelMapResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderCapabilityResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderHistoryResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.PublishedPresetResponse
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity
import com.discordassistant.central.channelai.domain.model.ProposalStatus
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.knowledge.domain.model.KnowledgeSpaceStatus
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetEntity
import com.discordassistant.central.requestlog.application.AnalyticsService
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.ModelQualityTier
import org.springframework.stereotype.Component

/**
 * AI Network 대시보드의 **순수 매핑/포매팅/redaction SSOT**(엔티티→응답 DTO 변환·audience 마스킹·
 * 채널 readiness 카드 계산·모델맵 조립·freshness 등급 표시 문구).
 *
 * `AiNetworkDashboardQueryService` 가 @Transactional(readOnly=true) 경계 안에서 리포지토리로 읽은
 * 엔티티/투영을 입력으로 받아 TX 와 무관한 순수 변환만 수행한다. **리포지토리 fan-out·오케스트레이션·
 * @Transactional 메서드는 절대 여기로 이동하지 않는다**(별 빈 프록시 경유 시 TX 의미 변화).
 * audience redaction(provider 식별자/용량/risk 마스킹)·readiness 임계값·사용자 노출 문구·JSON 매핑
 * 키는 기존 본문 그대로(1바이트 불변) 옮겼다. 파사드는 public 시그니처를 유지한 채 위임만 하고,
 * 이 협력자는 생성자 기본값으로 와이어된다.
 */
@Component
class AiNetworkDashboardMapper {
    fun knowledgeReadiness(
        spaces: List<KnowledgeSpaceEntity>,
        indexedSources: Int,
        blockedSources: Int,
    ): String =
        when {
            indexedSources > 0 && blockedSources == 0 -> "ready"
            indexedSources > 0 -> "partial"
            blockedSources > 0 -> "needs_review"
            spaces.any { it.status == KnowledgeSpaceStatus.PENDING_INDEX } -> "indexing_needed"
            else -> "empty"
        }

    fun indexedSourceCount(sources: List<KnowledgeSourceEntity>): Int = sources.count { it.status.isIndexed }

    fun blockedSourceCount(sources: List<KnowledgeSourceEntity>): Int =
        sources.count {
            it.status.isBlocked ||
                it.riskLevel in BLOCKING_KNOWLEDGE_RISKS
        }

    fun providers(
        providers: List<ProviderCapabilityProfileEntity>,
        visibility: DashboardAudience,
    ): List<ProviderCapabilityResponse> =
        providers.mapIndexed { index, provider ->
            ProviderCapabilityResponse(
                providerUserId = if (visibility.canSeeProviderIdentity) provider.providerUserId else null,
                providerLabel = if (visibility.canSeeProviderIdentity) "provider:${provider.providerUserId}" else "Provider ${index + 1}",
                state = visibility.state(provider.providerState.wire),
                modelCount = provider.modelCount,
                models = splitCsv(provider.modelNames),
                tags = splitCsv(provider.capabilityTags),
                qualityTier = provider.qualityTier.wire,
                maxBurden = provider.maxBurden.name,
                maxConcurrency = if (visibility.canSeeProviderCapacity) provider.maxConcurrency else null,
                dailyLimit = if (visibility.canSeeProviderCapacity) provider.dailyLimit else null,
                overloadRisk = visibility.risk(provider.overloadRisk.wire),
                availableFromHour = if (visibility.canSeeProviderCapacity) provider.availableFromHour else null,
                availableToHour = if (visibility.canSeeProviderCapacity) provider.availableToHour else null,
                lastSeenAt = if (visibility.canSeeProviderCapacity) provider.lastSeenAt?.toString() else null,
            )
        }

    fun modelMap(
        providers: List<ProviderCapabilityProfileEntity>,
        modelToChannels: Map<String, Set<Long>>,
    ): List<ModelMapResponse> =
        providers
            .flatMap { provider ->
                splitCsv(provider.modelNames).map { modelName ->
                    ModelProviderSnapshot(
                        modelName = modelName,
                        providerState = provider.providerState,
                        qualityTier = provider.qualityTier,
                        maxBurden = provider.maxBurden,
                        overloadRisk = provider.overloadRisk,
                        tags = splitCsv(provider.capabilityTags),
                    )
                }
            }.groupBy { it.modelName }
            .map { (modelName, providers) ->
                ModelMapResponse(
                    modelName = modelName,
                    totalProviderCount = providers.size,
                    onlineProviderCount = providers.count { it.providerState == ProviderAvailability.ONLINE },
                    protectedProviderCount = providers.count { it.overloadRisk.isOverload },
                    qualityTiers =
                        providers
                            .map { it.qualityTier }
                            .distinct()
                            .sortedByDescending { it.rank }
                            .map { it.wire },
                    maxBurdens =
                        providers
                            .map { it.maxBurden }
                            .distinct()
                            .sortedByDescending { it.rank }
                            .map { it.name },
                    tags = providers.flatMap { it.tags }.distinct().sorted(),
                    channelCount = modelToChannels[modelName].orEmpty().size,
                    channels = modelToChannels[modelName].orEmpty().sorted(),
                )
            }.sortedWith(
                compareByDescending<ModelMapResponse> { it.onlineProviderCount }
                    .thenByDescending { it.totalProviderCount }
                    .thenBy { it.modelName },
            )

    fun knowledgeSpace(space: KnowledgeSpaceEntity): KnowledgeSpaceResponse =
        KnowledgeSpaceResponse(
            id = space.id,
            channelId = space.channelId,
            channelAiId = space.channelAiId,
            name = space.displayName,
            status = space.status.wire,
            sourceCount = space.sourceCount,
            chunkCount = space.chunkCount,
            embeddingModel = space.embeddingModel,
            indexName = space.indexName,
            updatedAt = space.updatedAt.toString(),
        )

    fun publishedPreset(preset: PublishedPresetEntity): PublishedPresetResponse =
        PublishedPresetResponse(
            id = preset.id,
            slug = preset.slug,
            title = preset.title,
            description = preset.description,
            publisherGuildId = null,
            publisherLabel = "공개 프리셋 작성자",
            likeCount = preset.likeCount,
            importCount = preset.importCount,
            reportCount = preset.reportCount,
            publishedAt = preset.publishedAt.toString(),
        )

    fun changeApproval(
        guildId: Long,
        all: List<AiChangeProposalEntity>,
    ): ChannelAiChangeApprovalDashboardResponse {
        val pending = all.filter { it.status == ProposalStatus.PENDING }
        val stale = all.filter { it.status == ProposalStatus.STALE }
        val rejected = all.filter { it.status == ProposalStatus.REJECTED }
        val status =
            when {
                stale.isNotEmpty() -> "blocked"
                pending.isNotEmpty() -> "needs_review"
                rejected.isNotEmpty() -> "warning"
                else -> "ready"
            }
        return ChannelAiChangeApprovalDashboardResponse(
            guildId = guildId,
            status = status,
            pendingCount = pending.size,
            staleCount = stale.size,
            rejectedCount = rejected.size,
            recentCount = all.size,
            pendingItems = pending.take(10).map { it.toApprovalItem() },
            nextActions =
                buildList {
                    if (pending.isNotEmpty()) add("pending AI 설정 변경을 승인하거나 거절하세요.")
                    if (stale.isNotEmpty()) add("stale 변경 제안은 새 제안으로 다시 생성하세요.")
                    if (rejected.isNotEmpty()) add("거절 사유를 반영한 새 행동 버전을 제안하세요.")
                    if (isEmpty()) add("검토 대기 중인 AI 설정 변경은 없습니다.")
                },
        )
    }

    fun overviewResponse(
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

    fun channelUsage(usage: AnalyticsService.ChannelUsage): ChannelUsageResponse =
        ChannelUsageResponse(
            channelId = usage.channelId,
            requestCount = usage.requestCount,
            distinctUsers = usage.distinctUsers,
            lastUsedAt = usage.lastUsedAt,
        )

    fun featureUser(usage: AnalyticsService.UserUsage): FeatureUserResponse =
        FeatureUserResponse(
            userId = usage.userId,
            requestCount = usage.requestCount,
            firstUsedAt = usage.firstUsedAt,
            lastUsedAt = usage.lastUsedAt,
        )

    fun providerHistory(entry: AnalyticsService.ProviderHistoryEntry): ProviderHistoryResponse =
        ProviderHistoryResponse(
            id = entry.id,
            eventType = entry.eventType,
            providerUserId = entry.providerUserId,
            title = entry.title,
            summary = entry.summary,
            createdAt = entry.createdAt,
        )

    fun withReadiness(card: ChannelAiCardResponse): ChannelAiCardResponse {
        val missing =
            buildList {
                if (card.activeBehaviorVersionId == null) add("behavior_version")
                if (card.purpose.isNullOrBlank()) add("purpose")
                if (card.tone.isNullOrBlank()) add("tone")
                if (card.knowledgeReadiness in setOf("empty", "indexing_needed", "needs_review")) add("knowledge")
                if (card.preferredModel.isNullOrBlank() && card.allowedModels.isEmpty()) add("model_policy")
            }
        val readiness =
            when {
                missing.any { it == "behavior_version" || it == "purpose" } -> "needs_profile"
                missing.any { it == "knowledge" } -> "needs_knowledge"
                missing.any { it == "model_policy" } -> "needs_model_policy"
                else -> "ready"
            }
        val actions =
            missing
                .map { part ->
                    when (part) {
                        "behavior_version", "purpose", "tone" -> "채널프로필 패널에서 역할·말투를 저장하세요."
                        "knowledge" -> "채널 지식공간에 README·규칙·FAQ를 추가하고 색인하세요."
                        "model_policy" -> "응답 속도/품질 모드와 선호 모델 정책을 설정하세요."
                        else -> "채널 AI 설정을 점검하세요."
                    }
                }.distinct()
        return card.copy(readinessStatus = readiness, missingParts = missing, nextActions = actions)
    }

    fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun AiChangeProposalEntity.toApprovalItem(): ChannelAiChangeApprovalItemResponse =
        ChannelAiChangeApprovalItemResponse(
            id = id,
            channelId = channelId,
            channelAiId = channelAiId,
            proposedBehaviorId = proposedBehaviorId,
            requestedBy = requestedBy,
            reason = reason,
            createdAt = createdAt.toString(),
        )

    private data class ModelProviderSnapshot(
        val modelName: String,
        val providerState: ProviderAvailability,
        val qualityTier: ModelQualityTier,
        val maxBurden: ModelBurden,
        val overloadRisk: OverloadRisk,
        val tags: List<String>,
    )

    private companion object {
        val BLOCKING_KNOWLEDGE_RISKS = setOf("sensitive", "ssrf")
    }
}
