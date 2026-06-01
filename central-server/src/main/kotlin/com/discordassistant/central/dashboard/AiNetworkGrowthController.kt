package com.discordassistant.central.dashboard

import com.discordassistant.central.network.AiNetworkGrowthService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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
        return mapOf(
            "providerCapabilityId" to result.providerCapabilityId,
            "eventId" to result.eventId,
            "networkLevel" to result.networkLevel,
        )
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

    @GetMapping("/{guildId}/timeline")
    fun timeline(
        @PathVariable guildId: Long,
    ): List<Map<String, Any?>> =
        growth.timelineCards(guildId).map {
            mapOf(
                "id" to it.id,
                "eventType" to it.eventType,
                "providerUserId" to it.providerUserId,
                "channelId" to it.channelId,
                "title" to it.title,
                "summary" to it.summary,
                "impactBullets" to it.impactBullets,
                "levelBefore" to it.levelBefore,
                "levelAfter" to it.levelAfter,
                "createdAt" to it.createdAt,
            )
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
