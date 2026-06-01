package com.discordassistant.central.dashboard

import com.discordassistant.central.network.ChannelAiRoutingPolicyService
import com.discordassistant.central.policy.PolicyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai-network/channel-ai-routing")
class ChannelAiRoutingPolicyController(
    private val routingPolicies: ChannelAiRoutingPolicyService,
    private val guildPolicy: PolicyService,
) {
    @PostMapping("/{guildId}/{channelId}")
    fun save(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: SaveChannelAiRoutingPolicyRequest,
    ): Map<String, Any?> {
        val saved =
            routingPolicies.save(
                guildId = guildId,
                channelId = channelId,
                responseMode = request.responseMode,
                preferredModel = request.preferredModel,
                allowedModels = request.allowedModels,
                minQualityTier = request.minQualityTier,
                maxCandidates = request.maxCandidates,
                providerTagFilter = request.providerTagFilter,
                costGuard = request.costGuard,
            )
        return mapOf(
            "id" to saved.id,
            "responseMode" to saved.responseMode,
            "preferredModel" to saved.preferredModel,
            "allowedModels" to saved.allowedModels,
            "costGuard" to saved.costGuard,
        )
    }

    @GetMapping("/{guildId}/{channelId}")
    fun effective(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
    ): Map<String, Any?> {
        val effective = routingPolicies.effective(guildId, channelId, guildPolicy.guildDefaultModel(guildId))
        return mapOf(
            "responseMode" to effective.responseMode,
            "preferredModel" to effective.preferredModel,
            "allowedModels" to effective.allowedModels,
            "minQualityTier" to effective.minQualityTier,
            "maxCandidates" to effective.maxCandidates,
            "providerTagFilter" to effective.providerTagFilter,
            "costGuard" to effective.costGuard,
        )
    }

    @GetMapping("/{guildId}")
    fun list(
        @PathVariable guildId: Long,
    ): List<Map<String, Any?>> =
        routingPolicies.list(guildId).map {
            mapOf(
                "channelId" to it.channelId,
                "responseMode" to it.responseMode,
                "preferredModel" to it.preferredModel,
                "allowedModels" to it.allowedModels,
                "minQualityTier" to it.minQualityTier,
                "maxCandidates" to it.maxCandidates,
            )
        }
}

data class SaveChannelAiRoutingPolicyRequest(
    val responseMode: String = "balanced",
    val preferredModel: String? = null,
    val allowedModels: List<String> = emptyList(),
    val minQualityTier: String = "standard",
    val maxCandidates: Int = 1,
    val providerTagFilter: List<String> = emptyList(),
    val costGuard: String = "provider_safe",
)
