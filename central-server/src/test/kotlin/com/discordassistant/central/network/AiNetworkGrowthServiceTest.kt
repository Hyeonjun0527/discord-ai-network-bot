package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkGrowthController
import com.discordassistant.central.ainetwork.adapter.inbound.web.ProviderJoinedRequest
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkProfileRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.NetworkOverviewProjectionRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.application.AiNetworkFoundationService
import com.discordassistant.central.ainetwork.application.AiNetworkGrowthService
import com.discordassistant.central.ainetwork.application.AiNetworkLevelMilestone
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.shared.ModelBurden
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
class AiNetworkGrowthServiceTest
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
        private val growth = AiNetworkGrowthService(foundation, events, providerCapabilities, clock)
        private val controller = AiNetworkGrowthController(growth)

        @Test
        fun `provider joined event shows how network improved`() {
            val response =
                controller.providerJoined(
                    987654321,
                    ProviderJoinedRequest(
                        providerUserId = 77,
                        modelNames = listOf("llama3.1:8b"),
                        capabilityTags = listOf("coding", "night"),
                        maxBurden = "STANDARD",
                        maxConcurrency = 2,
                        dailyLimit = 30,
                    ),
                )

            assertEquals(2, response["networkLevel"])
            assertEquals("Provider 1", response["providerLabel"])
            assertTrue(!response.containsKey("providerCapabilityId"))
            val adminResponse =
                controller.providerJoined(
                    987654322,
                    ProviderJoinedRequest(providerUserId = 78, modelNames = listOf("qwen-coder")),
                    audience = "admin",
                )
            assertEquals("provider:78", adminResponse["providerLabel"])
            assertTrue(adminResponse.containsKey("providerCapabilityId"))
            val timeline = controller.timeline(987654321)
            assertTrue(timeline.toString().contains("Provider가 AI 네트워크에 참여했어요"))
            assertTrue(timeline.toString().contains("사용 가능한 모델 llama3.1:8b 추가"))
            assertTrue(timeline.toString().contains("특화 능력 coding, night 추가"))
            assertTrue(timeline.toString().contains("동시 처리 용량 2개 확보"))
            assertTrue(timeline.toString().contains("하루 최대 30 회 Provider 보호 한도 적용"))
            val joined = timeline.first { it["eventType"] == "provider_joined" }
            assertTrue(joined["levelBefore"] is Int)
            assertTrue(joined["levelAfter"] is Int)
            assertEquals("Provider 1", joined["providerLabel"])
            assertTrue(!joined.containsKey("providerUserId"))

            val adminJoined = controller.timeline(987654321, audience = "admin").first { it["eventType"] == "provider_joined" }
            assertEquals(77L, adminJoined["providerUserId"])
            assertEquals("provider:77", adminJoined["providerLabel"])
        }

        @Test
        fun `provider hello sync persists real relay capabilities without duplicate growth events`() {
            val first =
                growth.syncProviderCapabilitiesFromHello(
                    guildId = 333,
                    providerUserId = 77,
                    modelNames = listOf("qwen2.5-coder:7b", "llama3.1:8b"),
                    maxConcurrency = 2,
                    remainingDailyRequests = 25,
                )

            assertTrue(first.changed)
            assertTrue(first.eventId != null)
            val profile = providerCapabilities.findByGuildIdAndProviderUserId(333, 77)!!
            assertEquals(ProviderAvailability.ONLINE, profile.providerState)
            assertEquals("qwen2.5-coder:7b,llama3.1:8b", profile.modelNames)
            assertEquals(ModelBurden.STANDARD, profile.maxBurden)
            assertEquals(2, profile.maxConcurrency)
            assertEquals(25, profile.dailyLimit)
            assertTrue(profile.capabilityTags!!.contains("coding"))
            assertTrue(profile.capabilityTags!!.contains("local-llm"))

            val repeat =
                growth.syncProviderCapabilitiesFromHello(
                    guildId = 333,
                    providerUserId = 77,
                    modelNames = listOf("qwen2.5-coder:7b", "llama3.1:8b"),
                    maxConcurrency = 2,
                    remainingDailyRequests = 25,
                )

            assertFalse(repeat.changed)
            assertEquals(null, repeat.eventId)
            assertEquals(1, events.findByGuildIdAndEventType(333, "provider_joined").size)

            growth.markProviderOffline(333, 77)

            val offline = providerCapabilities.findByGuildIdAndProviderUserId(333, 77)!!
            assertEquals(ProviderAvailability.OFFLINE, offline.providerState)
            assertEquals(0, overviewProjections.findByGuildId(333)!!.onlineProviderCount)
        }

        @Test
        fun `level up event advances when channel ai and providers grow`() {
            channelAis.save(ChannelAiEntity(guildId = 100, channelId = 200, displayName = "코드냥"))
            growth.recordProviderJoined(100, 77, listOf("llama3.1:8b"), listOf("coding"), "STANDARD", 1, 0)
            growth.recordProviderJoined(100, 78, listOf("qwen-coder"), listOf("coding"), "STANDARD", 1, 0)

            val levelEvents = events.findByGuildIdAndEventType(100, "network_level")

            assertTrue(levelEvents.any { it.metadata == "level=3" })
            assertEquals(3, overviewProjections.findByGuildId(100)!!.networkLevel)
        }

        @Test
        fun `level status exposes current level next milestone and gaps`() {
            val empty = controller.levels(700)
            assertEquals(1, empty["currentLevel"])
            val emptyNext = empty["nextMilestone"] as AiNetworkLevelMilestone
            assertEquals(2, emptyNext.level)
            assertEquals(listOf("온라인 Provider 1명 이상 연결"), emptyNext.gaps)

            channelAis.save(ChannelAiEntity(guildId = 700, channelId = 701, displayName = "코드냥"))
            growth.recordProviderJoined(700, 77, listOf("llama3.1:8b"), listOf("coding"), "STANDARD", 1, 0)

            val afterProvider = controller.levels(700)
            assertEquals(2, afterProvider["currentLevel"])
            val next = afterProvider["nextMilestone"] as AiNetworkLevelMilestone
            assertEquals(3, next.level)
            assertEquals(listOf("온라인 Provider 2명 이상"), next.gaps)
            val milestones = afterProvider["milestones"] as List<*>
            assertEquals(5, milestones.size)
            assertTrue(milestones.toString().contains("품질 피드백 5개 이상"))
        }

        @Test
        fun `growth plan recommends concrete next actions and prioritizes provider protection`() {
            val emptyPlan = controller.plan(811)
            assertEquals(1, emptyPlan.currentLevel)
            assertEquals(2, emptyPlan.targetLevel)
            assertEquals("connect_first_provider", emptyPlan.actions.first().key)
            assertEquals("/프로바이더참여", emptyPlan.actions.first().command)
            assertTrue(emptyPlan.builderMessage.contains("함께 만들어지고 있어요"))
            assertTrue(emptyPlan.capabilityBasis.contains("onlineProviderCount=0"))
            assertTrue(emptyPlan.recommendationPolicy.contains("자동 적용되지"))
            assertTrue(emptyPlan.actions.all { !it.autoApply })

            channelAis.save(ChannelAiEntity(guildId = 812, channelId = 912, displayName = "코드냥"))
            growth.recordProviderJoined(812, 77, listOf("llama3.1:8b"), listOf("coding"), "STANDARD", 1, 0)
            foundation.upsertProviderCapability(
                guildId = 812,
                providerUserId = 77,
                providerState = ProviderAvailability.OVERLOADED,
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.STANDARD,
                maxConcurrency = 1,
                dailyLimit = 0,
                overloadRisk = OverloadRisk.HIGH,
            )

            val riskyPlan = controller.plan(812)
            assertEquals("resolve_provider_overload", riskyPlan.actions.first().key)
            assertEquals("critical", riskyPlan.actions.first().severity)
            assertTrue(riskyPlan.summary.contains("Provider 보호"))
        }

        @Test
        fun `growth recommendations expose approval guard for settings changes`() {
            val plan = controller.plan(820)

            val channelAiAction = plan.actions.single { it.key == "create_first_channel_ai" }
            val providerAction = plan.actions.single { it.key == "connect_first_provider" }

            assertTrue(channelAiAction.requiresAdminApproval)
            assertFalse(channelAiAction.autoApply)
            assertFalse(providerAction.requiresAdminApproval)
            assertFalse(providerAction.autoApply)
            assertTrue(plan.recommendationPolicy.contains("관리자 검토"))
        }
    }
