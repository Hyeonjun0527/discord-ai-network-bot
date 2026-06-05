package com.discordassistant.central.ainetwork.application

// 응답/결과 DTO (행위 분해: 서비스 본체에서 분리, 같은 패키지·시그니처 불변).

data class ProviderGrowthResult(
    val providerCapabilityId: Long,
    val eventId: Long,
    val networkLevel: Int,
)

data class ProviderCapabilitySyncResult(
    val providerCapabilityId: Long,
    val eventId: Long?,
    val networkLevel: Int,
    val changed: Boolean,
)

data class NetworkGrowthEventCard(
    val id: Long,
    val eventType: String,
    val providerUserId: Long?,
    val channelId: Long?,
    val title: String,
    val summary: String?,
    val impactBullets: List<String>,
    val levelBefore: Int?,
    val levelAfter: Int?,
    val createdAt: String,
)

data class AiNetworkLevelStatus(
    val guildId: Long,
    val currentLevel: Int,
    val currentTitle: String,
    val currentDescription: String,
    val nextMilestone: AiNetworkLevelMilestone?,
    val milestones: List<AiNetworkLevelMilestone>,
)

data class AiNetworkGrowthPlan(
    val guildId: Long,
    val currentLevel: Int,
    val targetLevel: Int?,
    val targetTitle: String?,
    val healthStatus: String,
    val summary: String,
    val builderMessage: String,
    val capabilityBasis: List<String>,
    val recommendationPolicy: String,
    val actions: List<AiNetworkGrowthAction>,
)

data class AiNetworkGrowthAction(
    val key: String,
    val priority: Int,
    val severity: String,
    val title: String,
    val description: String,
    val command: String?,
    val dashboardPath: String,
    val unlocksLevel: Int?,
    val requiresAdminApproval: Boolean = false,
    val autoApply: Boolean = false,
)

data class AiNetworkLevelMilestone(
    val level: Int,
    val title: String,
    val description: String,
    val achieved: Boolean,
    val gaps: List<String>,
)
