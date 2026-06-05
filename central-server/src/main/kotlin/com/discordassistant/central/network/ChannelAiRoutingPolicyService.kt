package com.discordassistant.central.network

import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.domain.OverloadRisk
import com.discordassistant.central.domain.ProviderAvailability
import com.discordassistant.central.domain.ResponseMode
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.requestlog.adapter.outbound.persistence.AiRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class ChannelAiRoutingPolicyService(
    private val policies: ChannelAiRoutingPolicyRepository,
    private val channelAis: ChannelAiRepository,
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val feedbacks: AiFeedbackRepository,
    private val requests: AiRequestRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    @Transactional
    fun save(
        guildId: Long,
        channelId: Long,
        responseMode: String,
        preferredModel: String?,
        allowedModels: List<String>,
        minQualityTier: String,
        maxCandidates: Int,
        providerTagFilter: List<String>,
        costGuard: String,
    ): SavedChannelAiRoutingPolicy {
        featureGate.requireChannelAiEnabled()
        val now = Instant.now(clock)
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val policy =
            policies.findByGuildIdAndChannelId(guildId, channelId)
                ?: ChannelAiRoutingPolicyEntity(guildId = guildId, channelId = channelId, createdAt = now)
        policy.channelAiId = channelAi?.id
        policy.responseMode = normalizeResponseMode(responseMode)
        policy.preferredModel = preferredModel?.trim()?.ifBlank { null }
        policy.allowedModels = allowedModels.normalizedCsv()
        policy.minQualityTier = minQualityTier.trim().ifBlank { "standard" }
        policy.maxCandidates = maxCandidates.coerceIn(1, AI_NETWORK_MAX_CANDIDATES)
        policy.providerTagFilter = providerTagFilter.normalizedCsv()
        policy.costGuard = costGuard.trim().ifBlank { "provider_safe" }
        policy.updatedAt = now
        return policies.save(policy).toSavedDto()
    }

    @Transactional(readOnly = true)
    fun effective(
        guildId: Long,
        channelId: Long,
        guildDefaultModel: String?,
    ): EffectiveRoutingPolicy {
        if (!featureGate.snapshot().channelAi) return defaultPolicy(guildDefaultModel)
        val policy = policies.findByGuildIdAndChannelId(guildId, channelId)
        return EffectiveRoutingPolicy(
            responseMode = policy?.responseMode ?: "balanced",
            preferredModel = policy?.preferredModel ?: guildDefaultModel,
            allowedModels = splitCsv(policy?.allowedModels),
            minQualityTier = policy?.minQualityTier ?: "standard",
            maxCandidates = policy?.maxCandidates ?: 1,
            providerTagFilter = splitCsv(policy?.providerTagFilter),
            costGuard = policy?.costGuard ?: "provider_safe",
        )
    }

    fun list(guildId: Long): List<ChannelAiRoutingPolicySummary> {
        featureGate.requireChannelAiEnabled()
        return policies.findByGuildId(guildId).map { it.toSummaryDto() }
    }

    @Transactional(readOnly = true)
    fun resolveModelChoice(
        guildId: Long,
        channelId: Long,
        requestedModel: String?,
        guildDefaultModel: String?,
    ): ModelChoiceDecision {
        val effective = effective(guildId, channelId, guildDefaultModel)
        val allowedModels = effective.allowedModels.toSet()
        val availableModels = availableModelNames(guildId, allowedModels, effective.minQualityTier, effective.providerTagFilter.toSet())
        val requested = requestedModel?.trim()?.ifBlank { null }
        val desired = requested ?: effective.preferredModel?.trim()?.ifBlank { null }
        val selected = desired?.takeIf { availableModels.contains(it) } ?: availableModels.firstOrNull()
        val fallbackReason = fallbackReason(desired, selected, availableModels, allowedModels)
        return ModelChoiceDecision(
            requestedModel = requested,
            preferredModel = effective.preferredModel,
            selectedModel = selected,
            availableModels = availableModels,
            fallbackReason = fallbackReason,
            explanation = explanation(desired, selected, fallbackReason),
            userMessage = userMessage(fallbackReason),
            nextAction = nextAction(fallbackReason),
            responseMode = effective.responseMode,
            costGuard = effective.costGuard,
            requiresAvailableModel =
                effective.preferredModel != null ||
                    allowedModels.isNotEmpty() ||
                    !effective.minQualityTier.equals("standard", ignoreCase = true) ||
                    effective.providerTagFilter.isNotEmpty(),
        )
    }

    @Transactional(readOnly = true)
    fun modelCandidates(
        guildId: Long,
        channelId: Long,
        guildDefaultModel: String?,
    ): ModelCandidateCatalog {
        featureGate.requireChannelAiEnabled()
        val effective = effective(guildId, channelId, guildDefaultModel)
        val allowedModels = effective.allowedModels.toSet()
        val tagFilter = effective.providerTagFilter.toSet()
        val providers = providerCapabilities.findByGuildId(guildId)
        val feedbackSignals = providerFeedbackSignals(guildId)
        val candidates =
            providers
                .flatMap { provider ->
                    val feedback = feedbackSignals[provider.providerUserId] ?: ProviderFeedbackSignal.EMPTY
                    splitCsv(provider.modelNames).map { modelName ->
                        val providerTags = splitCsv(provider.capabilityTags).toSet()
                        val reasons =
                            buildList {
                                if (provider.providerState != ProviderAvailability.ONLINE) add("provider_offline")
                                if (provider.overloadRisk ==
                                    OverloadRisk.CRITICAL
                                ) {
                                    add("provider_critical_overload")
                                }
                                if (provider.qualityTier.rank <
                                    ModelQualityTier.rankOf(effective.minQualityTier)
                                ) {
                                    add("quality_below_minimum")
                                }
                                if (allowedModels.isNotEmpty() && modelName !in allowedModels) add("model_not_allowed")
                                if (tagFilter.isNotEmpty() && providerTags.intersect(tagFilter).isEmpty()) add("provider_tag_mismatch")
                            }
                        ModelCandidate(
                            modelName = modelName,
                            providerUserId = provider.providerUserId,
                            providerState = provider.providerState.wire,
                            qualityTier = provider.qualityTier.wire,
                            maxBurden = provider.maxBurden.name,
                            overloadRisk = provider.overloadRisk.wire,
                            tags = providerTags.sorted(),
                            shadowQualityScore = feedback.shadowScore,
                            feedbackPositive = feedback.positive,
                            feedbackNegative = feedback.negative,
                            feedbackReports = feedback.reports,
                            eligible = reasons.isEmpty(),
                            ineligibleReasons = reasons,
                        )
                    }
                }.sortedWith(
                    compareByDescending<ModelCandidate> { it.eligible }
                        .thenByDescending { it.shadowQualityScore }
                        .thenBy { it.modelName }
                        .thenBy { it.providerUserId },
                )
        val availableModels =
            candidates
                .filter { it.eligible }
                .map { it.modelName }
                .distinct()
                .sorted()
        val modelSummaries = summarizeModelCandidates(candidates, effective, availableModels)
        return ModelCandidateCatalog(
            guildId = guildId,
            channelId = channelId,
            responseMode = effective.responseMode,
            preferredModel = effective.preferredModel,
            allowedModels = effective.allowedModels,
            minQualityTier = effective.minQualityTier,
            providerTagFilter = effective.providerTagFilter,
            availableModels = availableModels,
            unavailableAllowedModels = effective.allowedModels.filter { it !in availableModels }.sorted(),
            safetySummary = modelCandidateSafetySummary(candidates, effective.allowedModels, availableModels),
            modelSummaries = modelSummaries,
            recommendedModel = modelSummaries.firstOrNull { it.recommended }?.modelName,
            candidates = candidates,
        )
    }

    private fun defaultPolicy(guildDefaultModel: String?): EffectiveRoutingPolicy =
        EffectiveRoutingPolicy(
            responseMode = "balanced",
            preferredModel = guildDefaultModel,
            allowedModels = emptyList(),
            minQualityTier = "standard",
            maxCandidates = 1,
            providerTagFilter = emptyList(),
            costGuard = "provider_safe",
        )

    private fun summarizeModelCandidates(
        candidates: List<ModelCandidate>,
        effective: EffectiveRoutingPolicy,
        availableModels: List<String>,
    ): List<ModelCandidateSummary> {
        val preferred = effective.preferredModel?.trim()?.ifBlank { null }
        return candidates
            .groupBy { it.modelName }
            .map { (modelName, modelCandidates) ->
                val eligible = modelCandidates.filter { it.eligible }
                val reasons = modelCandidates.flatMap { it.ineligibleReasons }.distinct().sorted()
                val bestQuality = modelCandidates.maxOfOrNull { ModelQualityTier.rankOf(it.qualityTier) } ?: 0
                val protectedCount = modelCandidates.count { OverloadRisk.normalize(it.overloadRisk) == OverloadRisk.CRITICAL }
                val shadowScore = modelCandidates.filter { it.eligible }.sumOf { it.shadowQualityScore }
                ModelCandidateSummary(
                    modelName = modelName,
                    eligibleProviderCount = eligible.size,
                    totalProviderCount = modelCandidates.size,
                    protectedProviderCount = protectedCount,
                    bestQualityTier = ModelQualityTier.ofRank(bestQuality).wire,
                    shadowQualityScore = shadowScore,
                    feedbackPositive = modelCandidates.sumOf { it.feedbackPositive },
                    feedbackNegative = modelCandidates.sumOf { it.feedbackNegative },
                    feedbackReports = modelCandidates.sumOf { it.feedbackReports },
                    available = modelName in availableModels,
                    preferred = modelName == preferred,
                    recommended = false,
                    blockingReasons = if (eligible.isEmpty()) reasons else emptyList(),
                    tags = modelCandidates.flatMap { it.tags }.distinct().sorted(),
                )
            }.sortedWith(
                compareByDescending<ModelCandidateSummary> { it.preferred && it.available }
                    .thenByDescending { it.available }
                    .thenByDescending { it.eligibleProviderCount }
                    .thenByDescending { ModelQualityTier.rankOf(it.bestQualityTier) }
                    .thenByDescending { it.shadowQualityScore }
                    .thenBy { it.modelName },
            ).markRecommended()
    }

    private fun providerFeedbackSignals(guildId: Long): Map<Long, ProviderFeedbackSignal> {
        val feedbackRows = feedbacks.findTop200ByGuildIdOrderByCreatedAtDesc(guildId)
        val requestIds = feedbackRows.mapNotNull { it.requestId?.takeIf { id -> id.isNotBlank() } }.toSet()
        if (requestIds.isEmpty()) return emptyMap()
        val providerByRequestId =
            requests
                .findByRequestIdIn(requestIds)
                .associate { it.requestId to it.providerId }
        val signals = mutableMapOf<Long, ProviderFeedbackSignal>()
        feedbackRows.forEach { feedback ->
            val requestId = feedback.requestId?.takeIf { it.isNotBlank() } ?: return@forEach
            val providerId = providerByRequestId[requestId] ?: return@forEach
            val current = signals[providerId] ?: ProviderFeedbackSignal.EMPTY
            signals[providerId] = current.plus(feedback.rating ?: 0, feedback.feedbackType)
        }
        return signals
    }

    private fun List<ModelCandidateSummary>.markRecommended(): List<ModelCandidateSummary> =
        mapIndexed { index, summary -> summary.copy(recommended = index == 0 && summary.available) }

    private fun modelCandidateSafetySummary(
        candidates: List<ModelCandidate>,
        allowedModels: List<String>,
        availableModels: List<String>,
    ): String {
        if (availableModels.isNotEmpty()) return "available"
        if (candidates.isEmpty()) return "no_provider_models_reported"
        val scopedCandidates =
            if (allowedModels.isEmpty()) {
                candidates
            } else {
                candidates.filter { it.modelName in allowedModels }
            }
        if (scopedCandidates.isEmpty()) return "allowed_models_not_reported"
        val reasons = scopedCandidates.flatMap { it.ineligibleReasons }.toSet()
        return when {
            reasons.any { it == "provider_critical_overload" } -> "provider_protection_blocks_all_allowed_models"
            reasons.any { it == "provider_offline" } -> "providers_offline_for_allowed_models"
            reasons.any { it == "provider_tag_mismatch" } -> "provider_tag_filter_blocks_allowed_models"
            reasons.any { it == "quality_below_minimum" } -> "quality_policy_blocks_allowed_models"
            reasons.any { it == "model_not_allowed" } -> "model_policy_blocks_all_models"
            else -> "no_eligible_model_candidate"
        }
    }

    private fun availableModelNames(
        guildId: Long,
        allowedModels: Set<String>,
        minQualityTier: String,
        providerTagFilter: Set<String>,
    ): List<String> =
        providerCapabilities
            .findByGuildId(guildId)
            .asSequence()
            .filter { it.providerState == ProviderAvailability.ONLINE }
            .filter { it.overloadRisk != OverloadRisk.CRITICAL }
            .filter { it.qualityTier.rank >= ModelQualityTier.rankOf(minQualityTier) }
            .filter { provider -> providerMatchesTags(provider.capabilityTags, providerTagFilter) }
            .flatMap { splitCsv(it.modelNames).asSequence() }
            .filter { allowedModels.isEmpty() || allowedModels.contains(it) }
            .distinct()
            .sorted()
            .toList()

    private fun providerMatchesTags(
        capabilityTags: String?,
        providerTagFilter: Set<String>,
    ): Boolean =
        providerTagFilter.isEmpty() ||
            splitCsv(capabilityTags)
                .toSet()
                .intersect(providerTagFilter)
                .isNotEmpty()

    private fun fallbackReason(
        desired: String?,
        selected: String?,
        availableModels: List<String>,
        allowedModels: Set<String>,
    ): String? =
        when {
            selected == null -> "no_available_model"
            desired == null -> null
            desired == selected -> null
            allowedModels.isNotEmpty() && !allowedModels.contains(desired) -> "requested_model_not_allowed"
            !availableModels.contains(desired) -> "requested_model_unavailable"
            else -> "fallback_selected"
        }

    private fun explanation(
        desired: String?,
        selected: String?,
        fallbackReason: String?,
    ): String =
        when (fallbackReason) {
            null -> if (selected == null) "사용 가능한 모델이 없습니다." else "요청한 모델을 사용할 수 있어요."
            "requested_model_not_allowed" -> "요청한 모델은 이 채널 정책에서 허용되지 않아 사용 가능한 모델로 대체했어요."
            "requested_model_unavailable" -> "요청한 모델을 처리할 온라인 Provider가 없어 사용 가능한 모델로 대체했어요."
            "no_available_model" -> "현재 채널 정책과 Provider 상태를 만족하는 모델이 없습니다."
            else -> "${desired ?: "선호 모델"} 대신 ${selected ?: "대체 모델"}을 사용합니다."
        }

    private fun userMessage(fallbackReason: String?): String? =
        when (fallbackReason) {
            "no_available_model" ->
                "현재 채널 모델 정책과 Provider 보호 상태를 만족하는 모델이 없어 요청을 보내지 않았습니다. " +
                    "잠시 후 다시 시도하거나 관리자에게 모델 정책을 확인해 달라고 해주세요."
            "requested_model_not_allowed" ->
                "선택한 모델은 이 채널에서 허용되지 않아 사용 가능한 모델로 대체됩니다."
            "requested_model_unavailable" ->
                "선택한 모델을 처리할 온라인 Provider가 없어 사용 가능한 모델로 대체됩니다."
            "fallback_selected" ->
                "선택한 모델 대신 현재 안전하게 사용할 수 있는 모델로 대체됩니다."
            else -> null
        }

    private fun nextAction(fallbackReason: String?): String? =
        when (fallbackReason) {
            "no_available_model" -> "retry_later_or_adjust_channel_model_policy"
            "requested_model_not_allowed" -> "choose_allowed_model_or_update_policy"
            "requested_model_unavailable" -> "choose_available_model_or_wait_for_provider"
            "fallback_selected" -> "review_selected_fallback_model"
            else -> null
        }

    private fun normalizeResponseMode(value: String): String = ResponseMode.normalize(value).wire

    private fun List<String>.normalizedCsv(): String? =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
            .ifBlank { null }

    private fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun ChannelAiRoutingPolicyEntity.toSavedDto(): SavedChannelAiRoutingPolicy =
        SavedChannelAiRoutingPolicy(
            id = id,
            channelAiId = channelAiId,
            responseMode = responseMode,
            preferredModel = preferredModel,
            allowedModels = allowedModels,
            costGuard = costGuard,
        )

    private fun ChannelAiRoutingPolicyEntity.toSummaryDto(): ChannelAiRoutingPolicySummary =
        ChannelAiRoutingPolicySummary(
            channelId = channelId,
            responseMode = responseMode,
            preferredModel = preferredModel,
            allowedModels = allowedModels,
            minQualityTier = minQualityTier,
            maxCandidates = maxCandidates,
        )
}

