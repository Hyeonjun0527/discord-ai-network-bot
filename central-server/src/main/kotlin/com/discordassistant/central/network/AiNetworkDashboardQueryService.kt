package com.discordassistant.central.network

import com.discordassistant.central.dashboard.ChannelAiCardResponse
import com.discordassistant.central.dashboard.ChannelAiChangeApprovalDashboardResponse
import com.discordassistant.central.dashboard.ChannelAiChangeApprovalItemResponse
import com.discordassistant.central.dashboard.DashboardAudience
import com.discordassistant.central.dashboard.KnowledgeSpaceResponse
import com.discordassistant.central.dashboard.ModelMapResponse
import com.discordassistant.central.dashboard.ProviderCapabilityResponse
import com.discordassistant.central.dashboard.PublishedPresetResponse
import com.discordassistant.central.domain.KnowledgeSpaceStatus
import com.discordassistant.central.domain.ProposalStatus
import com.discordassistant.central.domain.PublishedPresetStatus
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.AiPresetRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.persistence.PresetImportRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.persistence.PublishedPresetRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AI Network 대시보드의 **영속 계층 read·매핑** 책임. 컨트롤러(웹 어댑터)가 리포지토리를 직접
 * 주입/호출하던 god class·클린아키텍처 위반(controller↛persistence)을 제거하기 위해 분리했다.
 * 엔티티→응답 DTO 매핑만 담당하며, readiness/nextActions 등 조합 로직은 컨트롤러가 그대로 한다.
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
) {
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

    fun providers(
        guildId: Long,
        audience: String,
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

    private data class ModelProviderSnapshot(
        val modelName: String,
        val providerState: String,
        val qualityTier: String,
        val maxBurden: String,
        val overloadRisk: String,
        val tags: List<String>,
    )

    private companion object {
        val BLOCKING_KNOWLEDGE_RISKS = setOf("sensitive", "ssrf")
        val PROTECTED_OVERLOAD_RISKS = setOf("high", "critical")
    }
}
