package com.discordassistant.central.network

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AiNetworkFeatureGate(
    @param:Value("\${central.ai-network.enabled:true}") private val aiNetworkEnabled: Boolean = true,
    @param:Value("\${central.ai-network.dashboard-enabled:true}") private val dashboardEnabled: Boolean = true,
    @param:Value("\${central.ai-network.presets-enabled:true}") private val presetsEnabled: Boolean = true,
    @param:Value("\${central.ai-network.rag-enabled:true}") private val ragEnabled: Boolean = true,
    @param:Value("\${central.ai-network.multi-response-enabled:true}") private val multiResponseEnabled: Boolean = true,
    @param:Value("\${central.ai-network.channel-ai-enabled:true}") private val channelAiEnabled: Boolean = true,
    @param:Value("\${central.ai-network.kill-switch:false}") private val killSwitch: Boolean = false,
) {
    fun snapshot(): AiNetworkFeatureSnapshot =
        AiNetworkFeatureSnapshot(
            aiNetwork = available(aiNetworkEnabled),
            dashboard = available(dashboardEnabled),
            presets = available(presetsEnabled),
            rag = available(ragEnabled),
            multiResponse = available(multiResponseEnabled),
            channelAi = available(channelAiEnabled),
            killSwitch = killSwitch,
        )

    fun requireDashboardEnabled() = requireFeature("AI Network dashboard", dashboardEnabled)

    fun requirePresetEnabled() = requireFeature("AI preset registry", presetsEnabled)

    fun requireRagEnabled() = requireFeature("AI Network RAG", ragEnabled)

    fun requireMultiResponseEnabled() = requireFeature("multi-response", multiResponseEnabled)

    fun requireChannelAiEnabled() = requireFeature("channel AI customization", channelAiEnabled)

    private fun requireFeature(
        name: String,
        flag: Boolean,
    ) {
        if (killSwitch || !aiNetworkEnabled || !flag) {
            throw IllegalStateException("$name is disabled by AI Network feature gate")
        }
    }

    private fun available(flag: Boolean): Boolean = aiNetworkEnabled && flag && !killSwitch
}

data class AiNetworkFeatureSnapshot(
    val aiNetwork: Boolean,
    val dashboard: Boolean,
    val presets: Boolean,
    val rag: Boolean,
    val multiResponse: Boolean,
    val channelAi: Boolean,
    val killSwitch: Boolean,
)
