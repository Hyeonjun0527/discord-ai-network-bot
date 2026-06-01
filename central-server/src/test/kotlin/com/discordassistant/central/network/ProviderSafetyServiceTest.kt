package com.discordassistant.central.network

import com.discordassistant.central.dashboard.MarkProviderOverloadRequest
import com.discordassistant.central.dashboard.ProviderSafetyController
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.AiNetworkEventRepository
import com.discordassistant.central.persistence.AiNetworkProfileRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProviderSafetyServiceTest
    @Autowired
    constructor(
        private val networkProfiles: AiNetworkProfileRepository,
        private val providerCapabilities: ProviderCapabilityProfileRepository,
        private val knowledgeSpaces: KnowledgeSpaceRepository,
        private val overviewProjections: NetworkOverviewProjectionRepository,
        private val channelAis: ChannelAiRepository,
        private val feedbacks: AiFeedbackRepository,
        private val events: AiNetworkEventRepository,
    ) {
        private val clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
        private val foundation =
            AiNetworkFoundationService(
                networkProfiles = networkProfiles,
                providerCapabilities = providerCapabilities,
                knowledgeSpaces = knowledgeSpaces,
                overviewProjections = overviewProjections,
                channelAis = channelAis,
                feedbacks = feedbacks,
                clock = clock,
            )
        private val service = ProviderSafetyService(providerCapabilities, events, foundation, clock)
        private val controller = ProviderSafetyController(service)

        @Test
        fun `overload dashboard exposes actionable provider protection alerts`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 1,
                    providerState = "ONLINE",
                    modelNames = "llama3.1:8b",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 2,
                    providerState = "OVERLOADED",
                    modelNames = "qwen-coder",
                    overloadRisk = "high",
                ),
            )

            val dashboard = controller.overloadAlerts(100)
            val guard = controller.guardFanout(100, requestedCandidates = 3)

            assertEquals(1, dashboard.alertCount)
            assertEquals(1, dashboard.safeOnlineProviderCount)
            assertEquals(1, guard.maxSafeCandidates)
            assertTrue(
                dashboard.alerts
                    .single()
                    .recommendedAction
                    .contains("요청량"),
            )
        }

        @Test
        fun `mark overload records event and refreshes overview warning state`() {
            val result =
                controller.markOverload(
                    guildId = 100,
                    providerUserId = 9,
                    request = MarkProviderOverloadRequest(overloadRisk = "critical", reason = "응답 지연 급증"),
                )
            val dashboard = controller.overloadAlerts(100)
            val guard = controller.guardFanout(100, requestedCandidates = 2)

            assertEquals(1, result["overloadAlertCount"])
            assertEquals("warning", result["healthStatus"])
            assertEquals("critical", dashboard.alerts.single().risk)
            assertFalse(guard.allowed)
            assertEquals("provider_overload", events.findTop20ByGuildIdOrderByCreatedAtDesc(100).single().eventType)
        }
    }
