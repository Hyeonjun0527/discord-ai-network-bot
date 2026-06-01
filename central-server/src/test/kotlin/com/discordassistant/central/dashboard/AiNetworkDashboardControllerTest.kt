package com.discordassistant.central.dashboard

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
                        status = "ready",
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
                    status = "indexed",
                ),
            )
            feedbacks.save(AiFeedbackEntity(guildId = 802, channelId = 902, rating = 1, status = "resolved"))

            val readiness = dashboard.readiness(802, audience = "admin")

            assertEquals("ready", readiness.status)
            assertEquals(100, readiness.score)
            assertEquals(0, readiness.blockedAreaCount)
            assertEquals(0, readiness.warningAreaCount)
            assertEquals(7, readiness.readyAreaCount)
            assertTrue(readiness.topNextActions.isEmpty())
        }
    }
