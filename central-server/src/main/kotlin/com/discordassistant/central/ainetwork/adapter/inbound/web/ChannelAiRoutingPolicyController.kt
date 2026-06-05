package com.discordassistant.central.ainetwork.adapter.inbound.web

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiRoutingPolicySummaryResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.EffectiveRoutingPolicyResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ModelChoiceDecisionResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.SaveChannelAiRoutingPolicyRequest
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.SavedChannelAiRoutingPolicyResponse
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingPolicyService
import com.discordassistant.central.guild.application.PolicyService
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
        return SavedChannelAiRoutingPolicyResponse.from(saved).toMap()
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
        return ModelChoiceDecisionResponse.from(decision).toMap()
    }

    @GetMapping("/{guildId}/{channelId}")
    fun effective(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
    ): Map<String, Any?> {
        val effective = routingPolicies.effective(guildId, channelId, guildPolicy.guildDefaultModel(guildId))
        return EffectiveRoutingPolicyResponse.from(effective).toMap()
    }

    @GetMapping("/{guildId}")
    fun list(
        @PathVariable guildId: Long,
    ): List<Map<String, Any?>> = ChannelAiRoutingPolicySummaryResponse.from(routingPolicies.list(guildId))
}
