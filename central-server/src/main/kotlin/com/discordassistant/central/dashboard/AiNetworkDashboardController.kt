package com.discordassistant.central.dashboard

import com.discordassistant.central.network.AiNetworkFoundationService
import com.discordassistant.central.network.AiQualityFeedbackService
import com.discordassistant.central.network.ModelQualitySummary
import com.discordassistant.central.network.ProviderSafetyDashboard
import com.discordassistant.central.network.ProviderSafetyExecutionPlan
import com.discordassistant.central.network.ProviderSafetyService
import com.discordassistant.central.network.QualitySummary
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
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
    private val qualityFeedback: AiQualityFeedbackService,
    private val providerSafety: ProviderSafetyService,
    private val channelAis: ChannelAiRepository,
    private val behaviorVersions: AiBehaviorVersionRepository,
    private val routingPolicies: ChannelAiRoutingPolicyRepository,
    private val multiResponsePolicies: MultiResponsePolicyRepository,
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val knowledgeSpaces: KnowledgeSpaceRepository,
    private val knowledgeSources: KnowledgeSourceRepository,
    private val presets: AiPresetRepository,
    private val publishedPresets: PublishedPresetRepository,
    private val presetImports: PresetImportRepository,
) {
    @GetMapping("/{guildId}/dashboard")
    fun dashboard(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
        @RequestParam(defaultValue = "balanced") responseMode: String = "balanced",
        @RequestParam(defaultValue = "1") requestedCandidates: Int = 1,
    ): AiNetworkDashboardResponse =
        AiNetworkDashboardResponse(
            overview = overview(guildId),
            channels = channels(guildId),
            providers = providers(guildId, audience),
            modelMap = modelMap(guildId),
            knowledgeSpaces = knowledgeSpaces(guildId),
            presets = guildPresets(guildId),
            publishedPresets = publishedPresets().take(10),
            quality = qualityFeedback.guildSummary(guildId),
            modelQuality = qualityFeedback.modelQuality(guildId),
            overload = providerSafety.overloadAlerts(guildId),
            executionPlan = providerSafety.executionPlan(guildId, responseMode, requestedCandidates),
        )

    @GetMapping("/{guildId}/overview")
    fun overview(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "true") refresh: Boolean = true,
    ): AiNetworkOverviewResponse {
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

    @GetMapping("/{guildId}/channels")
    fun channels(
        @PathVariable guildId: Long,
    ): List<ChannelAiCardResponse> =
        channelAis.findByGuildId(guildId).map { channelAi ->
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
            )
        }

    @GetMapping("/{guildId}/providers")
    fun providers(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): List<ProviderCapabilityResponse> {
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
    ): List<KnowledgeSpaceResponse> =
        knowledgeSpaces.findByGuildId(guildId).map {
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

    @GetMapping("/{guildId}/presets")
    fun guildPresets(
        @PathVariable guildId: Long,
    ): Map<String, Any> =
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

    @GetMapping("/presets/published")
    fun publishedPresets(): List<PublishedPresetResponse> =
        publishedPresets.findByStatusOrderByLikeCountDescPublishedAtDesc("published").map {
            PublishedPresetResponse(
                id = it.id,
                title = it.title,
                description = it.description,
                publisherGuildId = it.publisherGuildId,
                likeCount = it.likeCount,
                importCount = it.importCount,
                reportCount = it.reportCount,
                publishedAt = it.publishedAt.toString(),
            )
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
    val overview: AiNetworkOverviewResponse,
    val channels: List<ChannelAiCardResponse>,
    val providers: List<ProviderCapabilityResponse>,
    val modelMap: List<ModelMapResponse>,
    val knowledgeSpaces: List<KnowledgeSpaceResponse>,
    val presets: Map<String, Any>,
    val publishedPresets: List<PublishedPresetResponse>,
    val quality: QualitySummary,
    val modelQuality: List<ModelQualitySummary>,
    val overload: ProviderSafetyDashboard,
    val executionPlan: ProviderSafetyExecutionPlan,
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

private enum class DashboardAudience(
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
    val title: String,
    val description: String?,
    val publisherGuildId: Long,
    val likeCount: Int,
    val importCount: Int,
    val reportCount: Int,
    val publishedAt: String,
)