private data class ProviderFeedbackSignal(
    val positive: Int = 0,
    val negative: Int = 0,
    val reports: Int = 0,
) {
    val shadowScore: Int get() = positive * 10 - negative * 12 - reports * 25

    fun plus(
        rating: Int,
        feedbackType: String,
    ): ProviderFeedbackSignal {
        val isReport = feedbackType.contains("report", ignoreCase = true)
        return copy(
            positive = positive + if (rating > 0 && !isReport) 1 else 0,
            negative = negative + if (rating < 0 && !isReport) 1 else 0,
            reports = reports + if (isReport) 1 else 0,
        )
    }

    companion object {
        val EMPTY = ProviderFeedbackSignal()
    }
}

data class EffectiveRoutingPolicy(
    val responseMode: String,
    val preferredModel: String?,
    val allowedModels: List<String>,
    val minQualityTier: String,
    val maxCandidates: Int,
    val providerTagFilter: List<String>,
    val costGuard: String,
)

data class SavedChannelAiRoutingPolicy(
    val id: Long,
    val channelAiId: Long?,
    val responseMode: String,
    val preferredModel: String?,
    val allowedModels: String?,
    val costGuard: String,
)

data class ChannelAiRoutingPolicySummary(
    val channelId: Long,
    val responseMode: String,
    val preferredModel: String?,
    val allowedModels: String?,
    val minQualityTier: String,
    val maxCandidates: Int,
)

