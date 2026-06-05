package com.discordassistant.central.ainetwork.adapter.inbound.web.dto

data class ProviderJoinedRequest(
    val providerUserId: Long,
    val modelNames: List<String> = emptyList(),
    val capabilityTags: List<String> = emptyList(),
    val maxBurden: String = "LIGHT",
    val maxConcurrency: Int = 1,
    val dailyLimit: Int = 0,
)
