package com.discordassistant.central.ainetwork.adapter.inbound.web

import com.discordassistant.central.ainetwork.application.AiNetworkGrowthService
import com.discordassistant.central.ainetwork.application.DashboardAudience
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai-network/growth")
class AiNetworkGrowthController(
    private val growth: AiNetworkGrowthService,
) {
    @PostMapping("/{guildId}/provider-joined")
    fun providerJoined(
        @PathVariable guildId: Long,
        @RequestBody request: ProviderJoinedRequest,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): Map<String, Any?> {
        val result =
            growth.recordProviderJoined(
                guildId = guildId,
                providerUserId = request.providerUserId,
                modelNames = request.modelNames,
                capabilityTags = request.capabilityTags,
                maxBurden = request.maxBurden,
                maxConcurrency = request.maxConcurrency,
                dailyLimit = request.dailyLimit,
            )
        val visibility = DashboardAudience.from(audience)
        return buildMap {
            put("providerLabel", providerLabel(request.providerUserId, 0, visibility))
            if (visibility.canSeeProviderIdentity) put("providerCapabilityId", result.providerCapabilityId)
            put("eventId", result.eventId)
            put("networkLevel", result.networkLevel)
        }
    }

    @GetMapping("/{guildId}/levels")
    fun levels(
        @PathVariable guildId: Long,
    ): Map<String, Any?> {
        val status = growth.levelStatus(guildId)
        return mapOf(
            "guildId" to status.guildId,
            "currentLevel" to status.currentLevel,
            "currentTitle" to status.currentTitle,
            "currentDescription" to status.currentDescription,
            "nextMilestone" to status.nextMilestone,
            "milestones" to status.milestones,
        )
    }

    @GetMapping("/{guildId}/plan")
    fun plan(
        @PathVariable guildId: Long,
    ) = growth.growthPlan(guildId)

    @GetMapping("/{guildId}/timeline")
    fun timeline(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): List<Map<String, Any?>> {
        val visibility = DashboardAudience.from(audience)
        return growth.timelineCards(guildId).mapIndexed { index, event ->
            buildMap {
                put("id", event.id)
                put("eventType", event.eventType)
                put("providerLabel", providerLabel(event.providerUserId, index, visibility))
                if (visibility.canSeeProviderIdentity) {
                    put("providerUserId", event.providerUserId)
                }
                put("channelId", event.channelId)
                put("title", event.title)
                put("summary", event.summary)
                put("impactBullets", event.impactBullets)
                put("levelBefore", event.levelBefore)
                put("levelAfter", event.levelAfter)
                put("createdAt", event.createdAt)
            }
        }
    }

    private fun providerLabel(
        providerUserId: Long?,
        index: Int,
        visibility: DashboardAudience,
    ): String =
        if (visibility.canSeeProviderIdentity) {
            providerUserId?.let { "provider:$it" } ?: "network"
        } else {
            "Provider ${index + 1}"
        }
}

data class ProviderJoinedRequest(
    val providerUserId: Long,
    val modelNames: List<String> = emptyList(),
    val capabilityTags: List<String> = emptyList(),
    val maxBurden: String = "LIGHT",
    val maxConcurrency: Int = 1,
    val dailyLimit: Int = 0,
)
