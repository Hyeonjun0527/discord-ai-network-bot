package com.discordassistant.central.network

import com.discordassistant.central.dashboard.AiNetworkFeatureController
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
        assertTrue(snapshot.killSwitch)
        assertThrows(IllegalStateException::class.java) { gate.requireRagEnabled() }
    }
}
