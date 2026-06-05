package com.discordassistant.central.network
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.dashboard.MarkProviderOverloadRequest
import com.discordassistant.central.dashboard.ProviderSafetyController
import com.discordassistant.central.domain.OverloadRisk
import com.discordassistant.central.domain.ProviderAvailability
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.AiNetworkEventRepository
import com.discordassistant.central.persistence.AiNetworkProfileRepository
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
                    providerState = ProviderAvailability.ONLINE,
                    modelNames = "llama3.1:8b",
                    overloadRisk = OverloadRisk.NORMAL,
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 2,
                    providerState = ProviderAvailability.OVERLOADED,
                    modelNames = "qwen-coder",
                    overloadRisk = OverloadRisk.HIGH,
                ),
            )

            val dashboard = controller.overloadAlerts(100)
            val adminDashboard = controller.overloadAlerts(100, audience = "admin")
            val guard = controller.guardFanout(100, requestedCandidates = 3)

            assertEquals(1, dashboard.alertCount)
            assertEquals(1, dashboard.safeOnlineProviderCount)
            assertEquals(1, guard.maxSafeCandidates)
            val publicAlert = dashboard.alerts.single()
            assertEquals(null, publicAlert.providerUserId)
            assertEquals("Provider 1", publicAlert.providerLabel)
            assertEquals("unavailable", publicAlert.providerState)
            assertEquals("protected", publicAlert.risk)
            assertFalse(publicAlert.message.contains("#2"))
            assertTrue(publicAlert.recommendedAction.contains("요청량"))
            val adminAlert = adminDashboard.alerts.single()
            assertEquals(2L, adminAlert.providerUserId)
            assertEquals("provider:2", adminAlert.providerLabel)
            assertEquals("OVERLOADED", adminAlert.providerState)
            assertEquals("high", adminAlert.risk)
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
            assertEquals("Provider", result["providerLabel"])
            assertFalse(result.containsKey("providerCapabilityId"))
            val publicAlert = dashboard.alerts.single()
            assertEquals("protected", publicAlert.risk)
            assertFalse(publicAlert.message.contains("#9"))
            val adminDashboard = controller.overloadAlerts(100, audience = "admin")
            val adminAlert = adminDashboard.alerts.single()
            assertEquals("critical", adminAlert.risk)
            assertEquals(9L, adminAlert.providerUserId)
            val adminResult =
                controller.markOverload(
                    guildId = 101,
                    providerUserId = 10,
                    request = MarkProviderOverloadRequest(overloadRisk = "high"),
                    audience = "admin",
                )
            assertEquals("provider:10", adminResult["providerLabel"])
            assertTrue(adminResult.containsKey("providerCapabilityId"))
            assertFalse(guard.allowed)
            assertEquals("provider_overload", events.findTop20ByGuildIdOrderByCreatedAtDesc(100).single().eventType)
        }

        @Test
        fun `execution plan downgrades advanced modes when provider protection is risky`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 200,
                    providerUserId = 1,
                    providerState = ProviderAvailability.ONLINE,
                    modelNames = "llama3.1:8b",
                    overloadRisk = OverloadRisk.NORMAL,
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 200,
                    providerUserId = 2,
                    providerState = ProviderAvailability.OVERLOADED,
                    modelNames = "qwen-coder",
                    overloadRisk = OverloadRisk.HIGH,
                ),
            )

            val plan = controller.executionPlan(200, responseMode = "deep", requestedCandidates = 3)

            assertEquals("deep", plan.requestedResponseMode)
            assertEquals("balanced", plan.effectiveResponseMode)
            assertFalse(plan.advancedFeaturesAllowed)
            assertFalse(plan.fanoutAllowed)
            assertEquals(1, plan.maxSafeCandidates)
            assertTrue(plan.disabledFeatures.contains("multi_response"))
            assertTrue(plan.disabledFeatures.contains("deep_response"))
            assertTrue(plan.disabledFeatures.contains("provider_pressure_boost"))
            assertTrue(plan.reasons.any { it.contains("Provider 보호") })
        }

        @Test
        fun `execution plan allows limited fanout when enough safe providers exist`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 201,
                    providerUserId = 1,
                    providerState = ProviderAvailability.ONLINE,
                    modelNames = "llama3.1:8b",
                    overloadRisk = OverloadRisk.NORMAL,
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 201,
                    providerUserId = 2,
                    providerState = ProviderAvailability.ONLINE,
                    modelNames = "qwen-coder",
                    overloadRisk = OverloadRisk.NORMAL,
                ),
            )

            val plan = controller.executionPlan(201, responseMode = "deep", requestedCandidates = 2)

            assertEquals("deep", plan.effectiveResponseMode)
            assertTrue(plan.advancedFeaturesAllowed)
            assertTrue(plan.fanoutAllowed)
            assertEquals(2, plan.maxSafeCandidates)
            assertTrue(plan.disabledFeatures.isEmpty())
        }
    }
