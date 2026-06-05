package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.domain.model.AI_NETWORK_MAX_CANDIDATES
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
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
    private val resolver: ChannelAiRoutingPolicyResolver = ChannelAiRoutingPolicyResolver(),
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
        policy.responseMode = resolver.normalizeResponseMode(responseMode)
        policy.preferredModel = preferredModel?.trim()?.ifBlank { null }
        policy.allowedModels = resolver.normalizedCsv(allowedModels)
        policy.minQualityTier = minQualityTier.trim().ifBlank { "standard" }
        policy.maxCandidates = maxCandidates.coerceIn(1, AI_NETWORK_MAX_CANDIDATES)
        policy.providerTagFilter = resolver.normalizedCsv(providerTagFilter)
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
        if (!featureGate.snapshot().channelAi) return resolver.defaultPolicy(guildDefaultModel)
        val policy = policies.findByGuildIdAndChannelId(guildId, channelId)
        return EffectiveRoutingPolicy(
            responseMode = policy?.responseMode ?: "balanced",
            preferredModel = policy?.preferredModel ?: guildDefaultModel,
            allowedModels = resolver.splitCsv(policy?.allowedModels),
            minQualityTier = policy?.minQualityTier ?: "standard",
            maxCandidates = policy?.maxCandidates ?: 1,
            providerTagFilter = resolver.splitCsv(policy?.providerTagFilter),
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
        val availableModels =
            resolver.availableModelNames(
                providers = providerCapabilities.findByGuildId(guildId),
                allowedModels = effective.allowedModels.toSet(),
                minQualityTier = effective.minQualityTier,
                providerTagFilter = effective.providerTagFilter.toSet(),
            )
        return resolver.resolveModelChoice(
            effective = effective,
            availableModels = availableModels,
            requestedModel = requestedModel,
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
        return resolver.modelCandidates(
            guildId = guildId,
            channelId = channelId,
            effective = effective,
            providers = providerCapabilities.findByGuildId(guildId),
            feedbackSignals = providerFeedbackSignals(guildId),
        )
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

data class ProviderFeedbackSignal(
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
