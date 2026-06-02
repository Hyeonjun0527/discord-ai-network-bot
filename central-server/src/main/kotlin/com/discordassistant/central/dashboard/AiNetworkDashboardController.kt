package com.discordassistant.central.dashboard

import com.discordassistant.central.network.AiNetworkFeatureGate
import com.discordassistant.central.network.AiNetworkFoundationService
import com.discordassistant.central.network.AiNetworkGrowthPlan
import com.discordassistant.central.network.AiNetworkGrowthService
import com.discordassistant.central.network.AiQualityFeedbackService
import com.discordassistant.central.network.ModelQualitySummary
import com.discordassistant.central.network.MultiResponseOperationsSummary
import com.discordassistant.central.network.MultiResponseService
import com.discordassistant.central.network.NetworkGrowthEventCard
import com.discordassistant.central.network.ProviderSafetyDashboard
import com.discordassistant.central.network.ProviderSafetyExecutionPlan
import com.discordassistant.central.network.ProviderSafetyService
import com.discordassistant.central.network.QualityReviewSummary
import com.discordassistant.central.network.QualitySummary
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalEntity
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.AiPresetRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.persistence.PresetImportRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.persistence.PublishedPresetRepository
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
    private val growth: AiNetworkGrowthService,
    private val qualityFeedback: AiQualityFeedbackService,
    private val providerSafety: ProviderSafetyService,
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
    private val multiResponse: MultiResponseService,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    @GetMapping("/{guildId}/dashboard")
    fun dashboard(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
        @RequestParam(defaultValue = "balanced") responseMode: String = "balanced",
        @RequestParam(defaultValue = "1") requestedCandidates: Int = 1,
    ): AiNetworkDashboardResponse {
        featureGate.requireDashboardEnabled()
        val overview = overview(guildId)
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
        val multiResponseOperations =
            MultiResponseOperationsDashboardResponse.from(
                if (featureSnapshot.multiResponseDashboard) {
                    multiResponse.operationsSummary(guildId)
                } else {
                    MultiResponseOperationsSummary.disabled(guildId)
                },
                visibility,
            )
        val growthPlan = growth.growthPlan(guildId)
        val growthTimeline = growth.timelineCards(guildId).take(5)
        val readiness = readiness(overview, channels, providers, modelMap, knowledgeSpaces, quality, rawOverload)
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
            nextActions = nextActions(overview, channels, modelMap, knowledgeSpaces, quality, rawOverload, changeApproval, growthPlan),
        )
    }

    @GetMapping("/{guildId}/launch-checklist")
    fun launchChecklist(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "admin") audience: String = "admin",
    ): AiNetworkLaunchChecklistResponse {
        featureGate.requireDashboardEnabled()
        val dashboard = dashboard(guildId, audience = audience)
        val readinessItems =
            dashboard.readiness.areas.map {
                AiNetworkLaunchChecklistItemResponse(
                    key = it.key,
                    title = it.title,
                    status = it.status,
                    evidence = it.evidence,
                    nextAction = it.nextAction,
                    blocking = it.status == "blocked",
                )
            }
        val featureSnapshot = featureGate.snapshot()
        val featureBaseReady =
            featureSnapshot.aiNetwork &&
                featureSnapshot.dashboard &&
                featureSnapshot.channelAi &&
                featureSnapshot.presets &&
                featureSnapshot.rag &&
                !featureSnapshot.killSwitch
        val advancedLimited =
            !featureSnapshot.multiResponse ||
                !featureSnapshot.multiResponseDashboard ||
                !featureSnapshot.multiResponseSynthesis ||
                !featureSnapshot.multiResponseRag ||
                featureSnapshot.multiResponseMaxFanout <= 1
        val safetyItems =
            listOf(
                checklistItem(
                    key = "feature_flags",
                    title = "기능 플래그/kill switch",
                    passed = featureBaseReady && !advancedLimited,
                    warning = featureBaseReady && advancedLimited,
                    evidence =
                        listOf(
                            "aiNetwork=${featureSnapshot.aiNetwork}",
                            "dashboard=${featureSnapshot.dashboard}",
                            "channelAi=${featureSnapshot.channelAi}",
                            "presets=${featureSnapshot.presets}",
                            "rag=${featureSnapshot.rag}",
                            "multi=${featureSnapshot.multiResponse}",
                            "multiDashboard=${featureSnapshot.multiResponseDashboard}",
                            "synthesis=${featureSnapshot.multiResponseSynthesis}",
                            "multiRag=${featureSnapshot.multiResponseRag}",
                            "maxFanout=${featureSnapshot.multiResponseMaxFanout}",
                            "killSwitch=${featureSnapshot.killSwitch}",
                        ),
                    nextAction = "ENV_FILE 의 AI_NETWORK_* 플래그와 maxFanout 가 의도한 운영값인지 확인하세요.",
                ),
                checklistItem(
                    key = "provider_overload",
                    title = "Provider 과부하 보호",
                    passed = dashboard.overload.highRiskCount == 0,
                    warning = dashboard.overload.highRiskCount > 0 && dashboard.overload.safeOnlineProviderCount > 0,
                    evidence =
                        listOf(
                            "highRisk=${dashboard.overload.highRiskCount}",
                            "safeOnline=${dashboard.overload.safeOnlineProviderCount}",
                        ),
                    nextAction = "후보 수/깊은 답변/다중응답을 낮추고 과부하 Provider를 쉬게 하세요.",
                ),
                checklistItem(
                    key = "dashboard_projection",
                    title = "대시보드 Projection 최신성",
                    passed = !dashboard.metadata.stale,
                    warning = dashboard.metadata.stale,
                    evidence = listOf("freshness=${dashboard.metadata.freshnessStatus}", "source=${dashboard.metadata.source}"),
                    nextAction = dashboard.metadata.degradedReason ?: "projection freshness를 확인하세요.",
                ),
                checklistItem(
                    key = "unsafe_quality_reports",
                    title = "품질 신고 검토",
                    passed = dashboard.quality.openReports == 0,
                    warning = dashboard.quality.openReports > 0,
                    evidence = listOf("openReports=${dashboard.quality.openReports}", "feedback=${dashboard.quality.feedbackCount}"),
                    nextAction = "열린 품질 신고를 resolved/dismissed 로 정리하세요.",
                ),
                checklistItem(
                    key = "change_approval_queue",
                    title = "AI 설정 변경 승인 대기열",
                    passed = dashboard.changeApproval.pendingCount == 0 && dashboard.changeApproval.staleCount == 0,
                    warning = dashboard.changeApproval.pendingCount > 0,
                    evidence = listOf("pending=${dashboard.changeApproval.pendingCount}", "stale=${dashboard.changeApproval.staleCount}"),
                    nextAction = "승인/거절되지 않은 AI 설정 변경을 처리하세요.",
                ),
                checklistItem(
                    key = "multi_response_safety",
                    title = "다중응답 안전 게이트",
                    passed = dashboard.multiResponseOperations.safeToEnableAdvanced,
                    warning = !dashboard.multiResponseOperations.safeToEnableAdvanced,
                    evidence =
                        listOf(
                            "status=${dashboard.multiResponseOperations.status}",
                            "riskCodes=${dashboard.multiResponseOperations.riskCodes.joinToString(",")}",
                        ),
                    nextAction = dashboard.multiResponseOperations.nextActions.firstOrNull() ?: "다중응답 운영 상태를 점검하세요.",
                ),
            )
        val items = readinessItems + safetyItems
        val blocked = items.count { it.status == "blocked" }
        val warnings = items.count { it.status == "warning" }
        val status =
            when {
                blocked > 0 -> "blocked"
                warnings > 0 -> "warning"
                else -> "ready"
            }
        return AiNetworkLaunchChecklistResponse(
            guildId = guildId,
            status = status,
            score = ((items.count { it.status == "ready" }.toDouble() / items.size.coerceAtLeast(1)) * 100).toInt(),
            readyCount = items.count { it.status == "ready" },
            warningCount = warnings,
            blockedCount = blocked,
            items = items,
            releaseGate = if (blocked == 0) "pass" else "fail",
            nextActions =
                items
                    .filter { it.status != "ready" }
                    .take(8)
                    .map { it.nextAction },
        )
    }

    @GetMapping("/{guildId}/overview")
    fun overview(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "true") refresh: Boolean = true,
    ): AiNetworkOverviewResponse {
        featureGate.requireDashboardEnabled()
        val profile = foundation.ensureNetworkProfile(guildId)
        val overview =
            if (refresh) {
                foundation.refreshOverview(guildId)
            } else {
                foundation.currentOverview(guildId) ?: foundation.refreshOverview(guildId)
            }
        return AiNetworkOverviewResponse.from(
            guildId = profile.guildId,
            displayName = profile.displayName,
            tagline = profile.tagline,
            overview = overview,
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
        return readiness(
            overview = overview,
            channels = channels(guildId),
            providers = providers(guildId, audience),
            modelMap = modelMap(guildId),
            knowledgeSpaces = knowledgeSpaces(guildId),
            quality = qualityFeedback.guildSummary(guildId),
            overload = providerSafety.overloadAlerts(guildId),
        )
    }

    @GetMapping("/{guildId}/channels")
    fun channels(
        @PathVariable guildId: Long,
    ): List<ChannelAiCardResponse> {
        featureGate.requireDashboardEnabled()
        return channelAis.findByGuildId(guildId).map { channelAi ->
            val behavior = channelAi.activeBehaviorVersionId?.let { behaviorVersions.findByChannelAiIdAndId(channelAi.id, it) }
            val route = routingPolicies.findByGuildIdAndChannelId(guildId, channelAi.channelId)
            val spaces = knowledgeSpaces.findByGuildIdAndChannelId(guildId, channelAi.channelId)
            val indexedSources =
                spaces.sumOf { space ->
                    knowledgeSources
                        .findByKnowledgeSpaceId(space.id)
                        .count { it.status == "indexed" }
                }
            val blockedSources =
                spaces.sumOf { space ->
                    knowledgeSources
                        .findByKnowledgeSpaceId(space.id)
                        .count {
                            it.status.startsWith("blocked") ||
                                it.riskLevel in BLOCKING_KNOWLEDGE_RISKS
                        }
                }
            val knowledgeReadiness =
                when {
                    indexedSources > 0 && blockedSources == 0 -> "ready"
                    indexedSources > 0 -> "partial"
                    blockedSources > 0 -> "needs_review"
                    spaces.any { it.status == "pending_index" } -> "indexing_needed"
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
                        compareBy<ChannelAiCardResponse> { readinessRank(it.readinessStatus) }
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
        val all = proposals.findByGuildIdOrderByCreatedAtDesc(guildId)
        val pending = all.filter { it.status == "pending" }
        val stale = all.filter { it.status == "stale" }
        val rejected = all.filter { it.status == "rejected" }
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
            pendingItems = pending.take(10).map { ChannelAiChangeApprovalItemResponse.from(it) },
            nextActions =
                buildList {
                    if (pending.isNotEmpty()) add("pending AI 설정 변경을 승인하거나 거절하세요.")
                    if (stale.isNotEmpty()) add("stale 변경 제안은 새 제안으로 다시 생성하세요.")
                    if (rejected.isNotEmpty()) add("거절 사유를 반영한 새 행동 버전을 제안하세요.")
                    if (isEmpty()) add("검토 대기 중인 AI 설정 변경은 없습니다.")
                },
        )
    }

    @GetMapping("/{guildId}/providers")
    fun providers(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): List<ProviderCapabilityResponse> {
        featureGate.requireDashboardEnabled()
        val visibility = DashboardAudience.from(audience)
        return providerCapabilities.findByGuildId(guildId).mapIndexed { index, provider ->
            ProviderCapabilityResponse(
                providerUserId = if (visibility.canSeeProviderIdentity) provider.providerUserId else null,
                providerLabel = if (visibility.canSeeProviderIdentity) "provider:${provider.providerUserId}" else "Provider ${index + 1}",
                state = visibility.state(provider.providerState),
                modelCount = provider.modelCount,
                models = splitCsv(provider.modelNames),
                tags = splitCsv(provider.capabilityTags),
                qualityTier = provider.qualityTier,
                maxBurden = provider.maxBurden,
                maxConcurrency = if (visibility.canSeeProviderCapacity) provider.maxConcurrency else null,
                dailyLimit = if (visibility.canSeeProviderCapacity) provider.dailyLimit else null,
                overloadRisk = visibility.risk(provider.overloadRisk),
                lastSeenAt = if (visibility.canSeeProviderCapacity) provider.lastSeenAt?.toString() else null,
            )
        }
    }

    @GetMapping("/{guildId}/model-map")
    fun modelMap(
        @PathVariable guildId: Long,
    ): List<ModelMapResponse> {
        featureGate.requireDashboardEnabled()
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
                    onlineProviderCount = providers.count { it.providerState.equals("ONLINE", ignoreCase = true) },
                    protectedProviderCount = providers.count { it.overloadRisk.lowercase() in PROTECTED_OVERLOAD_RISKS },
                    qualityTiers = providers.map { it.qualityTier }.distinct().sortedByDescending { qualityRank(it) },
                    maxBurdens = providers.map { it.maxBurden }.distinct().sortedByDescending { burdenRank(it) },
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

    @GetMapping("/{guildId}/knowledge-spaces")
    fun knowledgeSpaces(
        @PathVariable guildId: Long,
    ): List<KnowledgeSpaceResponse> {
        featureGate.requireDashboardEnabled()
        return knowledgeSpaces.findByGuildId(guildId).map {
            KnowledgeSpaceResponse(
                id = it.id,
                channelId = it.channelId,
                channelAiId = it.channelAiId,
                name = it.displayName,
                status = it.status,
                sourceCount = it.sourceCount,
                chunkCount = it.chunkCount,
                embeddingModel = it.embeddingModel,
                indexName = it.indexName,
                updatedAt = it.updatedAt.toString(),
            )
        }
    }

    @GetMapping("/{guildId}/presets")
    fun guildPresets(
        @PathVariable guildId: Long,
    ): Map<String, Any> {
        featureGate.requireDashboardEnabled()
        return mapOf(
            "guildId" to guildId,
            "local" to
                presets.findByGuildId(guildId).map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "summary" to it.summary,
                        "category" to it.category,
                        "visibility" to it.visibility,
                        "status" to it.status,
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
    }

    @GetMapping("/presets/published")
    fun publishedPresets(): List<PublishedPresetResponse> {
        featureGate.requireDashboardEnabled()
        return publishedPresets.findByStatusOrderByLikeCountDescPublishedAtDesc("published").map {
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
    }

    private fun readiness(
        overview: AiNetworkOverviewResponse,
        channels: List<ChannelAiCardResponse>,
        providers: List<ProviderCapabilityResponse>,
        modelMap: List<ModelMapResponse>,
        knowledgeSpaces: List<KnowledgeSpaceResponse>,
        quality: QualitySummary,
        overload: ProviderSafetyDashboard,
        changeApproval: ChannelAiChangeApprovalDashboardResponse = changeApproval(overview.guildId),
    ): AiNetworkReadinessResponse {
        val hasKnowledge =
            knowledgeSpaces.any { it.status == "ready" || it.sourceCount > 0 } ||
                channels.any { it.knowledgeSpaceCount > 0 || it.indexedKnowledgeSourceCount > 0 }
        val areas =
            listOf(
                readinessArea(
                    key = "providers",
                    title = "Provider 상태",
                    status =
                        when {
                            overview.onlineProviderCount == 0 -> "blocked"
                            overload.highRiskCount > 0 -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            overview.onlineProviderCount == 0 -> 0
                            overload.highRiskCount > 0 -> 60
                            else -> 100
                        },
                    evidence =
                        listOf(
                            "online=${overview.onlineProviderCount}",
                            "approved=${overview.approvedProviderCount}",
                            "safeOnline=${overload.safeOnlineProviderCount}",
                        ),
                    nextAction =
                        when {
                            overview.onlineProviderCount == 0 -> "Provider 참여 안내로 최소 1대의 PC를 연결하세요."
                            overload.highRiskCount > 0 -> "과부하 Provider를 보호하고 후보 수/응답 모드를 낮추세요."
                            else -> "Provider 기반이 준비됐습니다."
                        },
                ),
                readinessArea(
                    key = "models",
                    title = "모델 지도",
                    status =
                        when {
                            modelMap.isEmpty() -> "blocked"
                            modelMap.size < 2 -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            modelMap.isEmpty() -> 0
                            modelMap.size < 2 -> 65
                            else -> 100
                        },
                    evidence = listOf("models=${modelMap.size}", "mappedChannels=${modelMap.sumOf { it.channelCount }}"),
                    nextAction =
                        if (modelMap.size < 2) {
                            "다른 모델을 가진 Provider를 늘리거나 채널별 선호 모델을 지정하세요."
                        } else {
                            "모델 다양성이 충분합니다."
                        },
                ),
                readinessArea(
                    key = "channel_ai",
                    title = "채널 AI 프로필",
                    status =
                        when {
                            channels.isEmpty() -> "blocked"
                            channels.any { it.readinessStatus != "ready" } -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            channels.isEmpty() -> 0
                            channels.any { it.readinessStatus != "ready" } -> 60
                            else -> 100
                        },
                    evidence = listOf("channels=${channels.size}", "ready=${channels.count { it.readinessStatus == "ready" }}"),
                    nextAction =
                        if (channels.any { it.readinessStatus != "ready" }) {
                            "채널프로필 패널에서 역할·말투·모델 정책을 마저 설정하세요."
                        } else {
                            "채널별 AI 프로필이 준비됐습니다."
                        },
                ),
                readinessArea(
                    key = "knowledge",
                    title = "RAG 지식",
                    status =
                        when {
                            !hasKnowledge -> "warning"
                            channels.any { it.blockedKnowledgeSourceCount > 0 } -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            !hasKnowledge -> 45
                            channels.any { it.blockedKnowledgeSourceCount > 0 } -> 60
                            else -> 100
                        },
                    evidence =
                        listOf(
                            "spaces=${knowledgeSpaces.size}",
                            "sources=${knowledgeSpaces.sumOf { it.sourceCount }}",
                            "blocked=${channels.sumOf { it.blockedKnowledgeSourceCount }}",
                        ),
                    nextAction =
                        when {
                            !hasKnowledge -> "README·운영규칙·FAQ를 지식공간에 추가하세요."
                            channels.any { it.blockedKnowledgeSourceCount > 0 } -> "blocked/review 지식 소스를 승인·거절·삭제하세요."
                            else -> "채널 지식 기반이 준비됐습니다."
                        },
                ),
                readinessArea(
                    key = "quality_feedback",
                    title = "품질 피드백",
                    status =
                        when {
                            quality.feedbackCount == 0 -> "warning"
                            quality.openReports > 0 -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            quality.feedbackCount == 0 -> 50
                            quality.openReports > 0 -> 65
                            else -> 100
                        },
                    evidence = listOf("feedback=${quality.feedbackCount}", "openReports=${quality.openReports}"),
                    nextAction =
                        when {
                            quality.feedbackCount == 0 -> "답변 따봉/신고 피드백을 모아 모델 선택 근거를 만드세요."
                            quality.openReports > 0 -> "열린 신고를 검토하고 resolved/dismissed 로 정리하세요."
                            else -> "품질 피드백 기반 개선 루프가 동작합니다."
                        },
                ),
                readinessArea(
                    key = "change_approval",
                    title = "AI 설정 변경 승인",
                    status =
                        when (changeApproval.status) {
                            "blocked" -> "blocked"
                            "needs_review", "warning" -> "warning"
                            else -> "ready"
                        },
                    score =
                        when (changeApproval.status) {
                            "blocked" -> 0
                            "needs_review" -> 55
                            "warning" -> 75
                            else -> 100
                        },
                    evidence =
                        listOf(
                            "pending=${changeApproval.pendingCount}",
                            "stale=${changeApproval.staleCount}",
                            "rejected=${changeApproval.rejectedCount}",
                        ),
                    nextAction = changeApproval.nextActions.firstOrNull() ?: "검토 대기 중인 AI 설정 변경은 없습니다.",
                ),
                readinessArea(
                    key = "provider_safety",
                    title = "Provider 보호",
                    status =
                        when {
                            overload.highRiskCount > 0 && overload.safeOnlineProviderCount == 0 -> "blocked"
                            overload.highRiskCount > 0 -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            overload.highRiskCount > 0 && overload.safeOnlineProviderCount == 0 -> 0
                            overload.highRiskCount > 0 -> 55
                            else -> 100
                        },
                    evidence = listOf("alerts=${overload.alertCount}", "highRisk=${overload.highRiskCount}"),
                    nextAction =
                        if (overload.highRiskCount > 0) {
                            "과부하 알림을 먼저 해소한 뒤 깊은 답변/다중 응답을 켜세요."
                        } else {
                            "Provider 보호 상태가 안정적입니다."
                        },
                ),
                readinessArea(
                    key = "projection",
                    title = "대시보드 Projection",
                    status = if (overview.stale) "warning" else "ready",
                    score = if (overview.stale) 70 else 100,
                    evidence = listOf("freshness=${overview.freshnessStatus}", "source=network_overview_projection"),
                    nextAction =
                        if (overview.stale) {
                            "projection을 새로고침하고 stale 원인을 확인하세요."
                        } else {
                            "읽기 모델이 최신 상태입니다."
                        },
                ),
            )
        val overallScore = areas.map { it.score }.average().toInt()
        val status =
            when {
                areas.any { it.status == "blocked" } -> "blocked"
                areas.any { it.status == "warning" } -> "warning"
                else -> "ready"
            }
        return AiNetworkReadinessResponse(
            guildId = overview.guildId,
            status = status,
            score = overallScore,
            readyAreaCount = areas.count { it.status == "ready" },
            warningAreaCount = areas.count { it.status == "warning" },
            blockedAreaCount = areas.count { it.status == "blocked" },
            areas = areas,
            topNextActions =
                areas
                    .filter { it.status != "ready" }
                    .sortedBy { it.score }
                    .take(5)
                    .map { it.nextAction },
        )
    }

    private fun readinessArea(
        key: String,
        title: String,
        status: String,
        score: Int,
        evidence: List<String>,
        nextAction: String,
    ): AiNetworkReadinessAreaResponse =
        AiNetworkReadinessAreaResponse(
            key = key,
            title = title,
            status = status,
            score = score.coerceIn(0, 100),
            evidence = evidence,
            nextAction = nextAction,
        )

    private fun checklistItem(
        key: String,
        title: String,
        passed: Boolean,
        warning: Boolean,
        evidence: List<String>,
        nextAction: String,
    ): AiNetworkLaunchChecklistItemResponse =
        AiNetworkLaunchChecklistItemResponse(
            key = key,
            title = title,
            status =
                when {
                    passed -> "ready"
                    warning -> "warning"
                    else -> "blocked"
                },
            evidence = evidence,
            nextAction = if (passed) "준비됐습니다." else nextAction,
            blocking = !passed && !warning,
        )

    private fun nextActions(
        overview: AiNetworkOverviewResponse,
        channels: List<ChannelAiCardResponse>,
        modelMap: List<ModelMapResponse>,
        knowledgeSpaces: List<KnowledgeSpaceResponse>,
        quality: QualitySummary,
        overload: ProviderSafetyDashboard,
        changeApproval: ChannelAiChangeApprovalDashboardResponse,
        growthPlan: AiNetworkGrowthPlan,
    ): List<AiNetworkNextActionResponse> =
        buildList {
            if (overview.onlineProviderCount == 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 10,
                        severity = "critical",
                        actionType = "connect_provider",
                        title = "Provider를 먼저 연결하세요",
                        description = "온라인 Provider가 없어 질문을 처리할 로컬 AI가 없습니다. /프로바이더참여 안내로 첫 PC를 연결하세요.",
                        ctaLabel = "Provider 참여 안내 열기",
                        discordCommand = "/프로바이더참여",
                        dashboardPath = "/dashboard/providers",
                    ),
                )
            }
            if (channels.isEmpty()) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 20,
                        severity = "recommended",
                        actionType = "create_channel_ai",
                        title = "채널 AI를 만드세요",
                        description = "채널별 이름·역할·말투가 아직 없습니다. 설정 패널에서 이 채널 AI 프로필을 만들면 네트워크 정체성이 생깁니다.",
                        ctaLabel = "채널 AI 설정",
                        discordCommand = "/채널프로필",
                        dashboardPath = "/dashboard/channels",
                    ),
                )
            }
            if (modelMap.size < 2 && overview.onlineProviderCount > 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 30,
                        severity = "recommended",
                        actionType = "add_model_diversity",
                        title = "모델 다양성을 늘리세요",
                        description = "현재 선택 가능한 모델이 적습니다. 다른 모델을 가진 Provider가 참여하면 질문 유형별 라우팅 품질이 좋아집니다.",
                        ctaLabel = "모델 지도 확인",
                        discordCommand = null,
                        dashboardPath = "/dashboard/model-map",
                    ),
                )
            }
            val hasKnowledge =
                knowledgeSpaces.any { space ->
                    space.status == "ready" || space.sourceCount > 0
                } ||
                    channels.any { channel ->
                        channel.knowledgeSpaceCount > 0 || channel.indexedKnowledgeSourceCount > 0
                    }
            if (!hasKnowledge) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 40,
                        severity = "optional",
                        actionType = "add_knowledge",
                        title = "채널 지식을 추가하세요",
                        description = "README·운영규칙·FAQ를 지식공간에 등록하면 채널 AI가 서버 맥락을 더 잘 반영할 수 있습니다.",
                        ctaLabel = "지식 추가",
                        discordCommand = "/지식추가",
                        dashboardPath = "/dashboard/knowledge",
                    ),
                )
            }
            if (changeApproval.pendingCount > 0 || changeApproval.staleCount > 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 35,
                        severity = if (changeApproval.staleCount > 0) "critical" else "recommended",
                        actionType = "review_ai_changes",
                        title = "AI 설정 변경을 검토하세요",
                        description =
                            "대기 중인 AI 설정 변경 ${changeApproval.pendingCount}건, " +
                                "stale 제안 ${changeApproval.staleCount}건이 있습니다. " +
                                "승인/거절 후에만 채널 AI가 안전하게 바뀝니다.",
                        ctaLabel = "AI 변경 승인 대기열",
                        discordCommand = null,
                        dashboardPath = "/dashboard/channels/approvals",
                    ),
                )
            }
            if (quality.openReports > 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 45,
                        severity = "recommended",
                        actionType = "review_quality_reports",
                        title = "열린 품질 신고를 검토하세요",
                        description = "미처리 신고가 ${quality.openReports}건 있습니다. 신고를 resolved/dismissed 로 정리해야 대시보드 품질 상태를 신뢰할 수 있습니다.",
                        ctaLabel = "품질 신고 검토",
                        discordCommand = null,
                        dashboardPath = "/dashboard/quality/review",
                    ),
                )
            }
            if (quality.feedbackCount == 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 50,
                        severity = "optional",
                        actionType = "collect_feedback",
                        title = "답변 품질 피드백을 모으세요",
                        description = "아직 품질 피드백이 없습니다. 따봉/신고/사유를 모으면 모델 선택과 채널 AI 개선 근거가 생깁니다.",
                        ctaLabel = "품질 대시보드 보기",
                        discordCommand = null,
                        dashboardPath = "/dashboard/quality",
                    ),
                )
            }
            if (overload.highRiskCount > 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 5,
                        severity = "critical",
                        actionType = "protect_providers",
                        title = "Provider 과부하를 먼저 낮추세요",
                        description = "과부하 Provider가 있어 깊은 답변·다중 응답보다 보호 정책이 우선됩니다. 수신정지/절약 모드/후보 수 제한을 확인하세요.",
                        ctaLabel = "과부하 알림 확인",
                        discordCommand = "/내상태",
                        dashboardPath = "/dashboard/providers/overload",
                    ),
                )
            }
            val existingActionTypes = map { it.actionType }.toSet()
            growthPlan.actions
                .filterNot { growthActionCoveredByPrimaryAction(it.key, existingActionTypes) }
                .take(3)
                .forEach { action ->
                    add(
                        AiNetworkNextActionResponse(
                            priority = action.priority + 60,
                            severity = action.severity,
                            actionType = "growth_${action.key}",
                            title = action.title,
                            description = action.description,
                            ctaLabel = "성장 계획 보기",
                            discordCommand = action.command,
                            dashboardPath = action.dashboardPath,
                        ),
                    )
                }
            if (isEmpty()) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 100,
                        severity = "info",
                        actionType = "optimize_network",
                        title = "AI 네트워크가 안정적으로 준비됐어요",
                        description = "Provider·채널 AI·지식·피드백 기반이 갖춰졌습니다. 이제 프리셋 공유나 다중 응답 실험을 단계적으로 켜도 됩니다.",
                        ctaLabel = "고급 기능 검토",
                        discordCommand = null,
                        dashboardPath = "/dashboard/experiments",
                    ),
                )
            }
        }.sortedBy { it.priority }

    private fun growthActionCoveredByPrimaryAction(
        growthKey: String,
        existingActionTypes: Set<String>,
    ): Boolean =
        when (growthKey) {
            "connect_first_provider" -> "connect_provider" in existingActionTypes
            "create_first_channel_ai" -> "create_channel_ai" in existingActionTypes
            "increase_model_diversity" -> "add_model_diversity" in existingActionTypes
            "add_first_knowledge_space" -> "add_knowledge" in existingActionTypes
            "collect_quality_feedback" -> "collect_feedback" in existingActionTypes
            "resolve_provider_overload" -> "protect_providers" in existingActionTypes
            else -> false
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

    private fun readinessRank(value: String): Int =
        when (value) {
            "needs_profile" -> 0
            "needs_knowledge" -> 1
            "needs_model_policy" -> 2
            else -> 3
        }

    private companion object {
        val BLOCKING_KNOWLEDGE_RISKS = setOf("sensitive", "ssrf")
        val PROTECTED_OVERLOAD_RISKS = setOf("high", "critical")
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

    private fun qualityRank(value: String): Int =
        when (value.trim().lowercase()) {
            "specialized" -> 3
            "high" -> 2
            "standard" -> 1
            else -> 0
        }

    private fun burdenRank(value: String): Int =
        when (value.trim().uppercase()) {
            "RESTRICTED" -> 4
            "HEAVY", "DEEP" -> 3
            "STANDARD" -> 2
            "LIGHT" -> 1
            else -> 0
        }

    private fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
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

private data class ModelProviderSnapshot(
    val modelName: String,
    val providerState: String,
    val qualityTier: String,
    val maxBurden: String,
    val overloadRisk: String,
    val tags: List<String>,
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
