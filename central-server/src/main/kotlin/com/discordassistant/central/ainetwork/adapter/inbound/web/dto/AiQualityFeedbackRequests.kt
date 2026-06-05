package com.discordassistant.central.ainetwork.adapter.inbound.web.dto

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
