package com.discordassistant.central.network

import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class ChannelAiRoutingPolicyService(
    private val policies: ChannelAiRoutingPolicyRepository,
    private val channelAis: ChannelAiRepository,
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
