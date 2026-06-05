package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkFeatureController
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.ainetwork.domain.model.AI_NETWORK_MAX_CANDIDATES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiNetworkFeatureGateTest {
    @Test
    fun `kill switch disables every ai network feature snapshot`() {
        val gate = AiNetworkFeatureGate(killSwitch = true)
        val snapshot = AiNetworkFeatureController(gate).features()

        assertFalse(snapshot.aiNetwork)
        assertFalse(snapshot.dashboard)
        assertFalse(snapshot.presets)
        assertFalse(snapshot.rag)
        assertFalse(snapshot.multiResponse)
        assertFalse(snapshot.multiResponseSynthesis)
        assertFalse(snapshot.multiResponseDashboard)
        assertFalse(snapshot.multiResponseRag)
        assertEquals(1, snapshot.multiResponseMaxFanout)
        assertFalse(snapshot.channelAi)
        assertTrue(snapshot.killSwitch)
        assertThrows(IllegalStateException::class.java) { gate.requireRagEnabled() }
        assertThrows(IllegalStateException::class.java) { gate.requireChannelAiEnabled() }
    }

    @Test
    fun `multi response feature gate exposes synthesis rag and max fanout controls`() {
        val gate =
            AiNetworkFeatureGate(
                multiResponseSynthesisEnabled = false,
                multiResponseRagEnabled = false,
                multiResponseMaxFanout = 99,
            )

        val snapshot = gate.snapshot()

        assertTrue(snapshot.multiResponse)
        assertTrue(snapshot.multiResponseDashboard)
        assertFalse(snapshot.multiResponseSynthesis)
        assertFalse(snapshot.multiResponseRag)
        assertEquals(AI_NETWORK_MAX_CANDIDATES, snapshot.multiResponseMaxFanout)
        assertThrows(IllegalStateException::class.java) { gate.requireMultiResponseSynthesisEnabled() }
        assertFalse(gate.canUseMultiResponseRag())
    }

    @Test
    fun `multi response dashboard gate can stop projections without stopping question fanout`() {
        val gate = AiNetworkFeatureGate(multiResponseDashboardEnabled = false)
        val snapshot = gate.snapshot()

        assertTrue(snapshot.multiResponse)
        assertFalse(snapshot.multiResponseDashboard)
        gate.requireMultiResponseEnabled()
        assertThrows(IllegalStateException::class.java) { gate.requireMultiResponseDashboardEnabled() }
    }
}
