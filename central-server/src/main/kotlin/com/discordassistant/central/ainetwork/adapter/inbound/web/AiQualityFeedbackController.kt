package com.discordassistant.central.ainetwork.adapter.inbound.web

import com.discordassistant.central.ainetwork.application.AiQualityFeedbackService
import com.discordassistant.central.ainetwork.application.DashboardAudience
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai-network/quality")
class AiQualityFeedbackController(
    private val feedback: AiQualityFeedbackService,
) {
    @PostMapping("/{guildId}/{channelId}/feedback")
    fun submit(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: SubmitAiFeedbackRequest,
    ): Map<String, Any?> {
        val saved =
            feedback.submit(
                guildId = guildId,
                channelId = channelId,
                requestId = request.requestId,
                userId = request.userId,
                rating = request.rating,
                feedbackType = request.feedbackType,
                reason = request.reason,
            )
        return mapOf("id" to saved.id, "status" to saved.status, "rating" to saved.rating)
    }

    @GetMapping("/{guildId}/summary")
    fun guildSummary(
        @PathVariable guildId: Long,
    ) = feedback.guildSummary(guildId)

    @GetMapping("/{guildId}/review-summary")
    fun reviewSummary(
        @PathVariable guildId: Long,
    ) = feedback.reviewSummary(guildId)

    @PostMapping("/{guildId}/feedback/{feedbackId}/review")
    fun resolveFeedback(
        @PathVariable guildId: Long,
        @PathVariable feedbackId: Long,
        @RequestBody request: ResolveAiFeedbackRequest,
    ): Map<String, Any?> {
        val saved =
            feedback.resolveFeedback(
                guildId = guildId,
                feedbackId = feedbackId,
                status = request.status,
                reviewerUserId = request.reviewerUserId,
                resolutionReason = request.resolutionReason,
            )
        return mapOf(
            "id" to saved.id,
            "status" to saved.status,
            "reviewedBy" to saved.reviewedBy,
            "reviewedAt" to saved.reviewedAt,
        )
    }

    @GetMapping("/{guildId}/{channelId}/summary")
    fun channelSummary(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
    ) = feedback.channelSummary(guildId, channelId)

    @GetMapping("/{guildId}/models")
    fun modelQuality(
        @PathVariable guildId: Long,
    ) = feedback.modelQuality(guildId)

    @GetMapping("/runs/{runId}/candidates")
    fun candidateQuality(
        @PathVariable runId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): List<com.discordassistant.central.ainetwork.application.CandidateQualitySummary> {
        val visibility = DashboardAudience.from(audience)
        return feedback.candidateQuality(runId).mapIndexed { index, candidate ->
            candidate.copy(
                providerUserId = if (visibility.canSeeProviderIdentity) candidate.providerUserId else null,
                providerLabel =
                    if (visibility.canSeeProviderIdentity) {
                        candidate.providerUserId?.let { "provider:$it" } ?: "provider:unknown"
                    } else {
                        "Provider ${index + 1}"
                    },
            )
        }
    }
}

data class SubmitAiFeedbackRequest(
    val requestId: String? = null,
    val userId: Long? = null,
    val rating: Int? = null,
    val feedbackType: String = "general",
    val reason: String? = null,
)

data class ResolveAiFeedbackRequest(
    val status: String = "resolved",
    val reviewerUserId: Long? = null,
    val resolutionReason: String? = null,
)
