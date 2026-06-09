package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.domain.model.AI_NETWORK_MAX_CANDIDATES
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AiNetworkFeatureGate(
    @param:Value("\${central.ai-network.enabled:true}") private val aiNetworkEnabled: Boolean = true,
    @param:Value("\${central.ai-network.dashboard-enabled:true}") private val dashboardEnabled: Boolean = true,
    @param:Value("\${central.ai-network.presets-enabled:true}") private val presetsEnabled: Boolean = true,
    @param:Value("\${central.ai-network.rag-enabled:true}") private val ragEnabled: Boolean = true,
    @param:Value("\${central.ai-network.multi-response-enabled:true}") private val multiResponseEnabled: Boolean = true,
    @param:Value("\${central.ai-network.multi-response-synthesis-enabled:true}") private val multiResponseSynthesisEnabled: Boolean = true,
    @param:Value("\${central.ai-network.multi-response-dashboard-enabled:true}") private val multiResponseDashboardEnabled: Boolean = true,
    @param:Value("\${central.ai-network.multi-response-rag-enabled:true}") private val multiResponseRagEnabled: Boolean = true,
    @param:Value("\${central.ai-network.multi-response-max-fanout:2}") private val multiResponseMaxFanout: Int = 2,
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
            multiResponseSynthesis = available(multiResponseEnabled && multiResponseSynthesisEnabled),
            multiResponseDashboard = available(dashboardEnabled && multiResponseEnabled && multiResponseDashboardEnabled),
            multiResponseRag = available(multiResponseEnabled && ragEnabled && multiResponseRagEnabled),
            multiResponseMaxFanout = maxFanout(),
            channelAi = available(channelAiEnabled),
            killSwitch = killSwitch,
        )

    fun requireDashboardEnabled() = requireFeature("AI Network dashboard", dashboardEnabled)

    fun requirePresetEnabled() = requireFeature("AI preset registry", presetsEnabled)

    fun requireRagEnabled() = requireFeature("AI Network RAG", ragEnabled)

    fun requireMultiResponseEnabled() = requireFeature("multi-response", multiResponseEnabled)

    fun requireMultiResponseSynthesisEnabled() =
        requireFeature("multi-response synthesis", multiResponseEnabled && multiResponseSynthesisEnabled)

    fun requireMultiResponseDashboardEnabled() =
        requireFeature("multi-response dashboard", dashboardEnabled && multiResponseEnabled && multiResponseDashboardEnabled)

    fun requireChannelAiEnabled() = requireFeature("channel AI customization", channelAiEnabled)

    fun canUseMultiResponseRag(): Boolean = available(multiResponseEnabled && ragEnabled && multiResponseRagEnabled)

    fun multiResponseMaxFanout(): Int = maxFanout()

    private fun requireFeature(
        name: String,
        flag: Boolean,
    ) {
        if (killSwitch || !aiNetworkEnabled || !flag) {
            // 어떤 조건으로 막혔는지(킬스위치/네트워크 비활성/개별 플래그) 메시지에 담아 진단을 돕는다(예외 원칙 4).
            throw IllegalStateException(
                "$name is disabled (killSwitch=$killSwitch, aiNetworkEnabled=$aiNetworkEnabled, featureFlag=$flag)",
            )
        }
    }

    private fun available(flag: Boolean): Boolean = aiNetworkEnabled && flag && !killSwitch

    private fun maxFanout(): Int =
        if (available(multiResponseEnabled)) {
            multiResponseMaxFanout.coerceIn(1, AI_NETWORK_MAX_CANDIDATES)
        } else {
            1
        }
}

data class AiNetworkFeatureSnapshot(
    val aiNetwork: Boolean,
    val dashboard: Boolean,
    val presets: Boolean,
    val rag: Boolean,
    val multiResponse: Boolean,
    val multiResponseSynthesis: Boolean,
    val multiResponseDashboard: Boolean,
    val multiResponseRag: Boolean,
    val multiResponseMaxFanout: Int,
    val channelAi: Boolean,
    val killSwitch: Boolean,
)
