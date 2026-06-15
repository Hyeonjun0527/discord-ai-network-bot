package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkLaunchChecklistItemResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkLaunchChecklistResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiRoutingPolicySummaryResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.EffectiveRoutingPolicyResponse
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingPolicySummary
import com.discordassistant.central.ainetwork.application.EffectiveRoutingPolicy
import com.discordassistant.central.ainetwork.application.NetworkLaunchChecklist
import com.discordassistant.central.ainetwork.application.NetworkLaunchChecklistItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AiNetworkWireDtoTest {
    @Test
    fun `launch checklist wire DTO keeps public field names`() {
        val item =
            AiNetworkLaunchChecklistItemResponse(
                key = "provider_pool",
                title = "Provider pool",
                status = "ready",
                evidence = listOf("2 providers online"),
                nextAction = "keep monitoring",
                blocking = false,
            )
        val response =
            AiNetworkLaunchChecklistResponse(
                guildId = 1001L,
                status = "ready",
                score = 92,
                readyCount = 4,
                warningCount = 1,
                blockedCount = 0,
                releaseGate = "pass",
                items = listOf(item),
                nextActions = listOf("announce"),
            )

        assertEquals(1001L, response.guildId)
        assertEquals("provider_pool", response.items.single().key)
        assertFalse(response.items.single().blocking)
    }

    @Test
    fun `launch checklist application model keeps Discord ids and counts`() {
        val item =
            NetworkLaunchChecklistItem(
                key = "privacy",
                title = "Privacy",
                status = "warning",
                evidence = listOf("notice published"),
                nextAction = "recheck copy",
                blocking = false,
            )
        val checklist =
            NetworkLaunchChecklist(
                guildId = 9007199254740993L,
                status = "warning",
                score = 80,
                readyCount = 3,
                warningCount = 1,
                blockedCount = 0,
                releaseGate = "manual-review",
                items = listOf(item),
                nextActions = listOf("review"),
            )

        assertEquals(9007199254740993L, checklist.guildId)
        assertEquals("manual-review", checklist.releaseGate)
        assertEquals("privacy", checklist.items.single().key)
    }

    @Test
    fun `routing policy responses expose unchanged map keys`() {
        val effective =
            EffectiveRoutingPolicyResponse
                .from(
                    EffectiveRoutingPolicy(
                        responseMode = "single",
                        preferredModel = "glm-5.1",
                        allowedModels = listOf("glm-5.1"),
                        minQualityTier = "standard",
                        maxCandidates = 1,
                        providerTagFilter = listOf("cloud"),
                        costGuard = "free",
                    ),
                ).toMap()
        val summaries =
            ChannelAiRoutingPolicySummaryResponse.from(
                listOf(
                    ChannelAiRoutingPolicySummary(
                        channelId = 9001L,
                        responseMode = "single",
                        preferredModel = "glm-5.1",
                        allowedModels = "glm-5.1",
                        minQualityTier = "standard",
                        maxCandidates = 1,
                    ),
                ),
            )

        assertEquals("single", effective["responseMode"])
        assertEquals(listOf("cloud"), effective["providerTagFilter"])
        assertEquals(9001L, summaries.single()["channelId"])
        assertEquals("glm-5.1", summaries.single()["allowedModels"])
    }
}