data class ModelCandidateCatalog(
    val guildId: Long,
    val channelId: Long,
    val responseMode: String,
    val preferredModel: String?,
    val allowedModels: List<String>,
    val minQualityTier: String,
    val providerTagFilter: List<String>,
    val availableModels: List<String>,
    val unavailableAllowedModels: List<String> = emptyList(),
    val safetySummary: String = "available",
    val modelSummaries: List<ModelCandidateSummary> = emptyList(),
    val recommendedModel: String? = null,
    val candidates: List<ModelCandidate>,
)

data class ModelCandidateSummary(
    val modelName: String,
    val eligibleProviderCount: Int,
    val totalProviderCount: Int,
    val protectedProviderCount: Int,
    val bestQualityTier: String,
    val shadowQualityScore: Int,
    val feedbackPositive: Int,
    val feedbackNegative: Int,
    val feedbackReports: Int,
    val available: Boolean,
    val preferred: Boolean,
    val recommended: Boolean,
    val blockingReasons: List<String>,
    val tags: List<String>,
)

data class ModelCandidate(
    val modelName: String,
    val providerUserId: Long,
    val providerState: String,
    val qualityTier: String,
    val maxBurden: String,
    val overloadRisk: String,
    val tags: List<String>,
    val shadowQualityScore: Int,
    val feedbackPositive: Int,
    val feedbackNegative: Int,
    val feedbackReports: Int,
    val eligible: Boolean,
    val ineligibleReasons: List<String>,
)

data class ModelChoiceDecision(
    val requestedModel: String?,
    val preferredModel: String?,
    val selectedModel: String?,
    val availableModels: List<String>,
    val fallbackReason: String?,
    val explanation: String,
    val userMessage: String? = null,
    val nextAction: String? = null,
    val responseMode: String,
    val costGuard: String,
    val requiresAvailableModel: Boolean = false,
)
