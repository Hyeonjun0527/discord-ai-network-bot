package com.discordassistant.central.ainetwork.adapter.inbound.web.dto

data class SaveChannelAiRoutingPolicyRequest(
    val responseMode: String = "balanced",
    val preferredModel: String? = null,
    val allowedModels: List<String> = emptyList(),
    val minQualityTier: String = "standard",
    val maxCandidates: Int = 1,
    val providerTagFilter: List<String> = emptyList(),
    val costGuard: String = "provider_safe",
)
