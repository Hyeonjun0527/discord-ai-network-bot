package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkLaunchChecklistResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkOverviewResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkReadinessResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiAttentionItemResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiCardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiChangeApprovalDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiChangeApprovalItemResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiFleetSummaryResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelUsageResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.DashboardMetadataResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.FeatureUserResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.KnowledgeSpaceResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ModelMapResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderCapabilityResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderHistoryResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderSafetyDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.PublishedPresetResponse
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.domain.model.ProposalStatus
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.knowledge.domain.model.KnowledgeSpaceStatus
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.MultiResponseOperationsDashboardResponse
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.multiresponse.application.MultiResponseOperationsSummary
import com.discordassistant.central.multiresponse.application.MultiResponseService
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetRepository
import com.discordassistant.central.preset.domain.model.PublishedPresetStatus
import com.discordassistant.central.requestlog.application.AnalyticsService
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.ModelQualityTier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AI Network 대시보드의 **영속 계층 read·매핑 + read API 오케스트레이션** 책임. 컨트롤러(웹 어댑터)가
 * 리포지토리/엔티티를 직접 만지고 fan-out·조합·audience redaction 을 인라인으로 수행하던 god class·
 * 클린아키텍처 위반(controller↛persistence)을 제거하기 위해 application 으로 흡수했다.
 * 엔티티→응답 DTO 매핑과 dashboard/overview/readiness/launchChecklist/channelsSummary 조립을 담당하며,
 * 컨트롤러는 요청 파싱 + featureGate 게이트 + 단일 위임만 한다.
 */
