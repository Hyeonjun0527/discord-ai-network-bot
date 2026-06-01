package com.discordassistant.central.network

import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class ChannelAiRoutingPolicyService(
    private val policies: ChannelAiRoutingPolicyRepository,
    private val channelAis: ChannelAiRepository,
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val clock: Clock = Clock.systemUTC(),
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
    ): ChannelAiRoutingPolicyEntity {
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
        policy.maxCandidates = maxCandidates.coerceIn(1, 5)
        policy.providerTagFilter = providerTagFilter.normalizedCsv()
        policy.costGuard = costGuard.trim().ifBlank { "provider_safe" }
        policy.updatedAt = now
        return policies.save(policy)
    }

    fun effective(
        guildId: Long,
        channelId: Long,
        guildDefaultModel: String?,
    ): EffectiveRoutingPolicy {
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

    fun list(guildId: Long): List<ChannelAiRoutingPolicyEntity> = policies.findByGuildId(guildId)

    fun resolveModelChoice(
        guildId: Long,
        channelId: Long,
        requestedModel: String?,
        guildDefaultModel: String?,
    ): ModelChoiceDecision {
        val effective = effective(guildId, channelId, guildDefaultModel)
        val allowedModels = effective.allowedModels.toSet()
        val availableModels = availableModels(guildId, allowedModels, effective.minQualityTier)
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
            responseMode = effective.responseMode,
            costGuard = effective.costGuard,
        )
    }

    private fun availableModels(
        guildId: Long,
        allowedModels: Set<String>,
        minQualityTier: String,
    ): List<String> =
        providerCapabilities
            .findByGuildId(guildId)
            .asSequence()
            .filter { it.providerState.equals("ONLINE", ignoreCase = true) }
            .filter { !it.overloadRisk.equals("critical", ignoreCase = true) }
            .filter { qualityRank(it.qualityTier) >= qualityRank(minQualityTier) }
            .flatMap { splitCsv(it.modelNames).asSequence() }
            .filter { allowedModels.isEmpty() || allowedModels.contains(it) }
            .distinct()
            .sorted()
            .toList()

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

    private fun qualityRank(value: String): Int =
        when (value.trim().lowercase()) {
            "specialized" -> 3
            "high" -> 2
            "standard" -> 1
            else -> 0
        }

    private fun normalizeResponseMode(value: String): String =
        when (value.trim().lowercase()) {
            "fast", "빠른", "빠른 답변" -> "fast"
            "deep", "깊은", "깊은 답변" -> "deep"
            "saving", "절약", "절약 모드" -> "saving"
            else -> "balanced"
        }

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

data class ModelChoiceDecision(
    val requestedModel: String?,
    val preferredModel: String?,
    val selectedModel: String?,
    val availableModels: List<String>,
    val fallbackReason: String?,
    val explanation: String,
    val responseMode: String,
    val costGuard: String,
)
