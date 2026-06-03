package com.discordassistant.central.network

import com.discordassistant.central.domain.FeedbackStatus
import com.discordassistant.central.persistence.AiFeedbackEntity
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.CandidateAnswerRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
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
    ): AiFeedbackResult {
        val normalizedRequestId = sanitizeRequestId(requestId)
        if (normalizedRequestId != null && userId != null) {
            feedbacks.findByGuildIdAndRequestIdAndUserId(guildId, normalizedRequestId, userId)?.let {
                return AiFeedbackResult.from(it)
            }
        }
        val normalizedFeedbackType = sanitizeFeedbackType(feedbackType)
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val saved =
            feedbacks.save(
                AiFeedbackEntity(
                    guildId = guildId,
                    channelId = channelId,
                    requestId = normalizedRequestId,
                    userId = userId,
                    channelAiId = channelAi?.id,
                    rating = rating?.coerceIn(-1, 1),
                    feedbackType = normalizedFeedbackType,
                    reason = sanitizeReason(reason),
                    status = if (normalizedFeedbackType.contains("report")) FeedbackStatus.NEEDS_REVIEW else FeedbackStatus.OPEN,
                    createdAt = Instant.now(clock),
                ),
            )
        return AiFeedbackResult.from(saved)
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
        val queue = feedbacks.findTop50ByGuildIdAndStatusOrderByCreatedAtDesc(guildId, FeedbackStatus.NEEDS_REVIEW)
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
    ): AiFeedbackReviewResult {
        val feedback = feedbacks.findByGuildIdAndId(guildId, feedbackId) ?: error("feedback_not_found")
        feedback.status = normalizeReviewStatus(status)
        feedback.reviewedBy = reviewerUserId
        feedback.reviewedAt = Instant.now(clock)
        feedback.resolutionReason = sanitizeReason(resolutionReason)
        return AiFeedbackReviewResult.from(feedbacks.save(feedback))
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
            openReports = recent.count { it.status == FeedbackStatus.NEEDS_REVIEW },
            recentReasons = recent.mapNotNull { it.reason }.take(5),
        )

    private fun sanitizeReason(reason: String?): String? =
        reason
            ?.trim()
            ?.replace(SECRET_PATTERN, "[redacted]")
            ?.take(500)
            ?.let { if (it.hasSensitiveMaterial()) "[redacted]" else it }
            ?.ifBlank { null }

    private fun sanitizeRequestId(requestId: String?): String? {
        val trimmed = requestId?.trim()?.ifBlank { null } ?: return null
        if (trimmed.hasSensitiveMaterial()) return "redacted-${sha256(trimmed).take(12)}"
        return trimmed.take(160)
    }

    private fun sanitizeFeedbackType(feedbackType: String): String {
        val trimmed = feedbackType.trim()
        if (trimmed.hasSensitiveMaterial()) {
            return if (trimmed.contains("report", ignoreCase = true)) "report" else "general"
        }
        return trimmed
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "general" }
    }

    private fun String.hasSensitiveMaterial(): Boolean =
        KnowledgeSafety.containsSensitiveMaterial(this) || SECRET_PATTERN.containsMatchIn(this)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun normalizeReviewStatus(status: String): FeedbackStatus =
        when (status.trim().lowercase()) {
            "resolve", "resolved", "done" -> FeedbackStatus.RESOLVED
            "dismiss", "dismissed", "ignore", "ignored" -> FeedbackStatus.DISMISSED
            "needs_review", "reopen", "open" -> FeedbackStatus.NEEDS_REVIEW
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

data class AiFeedbackResult(
    val id: Long,
    val status: String,
    val rating: Int?,
) {
    companion object {
        fun from(entity: AiFeedbackEntity): AiFeedbackResult =
            AiFeedbackResult(
                id = entity.id,
                status = entity.status.wire,
                rating = entity.rating,
            )
    }
}

data class AiFeedbackReviewResult(
    val id: Long,
    val status: String,
    val reviewedBy: Long?,
    val reviewedAt: String?,
) {
    companion object {
        fun from(entity: AiFeedbackEntity): AiFeedbackReviewResult =
            AiFeedbackReviewResult(
                id = entity.id,
                status = entity.status.wire,
                reviewedBy = entity.reviewedBy,
                reviewedAt = entity.reviewedAt?.toString(),
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
