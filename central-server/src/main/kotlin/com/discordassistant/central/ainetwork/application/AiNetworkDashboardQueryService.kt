package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkLaunchChecklistResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkOverviewResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkReadinessResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiAttentionItemResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiCardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiChangeApprovalDashboardResponse
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
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.multiresponse.adapter.inbound.web.dto.MultiResponseOperationsDashboardResponse
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.multiresponse.application.MultiResponseOperationsSummary
import com.discordassistant.central.multiresponse.application.MultiResponseService
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetRepository
import com.discordassistant.central.preset.domain.model.PublishedPresetStatus
import com.discordassistant.central.requestlog.application.AnalyticsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AI Network 대시보드의 **영속 계층 read·매핑 + read API 오케스트레이션** 책임. 컨트롤러(웹 어댑터)가
 * 리포지토리/엔티티를 직접 만지고 fan-out·조합·audience redaction 을 인라인으로 수행하던 god class·
 * 클린아키텍처 위반(controller↛persistence)을 제거하기 위해 application 으로 흡수했다.
 * 엔티티→응답 DTO 매핑과 dashboard/overview/readiness/launchChecklist/channelsSummary 조립을 담당하며,
 * 컨트롤러는 요청 파싱 + featureGate 게이트 + 단일 위임만 한다.
 *
 * 순수 매핑/포매팅/audience redaction/readiness 카드 계산은 [AiNetworkDashboardMapper] 로 분해했고,
 * 이 파사드는 @Transactional(readOnly=true) 경계 안에서 리포지토리 fan-out 만 수행한 뒤 매퍼에 위임한다.
 * public 시그니처는 전부 유지(컨트롤러·테스트 무수정), 매퍼는 생성자 기본값으로 와이어된다.
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
    private val mapper: AiNetworkDashboardMapper = AiNetworkDashboardMapper(),
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
        return mapper.overviewResponse(
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

    fun channelUsage(guildId: Long): List<ChannelUsageResponse> = analytics.channelUsage(guildId).map { mapper.channelUsage(it) }

    fun featureUsers(
        guildId: Long,
        limit: Int = 20,
    ): List<FeatureUserResponse> = analytics.featureUsers(guildId, limit.coerceIn(1, 200)).map { mapper.featureUser(it) }

    fun providerHistory(
        guildId: Long,
        providerUserId: Long? = null,
    ): List<ProviderHistoryResponse> = analytics.providerHistoryTimeline(guildId, providerUserId).map { mapper.providerHistory(it) }

    fun channels(guildId: Long): List<ChannelAiCardResponse> =
        channelAis.findByGuildId(guildId).map { channelAi ->
            val behavior = channelAi.activeBehaviorVersionId?.let { behaviorVersions.findByChannelAiIdAndId(channelAi.id, it) }
            val route = routingPolicies.findByGuildIdAndChannelId(guildId, channelAi.channelId)
            val spaces = knowledgeSpaces.findByGuildIdAndChannelId(guildId, channelAi.channelId)
            val indexedSources =
                spaces.sumOf { space ->
                    mapper.indexedSourceCount(knowledgeSources.findByKnowledgeSpaceId(space.id))
                }
            val blockedSources =
                spaces.sumOf { space ->
                    mapper.blockedSourceCount(knowledgeSources.findByKnowledgeSpaceId(space.id))
                }
            val knowledgeReadiness = mapper.knowledgeReadiness(spaces, indexedSources, blockedSources)
            val multi =
                multiResponsePolicies.findByGuildIdAndChannelId(guildId, channelAi.channelId)
                    ?: multiResponsePolicies.findByGuildIdAndChannelIdIsNull(guildId)
            mapper.withReadiness(
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
                    allowedModels = mapper.splitCsv(route?.allowedModels),
                    minQualityTier = route?.minQualityTier ?: "standard",
                    knowledgeReadiness = knowledgeReadiness,
                    knowledgeSpaceCount = spaces.size,
                    indexedKnowledgeSourceCount = indexedSources,
                    blockedKnowledgeSourceCount = blockedSources,
                    multiResponseMode = multi?.mode ?: "single",
                    multiResponseMaxCandidates = multi?.maxCandidates ?: 1,
                    multiResponseSynthesisEnabled = multi?.synthesisEnabled ?: false,
                    updatedAt = channelAi.updatedAt.toString(),
                ),
            )
        }

    fun changeApproval(guildId: Long): ChannelAiChangeApprovalDashboardResponse =
        mapper.changeApproval(guildId, proposals.findByGuildIdOrderByCreatedAtDesc(guildId))

    fun providers(
        guildId: Long,
        audience: String,
    ): List<ProviderCapabilityResponse> = mapper.providers(providerCapabilities.findByGuildId(guildId), DashboardAudience.from(audience))

    fun modelMap(guildId: Long): List<ModelMapResponse> =
        mapper.modelMap(providerCapabilities.findByGuildId(guildId), modelChannelUsage(guildId))

    fun knowledgeSpaces(guildId: Long): List<KnowledgeSpaceResponse> =
        knowledgeSpaces.findByGuildId(guildId).map { mapper.knowledgeSpace(it) }

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
            mapper.publishedPreset(it)
        }

    private fun modelChannelUsage(guildId: Long): Map<String, Set<Long>> {
        val usage = linkedMapOf<String, MutableSet<Long>>()
        routingPolicies.findByGuildId(guildId).forEach { policy ->
            val models = listOfNotNull(policy.preferredModel) + mapper.splitCsv(policy.allowedModels)
            models
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { model -> usage.getOrPut(model) { linkedSetOf() }.add(policy.channelId) }
        }
        return usage
    }
}
