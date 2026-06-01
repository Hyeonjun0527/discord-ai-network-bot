package com.discordassistant.central.dashboard

import com.discordassistant.central.network.AiQualityFeedbackService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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
    ) = feedback.candidateQuality(runId)
}

data class SubmitAiFeedbackRequest(
    val requestId: String? = null,
    val userId: Long? = null,
    val rating: Int? = null,
    val feedbackType: String = "general",
    val reason: String? = null,
)
