package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiFeedbackEntity
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.CandidateAnswerRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class AiQualityFeedbackService(
    private val feedbacks: AiFeedbackRepository,
    private val channelAis: ChannelAiRepository,
    private val candidateAnswers: CandidateAnswerRepository,
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun submit(
        guildId: Long,
        channelId: Long,
        requestId: String?,
        userId: Long?,
        rating: Int?,
        feedbackType: String,
        reason: String?,
    ): AiFeedbackEntity {
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        return feedbacks.save(
            AiFeedbackEntity(
                guildId = guildId,
                channelId = channelId,
                requestId = requestId?.trim()?.ifBlank { null },
                userId = userId,
                channelAiId = channelAi?.id,
                rating = rating?.coerceIn(-1, 1),
                feedbackType = feedbackType.trim().ifBlank { "general" },
                reason = reason?.trim()?.take(500)?.ifBlank { null },
                status = "open",
                createdAt = Instant.now(clock),
            ),
        )
    }

    fun channelSummary(
        guildId: Long,
        channelId: Long,
    ): QualitySummary {
        val recent = feedbacks.findTop20ByGuildIdAndChannelIdOrderByCreatedAtDesc(guildId, channelId)
        return QualitySummary(
            guildId = guildId,
            channelId = channelId,
            feedbackCount = recent.size,
            positive = recent.count { (it.rating ?: 0) > 0 },
            negative = recent.count { (it.rating ?: 0) < 0 },
            reports = recent.count { it.feedbackType.contains("report", ignoreCase = true) },
            recentReasons = recent.mapNotNull { it.reason }.take(5),
        )
    }

    fun modelQuality(guildId: Long): List<ModelQualitySummary> {
        val providers = providerCapabilities.findByGuildId(guildId)
        val modelNames =
            providers
                .flatMap { it.modelNames.orEmpty().split(",") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        return modelNames
            .map { model ->
                val candidates =
                    providers.filter {
                        it.modelNames
                            .orEmpty()
                            .split(",")
                            .map { name -> name.trim() }
                            .contains(model)
                    }
                val avgTier =
                    when {
                        candidates.any { it.qualityTier == "specialized" } -> "specialized"
                        candidates.any { it.qualityTier == "high" } -> "high"
                        else -> "standard"
                    }
                ModelQualitySummary(
                    modelName = model,
                    providerCount = candidates.size,
                    qualityTier = avgTier,
                    overloadRiskCount = candidates.count { it.overloadRisk == "high" },
                )
            }.sortedWith(
                compareByDescending<ModelQualitySummary> { it.providerCount }
                    .thenBy { it.modelName },
            )
    }

    fun candidateQuality(runId: Long): List<CandidateQualitySummary> =
        candidateAnswers.findByRunId(runId).map {
            CandidateQualitySummary(
                candidateId = it.id,
                providerUserId = it.providerUserId,
                modelName = it.modelName,
                status = it.status,
                qualityScore = it.qualityScore,
                safetyFlags =
                    it.safetyFlags
                        .orEmpty()
                        .split(",")
                        .filter { flag -> flag.isNotBlank() },
                latencyMs = it.latencyMs,
            )
        }
}

data class QualitySummary(
    val guildId: Long,
    val channelId: Long,
    val feedbackCount: Int,
    val positive: Int,
    val negative: Int,
    val reports: Int,
    val recentReasons: List<String>,
)

data class ModelQualitySummary(
    val modelName: String,
    val providerCount: Int,
    val qualityTier: String,
    val overloadRiskCount: Int,
)

data class CandidateQualitySummary(
    val candidateId: Long,
    val providerUserId: Long?,
    val modelName: String?,
    val status: String,
    val qualityScore: Int?,
    val safetyFlags: List<String>,
    val latencyMs: Int?,
)
