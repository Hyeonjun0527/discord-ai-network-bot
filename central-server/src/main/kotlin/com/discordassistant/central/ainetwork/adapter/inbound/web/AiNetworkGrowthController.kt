package com.discordassistant.central.ainetwork.adapter.inbound.web

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkLevelStatusResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.GrowthTimelineCardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderJoinedRequest
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderJoinedResponse
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
        return ProviderJoinedResponse.from(request.providerUserId, result, DashboardAudience.from(audience)).toMap()
    }

    @GetMapping("/{guildId}/levels")
    fun levels(
        @PathVariable guildId: Long,
    ): Map<String, Any?> = AiNetworkLevelStatusResponse.from(growth.levelStatus(guildId)).toMap()

    @GetMapping("/{guildId}/plan")
    fun plan(
        @PathVariable guildId: Long,
    ) = growth.growthPlan(guildId)

    @GetMapping("/{guildId}/timeline")
    fun timeline(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): List<Map<String, Any?>> = GrowthTimelineCardResponse.from(growth.timelineCards(guildId), DashboardAudience.from(audience))
}