@Service
@Transactional(readOnly = true)
class AiNetworkDashboardQueryService(
    private val channelAis: ChannelAiRepository,
    private val behaviorVersions: AiBehaviorVersionRepository,
    private val proposals: AiChangeProposalRepository,
    private val routingPolicies: ChannelAiRoutingPolicyRepository,
    private val multiResponsePolicies: MultiResponsePolicyRepository,
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val knowledgeSpaces: KnowledgeSpaceRepository,
    private val knowledgeSources: KnowledgeSourceRepository,
    private val presets: AiPresetRepository,
    private val publishedPresets: PublishedPresetRepository,
    private val presetImports: PresetImportRepository,
    private val foundation: AiNetworkFoundationService,
    private val aiLevel: AiLevelService,
    private val growth: AiNetworkGrowthService,
    private val qualityFeedback: AiQualityFeedbackService,
    private val providerSafety: ProviderSafetyService,
    private val multiResponse: MultiResponseService,
    private val analytics: AnalyticsService,
    private val readinessService: AiNetworkReadinessService = AiNetworkReadinessService(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun dashboard(
        guildId: Long,
        audience: String = "public",
        responseMode: String = "balanced",
        requestedCandidates: Int = 1,
        refreshOverview: Boolean = false,
    ): AiNetworkDashboardResponse {
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

    fun launchChecklist(
        guildId: Long,
        audience: String = "admin",
    ): AiNetworkLaunchChecklistResponse {
        val dashboard = dashboard(guildId, audience = audience)
        return readinessService.launchChecklist(dashboard, featureGate.snapshot())
    }

    fun overview(
        guildId: Long,
        refresh: Boolean = true,
    ): AiNetworkOverviewResponse {
        val profile = foundation.networkProfileView(guildId, refresh = refresh)
        val overview =
            if (refresh) {
                foundation.refreshOverview(guildId)
            } else {
                foundation.currentOverview(guildId) ?: foundation.emptyOverviewProjection(guildId)
            }
        val level = aiLevel.levelView(guildId)
        return overviewResponse(
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

    fun readiness(
        guildId: Long,
        audience: String = "public",
    ): AiNetworkReadinessResponse {
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

    fun channelsSummary(guildId: Long): ChannelAiFleetSummaryResponse {
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

    fun channelUsage(guildId: Long): List<ChannelUsageResponse> = analytics.channelUsage(guildId).map { it.toResponse() }

    fun featureUsers(
        guildId: Long,
        limit: Int = 20,
    ): List<FeatureUserResponse> = analytics.featureUsers(guildId, limit.coerceIn(1, 200)).map { it.toResponse() }

    fun providerHistory(
        guildId: Long,
        providerUserId: Long? = null,
    ): List<ProviderHistoryResponse> = analytics.providerHistoryTimeline(guildId, providerUserId).map { it.toResponse() }

    fun channels(guildId: Long): List<ChannelAiCardResponse> =
        channelAis.findByGuildId(guildId).map { channelAi ->
            val behavior = channelAi.activeBehaviorVersionId?.let { behaviorVersions.findByChannelAiIdAndId(channelAi.id, it) }
            val route = routingPolicies.findByGuildIdAndChannelId(guildId, channelAi.channelId)
            val spaces = knowledgeSpaces.findByGuildIdAndChannelId(guildId, channelAi.channelId)
            val indexedSources =
                spaces.sumOf { space ->
                    knowledgeSources
                        .findByKnowledgeSpaceId(space.id)
                        .count { it.status.isIndexed }
                }
            val blockedSources =
                spaces.sumOf { space ->
                    knowledgeSources
                        .findByKnowledgeSpaceId(space.id)
                        .count {
                            it.status.isBlocked ||
                                it.riskLevel in BLOCKING_KNOWLEDGE_RISKS
                        }
                }
            val knowledgeReadiness =
                when {
                    indexedSources > 0 && blockedSources == 0 -> "ready"
                    indexedSources > 0 -> "partial"
                    blockedSources > 0 -> "needs_review"
                    spaces.any { it.status == KnowledgeSpaceStatus.PENDING_INDEX } -> "indexing_needed"
                    else -> "empty"
                }
            val multi =
                multiResponsePolicies.findByGuildIdAndChannelId(guildId, channelAi.channelId)
                    ?: multiResponsePolicies.findByGuildIdAndChannelIdIsNull(guildId)
            ChannelAiCardResponse(
                channelId = channelAi.channelId,
                name = channelAi.displayName,
                avatarUrl = channelAi.avatarUrl,
                activeBehaviorVersionId = channelAi.activeBehaviorVersionId,
                source = channelAi.source,
                purpose = behavior?.purpose,
                tone = behavior?.tone,
                answerLength = behavior?.answerLength,
                safetyLevel = behavior?.safetyLevel,
                responseMode = route?.responseMode ?: "balanced",
                preferredModel = route?.preferredModel,
                allowedModels = splitCsv(route?.allowedModels),
                minQualityTier = route?.minQualityTier ?: "standard",
                knowledgeReadiness = knowledgeReadiness,
                knowledgeSpaceCount = spaces.size,
                indexedKnowledgeSourceCount = indexedSources,
                blockedKnowledgeSourceCount = blockedSources,
                multiResponseMode = multi?.mode ?: "single",
                multiResponseMaxCandidates = multi?.maxCandidates ?: 1,
                multiResponseSynthesisEnabled = multi?.synthesisEnabled ?: false,
                updatedAt = channelAi.updatedAt.toString(),
            ).withReadiness()
        }

    fun changeApproval(guildId: Long): ChannelAiChangeApprovalDashboardResponse {
        val all = proposals.findByGuildIdOrderByCreatedAtDesc(guildId)
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

    fun providers(
        guildId: Long,
        audience: String,
    ): List<ProviderCapabilityResponse> {
        val visibility = DashboardAudience.from(audience)
        return providerCapabilities.findByGuildId(guildId).mapIndexed { index, provider ->
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
    }

    fun modelMap(guildId: Long): List<ModelMapResponse> {
        val modelToChannels = modelChannelUsage(guildId)
        return providerCapabilities
            .findByGuildId(guildId)
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
    }

    fun knowledgeSpaces(guildId: Long): List<KnowledgeSpaceResponse> =
        knowledgeSpaces.findByGuildId(guildId).map {
            KnowledgeSpaceResponse(
                id = it.id,
                channelId = it.channelId,
                channelAiId = it.channelAiId,
                name = it.displayName,
                status = it.status.wire,
                sourceCount = it.sourceCount,
                chunkCount = it.chunkCount,
                embeddingModel = it.embeddingModel,
                indexName = it.indexName,
                updatedAt = it.updatedAt.toString(),
            )
        }

    fun guildPresets(guildId: Long): Map<String, Any> =
        mapOf(
            "guildId" to guildId,
            "local" to
                presets.findByGuildId(guildId).map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "summary" to it.summary,
                        "category" to it.category,
                        "visibility" to it.visibility,
                        "status" to it.status.wire,
                        "currentRevisionId" to it.currentRevisionId,
                    )
                },
            "imports" to
                presetImports.findByTargetGuildId(guildId).map {
                    mapOf(
                        "id" to it.id,
                        "publishedPresetId" to it.publishedPresetId,
                        "targetChannelId" to it.targetChannelId,
                        "status" to it.status,
                        "importedAt" to it.importedAt.toString(),
                    )
                },
        )

    fun publishedPresets(): List<PublishedPresetResponse> =
        publishedPresets.findByStatusOrderByLikeCountDescPublishedAtDesc(PublishedPresetStatus.PUBLISHED).map {
            PublishedPresetResponse(
                id = it.id,
                slug = it.slug,
                title = it.title,
                description = it.description,
                publisherGuildId = null,
                publisherLabel = "공개 프리셋 작성자",
                likeCount = it.likeCount,
                importCount = it.importCount,
                reportCount = it.reportCount,
                publishedAt = it.publishedAt.toString(),
            )
        }

    private fun modelChannelUsage(guildId: Long): Map<String, Set<Long>> {
        val usage = linkedMapOf<String, MutableSet<Long>>()
        routingPolicies.findByGuildId(guildId).forEach { policy ->
            val models = listOfNotNull(policy.preferredModel) + splitCsv(policy.allowedModels)
            models
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { model -> usage.getOrPut(model) { linkedSetOf() }.add(policy.channelId) }
        }
        return usage
    }

    private fun ChannelAiCardResponse.withReadiness(): ChannelAiCardResponse {
        val missing =
            buildList {
                if (activeBehaviorVersionId == null) add("behavior_version")
                if (purpose.isNullOrBlank()) add("purpose")
                if (tone.isNullOrBlank()) add("tone")
                if (knowledgeReadiness in setOf("empty", "indexing_needed", "needs_review")) add("knowledge")
                if (preferredModel.isNullOrBlank() && allowedModels.isEmpty()) add("model_policy")
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
        return copy(readinessStatus = readiness, missingParts = missing, nextActions = actions)
    }

    // 엔티티→DTO 매핑(controller↛persistence 보장: 엔티티는 application 안에서만 만진다, 의미/필드 불변).

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

    private fun overviewResponse(
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

    private fun AnalyticsService.ChannelUsage.toResponse(): ChannelUsageResponse =
        ChannelUsageResponse(
            channelId = channelId,
            requestCount = requestCount,
            distinctUsers = distinctUsers,
            lastUsedAt = lastUsedAt,
        )

    private fun AnalyticsService.UserUsage.toResponse(): FeatureUserResponse =
        FeatureUserResponse(
            userId = userId,
            requestCount = requestCount,
            firstUsedAt = firstUsedAt,
            lastUsedAt = lastUsedAt,
        )

    private fun AnalyticsService.ProviderHistoryEntry.toResponse(): ProviderHistoryResponse =
        ProviderHistoryResponse(
            id = id,
            eventType = eventType,
            providerUserId = providerUserId,
            title = title,
            summary = summary,
            createdAt = createdAt,
        )

    private fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

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
