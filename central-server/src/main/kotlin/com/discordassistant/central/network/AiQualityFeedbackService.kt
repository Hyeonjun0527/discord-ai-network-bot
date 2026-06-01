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
        val normalizedRequestId = requestId?.trim()?.ifBlank { null }
        if (normalizedRequestId != null && userId != null) {
            feedbacks.findByGuildIdAndRequestIdAndUserId(guildId, normalizedRequestId, userId)?.let { return it }
        }
        val normalizedFeedbackType = feedbackType.trim().lowercase().ifBlank { "general" }
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        return feedbacks.save(
            AiFeedbackEntity(
                guildId = guildId,
                channelId = channelId,
                requestId = normalizedRequestId,
                userId = userId,
                channelAiId = channelAi?.id,
                rating = rating?.coerceIn(-1, 1),
                feedbackType = normalizedFeedbackType,
                reason = sanitizeReason(reason),
                status = if (normalizedFeedbackType.contains("report")) "needs_review" else "open",
                createdAt = Instant.now(clock),
            ),
        )
    }

    fun channelSummary(
        guildId: Long,
        channelId: Long,
    ): QualitySummary {
        val recent = feedbacks.findTop20ByGuildIdAndChannelIdOrderByCreatedAtDesc(guildId, channelId)
        return summarize(guildId, channelId, recent)
    }

    fun guildSummary(guildId: Long): QualitySummary {
        val recent = feedbacks.findTop50ByGuildIdOrderByCreatedAtDesc(guildId)
        return summarize(guildId, null, recent)
    }

    fun reviewSummary(guildId: Long): QualityReviewSummary {
        val queue = feedbacks.findTop50ByGuildIdAndStatusOrderByCreatedAtDesc(guildId, "needs_review")
        val channelCounts = queue.groupingBy { it.channelId }.eachCount()
        val topChannels =
            channelCounts
                .entries
                .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
                .take(10)
                .map { QualityReviewChannelSummary(channelId = it.key, openReports = it.value) }
        return QualityReviewSummary(
            guildId = guildId,
            openReportCount = queue.size,
            affectedChannelCount = channelCounts.size,
            topChannels = topChannels,
            queue = queue.take(20).map { QualityReviewItem.from(it) },
            nextActions = reviewNextActions(queue.size),
        )
    }

    @Transactional
    fun resolveFeedback(
        guildId: Long,
        feedbackId: Long,
        status: String,
        reviewerUserId: Long?,
        resolutionReason: String?,
    ): AiFeedbackEntity {
        val feedback = feedbacks.findByGuildIdAndId(guildId, feedbackId) ?: error("feedback_not_found")
        feedback.status = normalizeReviewStatus(status)
        feedback.reviewedBy = reviewerUserId
        feedback.reviewedAt = Instant.now(clock)
        feedback.resolutionReason = sanitizeReason(resolutionReason)
        return feedbacks.save(feedback)
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

    private fun summarize(
        guildId: Long,
        channelId: Long?,
        recent: List<AiFeedbackEntity>,
    ): QualitySummary =
        QualitySummary(
            guildId = guildId,
            channelId = channelId,
            feedbackCount = recent.size,
            positive = recent.count { (it.rating ?: 0) > 0 },
            negative = recent.count { (it.rating ?: 0) < 0 },
            reports = recent.count { it.feedbackType.contains("report", ignoreCase = true) },
            openReports = recent.count { it.status == "needs_review" },
            recentReasons = recent.mapNotNull { it.reason }.take(5),
        )

    private fun sanitizeReason(reason: String?): String? =
        reason
            ?.trim()
            ?.replace(SECRET_PATTERN, "[redacted]")
            ?.take(500)
            ?.ifBlank { null }

    private fun normalizeReviewStatus(status: String): String =
        when (status.trim().lowercase()) {
            "resolve", "resolved", "done" -> "resolved"
            "dismiss", "dismissed", "ignore", "ignored" -> "dismissed"
            "needs_review", "reopen", "open" -> "needs_review"
            else -> error("invalid_feedback_review_status")
        }

    private fun reviewNextActions(openReportCount: Int): List<String> =
        if (openReportCount == 0) {
            listOf("열린 신고가 없습니다. 품질 피드백을 계속 수집하세요.")
        } else {
            listOf(
                "신고 내용을 검토해 resolved/dismissed 로 정리하세요.",
                "반복 신고가 있는 채널은 채널 AI 헌법·지식·모델 정책을 점검하세요.",
            )
        }

    private companion object {
        val SECRET_PATTERN =
            Regex(
                pattern = """(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""",
            )
    }
}

data class QualitySummary(
    val guildId: Long,
    val channelId: Long?,
    val feedbackCount: Int,
    val positive: Int,
    val negative: Int,
    val reports: Int,
    val openReports: Int,
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
    val providerLabel: String? = null,
    val modelName: String?,
    val status: String,
    val qualityScore: Int?,
    val safetyFlags: List<String>,
    val latencyMs: Int?,
)

data class QualityReviewSummary(
    val guildId: Long,
    val openReportCount: Int,
    val affectedChannelCount: Int,
    val topChannels: List<QualityReviewChannelSummary>,
    val queue: List<QualityReviewItem>,
    val nextActions: List<String>,
)

data class QualityReviewChannelSummary(
    val channelId: Long,
    val openReports: Int,
)

data class QualityReviewItem(
    val id: Long,
    val channelId: Long,
    val requestId: String?,
    val rating: Int?,
    val feedbackType: String,
    val reason: String?,
    val createdAt: String,
) {
    companion object {
        fun from(entity: AiFeedbackEntity): QualityReviewItem =
            QualityReviewItem(
                id = entity.id,
                channelId = entity.channelId,
                requestId = entity.requestId,
                rating = entity.rating,
                feedbackType = entity.feedbackType,
                reason = entity.reason,
                createdAt = entity.createdAt.toString(),
            )
    }
}
