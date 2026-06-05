package com.discordassistant.central.dashboard

import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.network.ChannelAiRoutingPolicyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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

    @GetMapping("/{guildId}/{channelId}/model-candidates")
    fun modelCandidates(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
    ) = routingPolicies.modelCandidates(guildId, channelId, guildPolicy.guildDefaultModel(guildId))

    @GetMapping("/{guildId}/{channelId}/model-choice")
    fun modelChoice(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestParam(required = false) requestedModel: String?,
    ): Map<String, Any?> {
        val decision =
            routingPolicies.resolveModelChoice(
                guildId = guildId,
                channelId = channelId,
                requestedModel = requestedModel,
                guildDefaultModel = guildPolicy.guildDefaultModel(guildId),
            )
        return mapOf(
            "requestedModel" to decision.requestedModel,
            "preferredModel" to decision.preferredModel,
            "selectedModel" to decision.selectedModel,
            "availableModels" to decision.availableModels,
            "fallbackReason" to decision.fallbackReason,
            "explanation" to decision.explanation,
            "userMessage" to decision.userMessage,
            "nextAction" to decision.nextAction,
            "responseMode" to decision.responseMode,
            "costGuard" to decision.costGuard,
            "requiresAvailableModel" to decision.requiresAvailableModel,
            "routingBlocked" to (decision.selectedModel == null && decision.requiresAvailableModel),
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
