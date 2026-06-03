package com.discordassistant.central.dashboard

import com.discordassistant.central.domain.FeedbackStatus
import com.discordassistant.central.domain.KnowledgeSourceStatus
import com.discordassistant.central.domain.KnowledgeSpaceStatus
import com.discordassistant.central.network.AiNetworkFoundationService
import com.discordassistant.central.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiFeedbackEntity
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.KnowledgeSourceEntity
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.persistence.NetworkOverviewProjectionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@SpringBootTest
@Transactional
class AiNetworkDashboardControllerTest
    @Autowired
    constructor(
        private val dashboard: AiNetworkDashboardController,
        private val foundation: AiNetworkFoundationService,
        private val channelAis: ChannelAiRepository,
        private val behaviorVersions: AiBehaviorVersionRepository,
        private val routingPolicies: ChannelAiRoutingPolicyRepository,
        private val knowledgeSpaces: KnowledgeSpaceRepository,
        private val knowledgeSources: KnowledgeSourceRepository,
        private val feedbacks: AiFeedbackRepository,
        private val overviewProjections: NetworkOverviewProjectionRepository,
    ) {
        @Test
        fun `readiness exposes blocked launch gaps with next actions`() {
            val readiness = dashboard.readiness(801)

            assertEquals("blocked", readiness.status)
            assertTrue(readiness.score < 70)
            assertTrue(readiness.blockedAreaCount >= 2)
            assertTrue(readiness.areas.any { it.key == "providers" && it.status == "blocked" })
            assertTrue(readiness.topNextActions.any { it.contains("Provider") })
        }

        @Test
        fun `dashboard includes multi response operations summary`() {
            val response = dashboard.dashboard(803, audience = "admin")

            assertEquals(803, response.multiResponseOperations.guildId)
            assertTrue(response.multiResponseOperations.riskCodes.contains("no_recent_runs"))
            assertTrue(response.multiResponseOperations.nextActions.isNotEmpty())
        }

        @Test
        fun `provider dashboard visibility changes by audience without exposing private state publicly`() {
            foundation.upsertProviderCapability(
                guildId = 804,
                providerUserId = 99001,
                providerState = "ONLINE",
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = "STANDARD",
                maxConcurrency = 3,
                dailyLimit = 40,
                overloadRisk = "high",
            )

            val public = dashboard.providers(804, audience = "public").single()
            assertEquals(null, public.providerUserId)
            assertEquals("Provider 1", public.providerLabel)
            assertEquals("available", public.state)
            assertEquals("protected", public.overloadRisk)
            assertEquals(null, public.maxConcurrency)
            assertEquals(null, public.dailyLimit)
            assertEquals(null, public.lastSeenAt)

            val provider = dashboard.providers(804, audience = "provider").single()
            assertEquals(null, provider.providerUserId)
            assertEquals("Provider 1", provider.providerLabel)
            assertEquals("ONLINE", provider.state)
            assertEquals("high", provider.overloadRisk)
            assertEquals(3, provider.maxConcurrency)
            assertEquals(40, provider.dailyLimit)

            val admin = dashboard.providers(804, audience = "admin").single()
            assertEquals(99001, admin.providerUserId)
            assertEquals("provider:99001", admin.providerLabel)
            assertEquals("ONLINE", admin.state)
            assertEquals("high", admin.overloadRisk)
            assertEquals(3, admin.maxConcurrency)
            assertEquals(40, admin.dailyLimit)
        }

        @Test
        fun `overview can read stale projection and exposes freshness metadata`() {
            overviewProjections.save(
                NetworkOverviewProjectionEntity(
                    guildId = 805,
                    onlineProviderCount = 1,
                    approvedProviderCount = 1,
                    modelCount = 2,
                    healthStatus = "ready",
                    refreshedAt = Instant.parse("2026-06-01T00:00:00Z"),
                    staleAfter = Instant.parse("2026-06-01T00:01:00Z"),
                ),
            )

            val overview = dashboard.overview(805, refresh = false)

            assertEquals(1, overview.onlineProviderCount)
            assertEquals(2, overview.modelCount)
            assertEquals("stale", overview.freshnessStatus)
            assertEquals(true, overview.stale)
            assertEquals("projection_stale", overview.degradedReason)
            assertEquals("2026-06-01T00:00:00Z", overview.refreshedAt)
        }

        @Test
        fun `dashboard reads existing overview projection without refreshing by default`() {
            overviewProjections.save(
                NetworkOverviewProjectionEntity(
                    guildId = 806,
                    onlineProviderCount = 7,
                    approvedProviderCount = 7,
                    modelCount = 9,
                    healthStatus = "ready",
                    refreshedAt = Instant.parse("2026-06-01T00:00:00Z"),
                    staleAfter = Instant.parse("2026-06-01T00:01:00Z"),
                ),
            )
            foundation.upsertProviderCapability(
                guildId = 806,
                providerUserId = 99002,
                providerState = "ONLINE",
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = "STANDARD",
                maxConcurrency = 2,
                dailyLimit = 30,
                overloadRisk = "normal",
            )

            val response = dashboard.dashboard(806, audience = "public")

            assertEquals("network_overview_projection", response.metadata.source)
            assertEquals("stale", response.metadata.freshnessStatus)
            assertEquals(true, response.metadata.stale)
            assertEquals("projection_stale", response.metadata.degradedReason)
            assertEquals(7, response.overview.onlineProviderCount)
            assertEquals(9, response.overview.modelCount)
            assertEquals(1, response.providers.size)
            assertEquals(7, overviewProjections.findByGuildId(806)!!.onlineProviderCount)
        }

        @Test
        fun `readiness becomes ready when provider channel ai knowledge feedback and safety are prepared`() {
            foundation.upsertProviderCapability(
                guildId = 802,
                providerUserId = 300,
                providerState = "ONLINE",
                modelNames = listOf("llama3.1:8b", "qwen-coder"),
                capabilityTags = listOf("coding"),
                maxBurden = "STANDARD",
                maxConcurrency = 2,
                dailyLimit = 50,
                overloadRisk = "normal",
            )
            val channelAi =
                channelAis.save(
                    ChannelAiEntity(
                        guildId = 802,
                        channelId = 902,
                        displayName = "코드냥",
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                )
            val behavior =
                behaviorVersions.save(
                    AiBehaviorVersionEntity(
                        channelAiId = channelAi.id,
                        version = 1,
                        purpose = "Kotlin/Spring Boot 개발 질문을 돕습니다.",
                        tone = "정확하고 실용적으로",
                        answerLength = "balanced",
                    ),
                )
            channelAi.activeBehaviorVersionId = behavior.id
            channelAis.save(channelAi)
            routingPolicies.save(
                ChannelAiRoutingPolicyEntity(
                    guildId = 802,
                    channelId = 902,
                    channelAiId = channelAi.id,
                    responseMode = "balanced",
                    preferredModel = "qwen-coder",
                ),
            )
            val space =
                knowledgeSpaces.save(
                    KnowledgeSpaceEntity(
                        guildId = 802,
                        channelId = 902,
                        channelAiId = channelAi.id,
                        displayName = "개발 지식",
                        status = KnowledgeSpaceStatus.READY,
                        sourceCount = 1,
                    ),
                )
            knowledgeSources.save(
                KnowledgeSourceEntity(
                    knowledgeSpaceId = space.id,
                    guildId = 802,
                    sourceType = "link",
                    sourceUri = "https://example.com/readme.md",
                    title = "README",
                    status = KnowledgeSourceStatus.INDEXED,
                ),
            )
            feedbacks.save(AiFeedbackEntity(guildId = 802, channelId = 902, rating = 1, status = FeedbackStatus.RESOLVED))

            val readiness = dashboard.readiness(802, audience = "admin")

            assertEquals("ready", readiness.status)
            assertEquals(100, readiness.score)
            assertEquals(0, readiness.blockedAreaCount)
            assertEquals(0, readiness.warningAreaCount)
            assertEquals(8, readiness.readyAreaCount)
            assertTrue(readiness.topNextActions.isEmpty())
        }
    }
