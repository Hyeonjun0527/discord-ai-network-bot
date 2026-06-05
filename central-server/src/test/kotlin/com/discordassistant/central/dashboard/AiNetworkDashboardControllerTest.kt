package com.discordassistant.central.dashboard

import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkDashboardController
import com.discordassistant.central.ainetwork.adapter.inbound.web.ChannelUsageResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.FeatureUserResponse
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.NetworkOverviewProjectionRepository
import com.discordassistant.central.ainetwork.application.AiNetworkFoundationService
import com.discordassistant.central.ainetwork.domain.model.FeedbackStatus
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSpaceStatus
import com.discordassistant.central.requestlog.adapter.outbound.persistence.AiRequestEntity
import com.discordassistant.central.requestlog.adapter.outbound.persistence.AiRequestRepository
import com.discordassistant.central.shared.ModelBurden
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
        private val aiRequests: AiRequestRepository,
        private val networkEvents: AiNetworkEventRepository,
    ) {
        @Test
        fun `channel usage aggregates per channel without exposing prompts (Phase2 a)`() {
            val t0 = Instant.parse("2026-06-01T00:00:00Z")
            aiRequests.save(AiRequestEntity(requestId = "cu1", guildId = 810, channelId = 11, userId = 1, createdAt = t0))
            aiRequests.save(AiRequestEntity(requestId = "cu2", guildId = 810, channelId = 11, userId = 2, createdAt = t0.plusSeconds(60)))
            aiRequests.save(AiRequestEntity(requestId = "cu3", guildId = 810, channelId = 12, userId = 1, createdAt = t0.plusSeconds(30)))

            val usage = dashboard.channelUsage(810)

            assertEquals(2, usage.size)
            val ch11 = usage.first { it.channelId == 11L }
            assertEquals(2L, ch11.requestCount)
            assertEquals(2L, ch11.distinctUsers)
            // 프라이버시: 응답 필드는 집계만(프롬프트/메시지 본문 필드 없음)
            val fields = ChannelUsageResponse::class.java.declaredFields.map { it.name }
            assertTrue(fields.none { it.contains("prompt", ignoreCase = true) || it.contains("message", ignoreCase = true) })
        }

        @Test
        fun `feature users list aggregates per user and omits prompt content (Phase2 d)`() {
            val t0 = Instant.parse("2026-06-02T00:00:00Z")
            aiRequests.save(AiRequestEntity(requestId = "fu1", guildId = 811, channelId = 1, userId = 7, createdAt = t0))
            aiRequests.save(AiRequestEntity(requestId = "fu2", guildId = 811, channelId = 1, userId = 7, createdAt = t0.plusSeconds(120)))
            aiRequests.save(AiRequestEntity(requestId = "fu3", guildId = 811, channelId = 2, userId = 8, createdAt = t0.plusSeconds(60)))

            val users = dashboard.featureUsers(811)

            assertEquals(2, users.size)
            val u7 = users.first { it.userId == 7L }
            assertEquals(2L, u7.requestCount)
            assertEquals(t0.toString(), u7.firstUsedAt)
            assertEquals(t0.plusSeconds(120).toString(), u7.lastUsedAt)
            // 프라이버시: userId·집계만(프롬프트/메시지 본문 필드 없음)
            val fields = FeatureUserResponse::class.java.declaredFields.map { it.name }
            assertTrue(fields.none { it.contains("prompt", ignoreCase = true) || it.contains("message", ignoreCase = true) })
        }

        @Test
        fun `provider history returns timeline and supports provider filter (Phase2 c)`() {
            val t0 = Instant.parse("2026-06-03T00:00:00Z")
            networkEvents.save(
                AiNetworkEventEntity(guildId = 812, eventType = "provider_joined", providerUserId = 500, title = "참여", createdAt = t0),
            )
            networkEvents.save(
                AiNetworkEventEntity(
                    guildId = 812,
                    eventType = "provider_overload",
                    providerUserId = 500,
                    title = "과부하",
                    createdAt = t0.plusSeconds(60),
                ),
            )
            networkEvents.save(
                AiNetworkEventEntity(
                    guildId = 812,
                    eventType = "provider_joined",
                    providerUserId = 600,
                    title = "다른 참여",
                    createdAt = t0.plusSeconds(30),
                ),
            )
            // 프로바이더 무관 이벤트 — 필터 없는 조회에서 제외돼야 한다.
            networkEvents.save(
                AiNetworkEventEntity(
                    guildId = 812,
                    eventType = "network_level",
                    providerUserId = null,
                    title = "네트워크 레벨업",
                    createdAt = t0.plusSeconds(90),
                ),
            )

            val all = dashboard.providerHistory(812)
            assertEquals(3, all.size) // network_level 제외
            assertTrue(all.all { it.providerUserId != null })
            assertEquals("과부하", all[0].title)

            val filtered = dashboard.providerHistory(812, providerUserId = 500)
            assertEquals(2, filtered.size)
            assertTrue(filtered.all { it.providerUserId == 500L })
        }

        @Test
        fun `provider status exposes availability hours and last seen for admin`() {
            foundation.upsertProviderCapability(
                guildId = 813,
                providerUserId = 700,
                providerState = ProviderAvailability.ONLINE,
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.STANDARD,
                maxConcurrency = 2,
                dailyLimit = 30,
                overloadRisk = OverloadRisk.NORMAL,
                availableFromHour = 9,
                availableToHour = 18,
            )

            val admin = dashboard.providers(813, audience = "admin").single()
            assertEquals(9, admin.availableFromHour)
            assertEquals(18, admin.availableToHour)

            // 공개 뷰는 가용시간을 숨긴다(capacity 가시성 없음)
            val public = dashboard.providers(813, audience = "public").single()
            assertEquals(null, public.availableFromHour)
            assertEquals(null, public.availableToHour)
        }

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
                providerState = ProviderAvailability.ONLINE,
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.STANDARD,
                maxConcurrency = 3,
                dailyLimit = 40,
                overloadRisk = OverloadRisk.HIGH,
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
                providerState = ProviderAvailability.ONLINE,
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.STANDARD,
                maxConcurrency = 2,
                dailyLimit = 30,
                overloadRisk = OverloadRisk.NORMAL,
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
                providerState = ProviderAvailability.ONLINE,
                modelNames = listOf("llama3.1:8b", "qwen-coder"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.STANDARD,
                maxConcurrency = 2,
                dailyLimit = 50,
                overloadRisk = OverloadRisk.NORMAL,
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
