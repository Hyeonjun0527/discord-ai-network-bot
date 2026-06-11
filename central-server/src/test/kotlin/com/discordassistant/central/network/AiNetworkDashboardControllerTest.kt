package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkDashboardController
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkProfileRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.NetworkOverviewProjectionRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.application.AiNetworkDashboardQueryService
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.ainetwork.application.AiNetworkFoundationService
import com.discordassistant.central.ainetwork.application.AiNetworkGrowthService
import com.discordassistant.central.ainetwork.application.AiQualityFeedbackService
import com.discordassistant.central.ainetwork.application.ProviderSafetyService
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.domain.model.ProposalStatus
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.SynthesisResultRepository
import com.discordassistant.central.multiresponse.application.MultiResponseService
import com.discordassistant.central.multiresponse.domain.model.CandidateStatus
import com.discordassistant.central.multiresponse.domain.model.MultiResponseRunStatus
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetRepository
import com.discordassistant.central.requestlog.adapter.outbound.persistence.AiRequestRepository
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogRepository
import com.discordassistant.central.requestlog.application.AnalyticsService
import com.discordassistant.central.shared.ModelBurden
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
class AiNetworkDashboardControllerTest
    @Autowired
    constructor(
        private val networkProfiles: AiNetworkProfileRepository,
        private val providerCapabilities: ProviderCapabilityProfileRepository,
        private val knowledgeSpaces: KnowledgeSpaceRepository,
        private val knowledgeSources: KnowledgeSourceRepository,
        private val overviewProjections: NetworkOverviewProjectionRepository,
        private val channelAis: ChannelAiRepository,
        private val behaviorVersions: AiBehaviorVersionRepository,
        private val proposals: AiChangeProposalRepository,
        private val routingPolicies: ChannelAiRoutingPolicyRepository,
        private val multiResponsePolicies: MultiResponsePolicyRepository,
        private val multiResponseRuns: MultiResponseRunRepository,
        private val syntheses: SynthesisResultRepository,
        private val feedbacks: AiFeedbackRepository,
        private val candidateAnswers: CandidateAnswerRepository,
        private val events: AiNetworkEventRepository,
        private val presets: AiPresetRepository,
        private val presetRevisions: PresetRevisionRepository,
        private val publishedPresets: PublishedPresetRepository,
        private val presetImports: PresetImportRepository,
        private val aiRequests: AiRequestRepository,
        private val usageLogs: UsageLogRepository,
    ) {
        private val fixedClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

        private val analytics = AnalyticsService(usageLogs, aiRequests, events)

        private val foundation =
            AiNetworkFoundationService(
                networkProfiles = networkProfiles,
                providerCapabilities = providerCapabilities,
                knowledgeSpaces = knowledgeSpaces,
                overviewProjections = overviewProjections,
                channelAis = channelAis,
                feedbacks = feedbacks,
                clock = fixedClock,
            )

        private val qualityFeedback =
            AiQualityFeedbackService(
                feedbacks = feedbacks,
                channelAis = channelAis,
                candidateAnswers = candidateAnswers,
                providerCapabilities = providerCapabilities,
                clock = fixedClock,
            )

        private val providerSafety = ProviderSafetyService(providerCapabilities, events, foundation, fixedClock)
        private val growth = AiNetworkGrowthService(foundation, events, providerCapabilities, fixedClock)
        private val multiResponse =
            MultiResponseService(
                policies = multiResponsePolicies,
                runs = multiResponseRuns,
                candidates = candidateAnswers,
                syntheses = syntheses,
                providerCapabilities = providerCapabilities,
                feedbacks = feedbacks,
                clock = fixedClock,
            )

        private val dashboardQuery =
            AiNetworkDashboardQueryService(
                channelAis = channelAis,
                behaviorVersions = behaviorVersions,
                proposals = proposals,
                routingPolicies = routingPolicies,
                multiResponsePolicies = multiResponsePolicies,
                providerCapabilities = providerCapabilities,
                knowledgeSpaces = knowledgeSpaces,
                knowledgeSources = knowledgeSources,
                presets = presets,
                publishedPresets = publishedPresets,
                presetImports = presetImports,
                foundation = foundation,
                growth = growth,
                qualityFeedback = qualityFeedback,
                providerSafety = providerSafety,
                multiResponse = multiResponse,
                analytics = analytics,
            )

        private val controller =
            AiNetworkDashboardController(
                query = dashboardQuery,
            )

        @Test
        fun `dashboard kill switch blocks read APIs before projection work`() {
            val disabledController =
                AiNetworkDashboardController(
                    query = dashboardQuery,
                    featureGate = AiNetworkFeatureGate(dashboardEnabled = false),
                )

            assertThrows(IllegalStateException::class.java) { disabledController.overview(100) }
            assertThrows(IllegalStateException::class.java) { disabledController.dashboard(100) }
            assertThrows(IllegalStateException::class.java) { disabledController.channels(100) }
        }

        @Test
        fun `overview returns refreshed AI network snapshot`() {
            channelAis.save(ChannelAiEntity(guildId = 100, channelId = 200, displayName = "코드 니아"))
            foundation.upsertProviderCapability(
                guildId = 100,
                providerUserId = 300,
                providerState = ProviderAvailability.ONLINE,
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.STANDARD,
                maxConcurrency = 2,
                dailyLimit = 20,
                overloadRisk = OverloadRisk.NORMAL,
            )

            val response = controller.overview(100)

            assertEquals("함께 만드는 AI 네트워크", response.tagline)
            assertEquals(1, response.onlineProviderCount)
            assertEquals(1, response.channelAiCount)
            assertEquals("ready", response.healthStatus)
        }

        @Test
        fun `overview can serve stale projection with explicit degraded marker`() {
            overviewProjections.save(
                NetworkOverviewProjectionEntity(
                    guildId = 101,
                    onlineProviderCount = 5,
                    approvedProviderCount = 6,
                    modelCount = 4,
                    channelAiCount = 3,
                    knowledgeSpaceCount = 2,
                    feedbackCount = 9,
                    overloadAlertCount = 1,
                    networkLevel = 4,
                    healthStatus = "warning",
                    staleAfter = Instant.parse("2026-05-31T23:59:00Z"),
                    refreshedAt = Instant.parse("2026-05-31T23:50:00Z"),
                ),
            )

            val response = controller.overview(101, refresh = false)

            assertEquals(5, response.onlineProviderCount)
            assertEquals("stale", response.freshnessStatus)
            assertTrue(response.stale)
            assertEquals("projection_stale", response.degradedReason)
            assertEquals("2026-05-31T23:50:00Z", response.refreshedAt)
        }

        @Test
        fun `dashboard lists channels providers knowledge and presets without prompt bodies`() {
            val channelAi = channelAis.save(ChannelAiEntity(guildId = 100, channelId = 200, displayName = "코드 니아"))
            val behavior =
                behaviorVersions.save(
                    AiBehaviorVersionEntity(
                        channelAiId = channelAi.id,
                        version = 1,
                        purpose = "개발 질문",
                        tone = "practical",
                        answerLength = "balanced",
                        safetyLevel = "strict",
                    ),
                )
            channelAi.activeBehaviorVersionId = behavior.id
            channelAis.save(channelAi)
            routingPolicies.save(
                ChannelAiRoutingPolicyEntity(
                    guildId = 100,
                    channelId = 200,
                    channelAiId = channelAi.id,
                    responseMode = "deep",
                    preferredModel = "qwen-coder",
                    allowedModels = "qwen-coder,llama3.1:8b",
                    minQualityTier = "high",
                ),
            )
            multiResponsePolicies.save(
                MultiResponsePolicyEntity(
                    guildId = 100,
                    channelId = 200,
                    channelAiId = channelAi.id,
                    mode = "compare",
                    maxCandidates = 2,
                    synthesisEnabled = true,
                ),
            )
            foundation.upsertProviderCapability(
                guildId = 100,
                providerUserId = 300,
                providerState = ProviderAvailability.ONLINE,
                modelNames = listOf("llama3.1:8b", "qwen-coder"),
                capabilityTags = listOf("coding", "night"),
                maxBurden = ModelBurden.STANDARD,
                maxConcurrency = 2,
                dailyLimit = 20,
                overloadRisk = OverloadRisk.NORMAL,
            )
            val knowledgeSpace = foundation.createKnowledgeSpace(100, 200, channelAi.id, "코드 지식", 77)
            knowledgeSources.save(
                KnowledgeSourceEntity(
                    knowledgeSpaceId = knowledgeSpace.id,
                    guildId = 100,
                    sourceType = "link",
                    title = "Kotlin Spring 운영 가이드",
                    status = KnowledgeSourceStatus.INDEXED,
                    riskLevel = "normal",
                ),
            )
            val preset = presets.save(AiPresetEntity(guildId = 100, ownerUserId = 77, name = "코딩 튜터"))
            val revision =
                presetRevisions.save(
                    PresetRevisionEntity(
                        presetId = preset.id,
                        revision = 1,
                        name = "코딩 튜터",
                        purpose = "개발 질문",
                        tone = "practical",
                    ),
                )
            val published =
                publishedPresets.save(
                    PublishedPresetEntity(
                        presetId = preset.id,
                        revisionId = revision.id,
                        publisherGuildId = 100,
                        slug = "coding-tutor-${preset.id}",
                        title = "코딩 튜터",
                    ),
                )
            presetImports.save(PresetImportEntity(publishedPresetId = published.id, targetGuildId = 100, targetChannelId = 200))

            val channels = controller.channels(100)
            val providers = controller.providers(100, audience = "admin")
            val knowledge = controller.knowledgeSpaces(100)
            val guildPresets = controller.guildPresets(100)

            assertEquals("코드 니아", channels.single().name)
            assertEquals("개발 질문", channels.single().purpose)
            assertEquals("deep", channels.single().responseMode)
            assertEquals("qwen-coder", channels.single().preferredModel)
            assertEquals(listOf("qwen-coder", "llama3.1:8b"), channels.single().allowedModels)
            assertEquals("ready", channels.single().knowledgeReadiness)
            assertEquals("ready", channels.single().readinessStatus)
            assertEquals(emptyList<String>(), channels.single().missingParts)
            assertEquals(1, channels.single().indexedKnowledgeSourceCount)
            assertEquals("compare", channels.single().multiResponseMode)
            assertEquals(2, channels.single().multiResponseMaxCandidates)
            assertTrue(channels.single().multiResponseSynthesisEnabled)
            assertEquals(listOf("llama3.1:8b", "qwen-coder"), providers.single().models)
            assertEquals(listOf("coding", "night"), providers.single().tags)
            assertEquals("코드 지식", knowledge.single().name)
            val localPresets = guildPresets["local"] as List<*>
            assertTrue(localPresets.single().toString().contains(preset.name))
            assertTrue(guildPresets.toString().contains("publishedPresetId"))
            val publicPreset = controller.publishedPresets().single()
            assertNull(publicPreset.publisherGuildId)
            assertEquals("공개 프리셋 작성자", publicPreset.publisherLabel)
            assertEquals("coding-tutor-${preset.id}", publicPreset.slug)
            assertTrue(publicPreset.toString().contains("publisherGuildId=null"))
        }

        @Test
        fun `channel summary highlights incomplete channel AI setup`() {
            val incomplete = channelAis.save(ChannelAiEntity(guildId = 140, channelId = 240, displayName = "초안냥"))
            val ready = channelAis.save(ChannelAiEntity(guildId = 140, channelId = 241, displayName = "완성냥"))
            val behavior =
                behaviorVersions.save(
                    AiBehaviorVersionEntity(
                        channelAiId = ready.id,
                        version = 1,
                        purpose = "번역",
                        tone = "concise",
                        answerLength = "short",
                        safetyLevel = "strict",
                    ),
                )
            ready.activeBehaviorVersionId = behavior.id
            channelAis.save(ready)
            routingPolicies.save(
                ChannelAiRoutingPolicyEntity(
                    guildId = 140,
                    channelId = 241,
                    channelAiId = ready.id,
                    responseMode = "fast",
                    preferredModel = "llama3.1:8b",
                ),
            )
            val space = foundation.createKnowledgeSpace(140, 241, ready.id, "번역 지식", 77)
            knowledgeSources.save(
                KnowledgeSourceEntity(
                    knowledgeSpaceId = space.id,
                    guildId = 140,
                    sourceType = "text",
                    title = "용어집",
                    status = KnowledgeSourceStatus.INDEXED,
                    riskLevel = "normal",
                ),
            )

            val cards = controller.channels(140)
            val summary = controller.channelsSummary(140)

            assertEquals("needs_profile", cards.first { it.channelId == incomplete.channelId }.readinessStatus)
            assertTrue(cards.first { it.channelId == incomplete.channelId }.missingParts.contains("behavior_version"))
            assertEquals(2, summary.totalChannelAiCount)
            assertEquals(1, summary.readyChannelAiCount)
            assertEquals(1, summary.channelsNeedingAttentionCount)
            assertEquals(1, summary.readinessCounts["ready"])
            assertEquals(1, summary.readinessCounts["needs_profile"])
            assertEquals(incomplete.channelId, summary.topAttentionItems.single().channelId)
            assertTrue(
                summary.topAttentionItems
                    .single()
                    .nextActions
                    .any { it.contains("채널프로필") },
            )
            assertEquals(1, summary.responseModeCounts["fast"])
            assertEquals(1, summary.knowledgeReadinessCounts["ready"])
        }

        @Test
        fun `public dashboard masks provider identity and sensitive capacity`() {
            foundation.upsertProviderCapability(
                guildId = 100,
                providerUserId = 300,
                providerState = ProviderAvailability.OVERLOADED,
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.HEAVY,
                maxConcurrency = 4,
                dailyLimit = 99,
                overloadRisk = OverloadRisk.CRITICAL,
            )
            val run =
                multiResponseRuns.save(
                    MultiResponseRunEntity(
                        guildId = 100,
                        channelId = 200,
                        requestId = "masked-dashboard-run",
                        status = MultiResponseRunStatus.COMPLETED,
                        candidateCount = 1,
                        startedAt = Instant.parse("2026-06-01T00:00:00Z"),
                    ),
                )
            candidateAnswers.save(
                CandidateAnswerEntity(
                    runId = run.id,
                    providerUserId = 300,
                    modelName = "llama3.1:8b",
                    answerRef = "answer:masked-dashboard-run",
                    status = CandidateStatus.COMPLETED,
                    latencyMs = 800,
                    qualityScore = 91,
                    createdAt = Instant.parse("2026-06-01T00:00:00Z"),
                ),
            )

            val publicProvider = controller.providers(100).single()
            val adminProvider = controller.providers(100, audience = "admin").single()

            assertNull(publicProvider.providerUserId)
            assertEquals("Provider 1", publicProvider.providerLabel)
            assertEquals("unavailable", publicProvider.state)
            assertEquals("protected", publicProvider.overloadRisk)
            assertNull(publicProvider.maxConcurrency)
            assertNull(publicProvider.dailyLimit)
            assertNull(publicProvider.lastSeenAt)
            assertEquals(300, adminProvider.providerUserId)
            assertEquals("critical", adminProvider.overloadRisk)
            assertEquals(4, adminProvider.maxConcurrency)

            val publicDashboard = controller.dashboard(100, audience = "public", refreshOverview = true)
            val publicAlert = publicDashboard.overload.alerts.single()
            assertNull(publicAlert.providerUserId)
            assertEquals("Provider 1", publicAlert.providerLabel)
            assertEquals("protected", publicAlert.risk)
            assertNull(publicAlert.maxConcurrency)
            assertNull(publicAlert.dailyLimit)
            assertTrue(publicAlert.message.contains("#300").not())
            val publicProviderLoad = publicDashboard.multiResponseOperations.providerLoads.single()
            assertNull(publicProviderLoad.providerUserId)
            assertTrue(publicProviderLoad.providerLabel.startsWith("Provider "))

            val adminDashboard = controller.dashboard(100, audience = "admin", refreshOverview = true)
            val adminAlert = adminDashboard.overload.alerts.single()
            assertEquals(300, adminAlert.providerUserId)
            assertEquals("provider:300", adminAlert.providerLabel)
            assertEquals("critical", adminAlert.risk)
            assertEquals(4, adminAlert.maxConcurrency)
            val adminProviderLoad = adminDashboard.multiResponseOperations.providerLoads.single()
            assertEquals(300, adminProviderLoad.providerUserId)
            assertEquals("provider:300", adminProviderLoad.providerLabel)
        }

        @Test
        fun `model map aggregates provider models and channel routing usage`() {
            foundation.upsertProviderCapability(
                guildId = 120,
                providerUserId = 301,
                providerState = ProviderAvailability.ONLINE,
                modelNames = listOf("llama3.1:8b", "qwen-coder"),
                capabilityTags = listOf("coding", "night"),
                maxBurden = ModelBurden.HEAVY,
                maxConcurrency = 2,
                dailyLimit = 20,
                overloadRisk = OverloadRisk.NORMAL,
            )
            foundation.upsertProviderCapability(
                guildId = 120,
                providerUserId = 302,
                providerState = ProviderAvailability.OVERLOADED,
                modelNames = listOf("qwen-coder"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.STANDARD,
                maxConcurrency = 1,
                dailyLimit = 10,
                overloadRisk = OverloadRisk.CRITICAL,
            )
            routingPolicies.save(
                ChannelAiRoutingPolicyEntity(
                    guildId = 120,
                    channelId = 220,
                    responseMode = "deep",
                    preferredModel = "qwen-coder",
                    allowedModels = "qwen-coder,llama3.1:8b",
                ),
            )
            routingPolicies.save(
                ChannelAiRoutingPolicyEntity(
                    guildId = 120,
                    channelId = 221,
                    responseMode = "fast",
                    preferredModel = "llama3.1:8b",
                ),
            )

            val modelMap = controller.modelMap(120)
            val qwen = modelMap.first { it.modelName == "qwen-coder" }
            val llama = modelMap.first { it.modelName == "llama3.1:8b" }

            assertEquals(2, qwen.totalProviderCount)
            assertEquals(1, qwen.onlineProviderCount)
            assertEquals(1, qwen.protectedProviderCount)
            assertEquals(listOf(220L), qwen.channels)
            assertEquals(listOf("coding", "night"), qwen.tags)
            assertEquals(1, llama.totalProviderCount)
            assertEquals(2, llama.channelCount)
            assertEquals(listOf(220L, 221L), llama.channels)
        }

        @Test
        fun `dashboard next actions guide missing foundation steps`() {
            val dashboard = controller.dashboard(501, refreshOverview = true)
            val actionTypes = dashboard.nextActions.map { it.actionType }

            assertEquals("connect_provider", dashboard.nextActions.first().actionType)
            assertTrue(actionTypes.contains("create_channel_ai"))
            assertTrue(actionTypes.contains("add_knowledge"))
            assertTrue(actionTypes.contains("collect_feedback"))
            assertTrue(
                dashboard.nextActions
                    .first { it.actionType == "connect_provider" }
                    .description
                    .contains("온라인 Provider가 없어"),
            )
        }

        @Test
        fun `dashboard surfaces pending AI setting approvals as readiness and next action`() {
            val channelAi = channelAis.save(ChannelAiEntity(guildId = 503, channelId = 603, displayName = "승인냥"))
            val behavior =
                behaviorVersions.save(
                    AiBehaviorVersionEntity(
                        channelAiId = channelAi.id,
                        version = 1,
                        purpose = "위험 변경",
                        tone = "friendly",
                        answerLength = "long",
                        safetyLevel = "high",
                    ),
                )
            proposals.save(
                AiChangeProposalEntity(
                    guildId = 503,
                    channelId = 603,
                    channelAiId = channelAi.id,
                    proposedBehaviorId = behavior.id,
                    status = ProposalStatus.PENDING,
                    requestedBy = 77,
                    reason = "high risk safety level",
                    createdAt = Instant.parse("2026-06-01T00:00:00Z"),
                ),
            )

            val dashboard = controller.dashboard(503, refreshOverview = true)
            val changeApproval = controller.changeApproval(503)

            assertEquals("needs_review", changeApproval.status)
            assertEquals(1, dashboard.changeApproval.pendingCount)
            assertTrue(dashboard.readiness.areas.any { it.key == "change_approval" && it.status == "warning" })
            assertTrue(dashboard.nextActions.any { it.actionType == "review_ai_changes" })
            val pendingApproval = dashboard.changeApproval.pendingItems.single()
            assertEquals(
                behavior.id,
                pendingApproval.proposedBehaviorId,
            )
        }

        @Test
        fun `dashboard includes network growth plan and recent provider impact`() {
            growth.recordProviderJoined(
                guildId = 504,
                providerUserId = 704,
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = "STANDARD",
                maxConcurrency = 2,
                dailyLimit = 30,
            )

            val dashboard = controller.dashboard(504, refreshOverview = true)

            assertEquals(2, dashboard.growthPlan.currentLevel)
            assertTrue(dashboard.growthPlan.actions.any { it.key == "add_second_provider" })
            assertTrue(dashboard.growthPlan.builderMessage.contains("Provider 1명"))
            assertTrue(dashboard.growthPlan.capabilityBasis.contains("onlineProviderCount=1"))
            assertTrue(dashboard.growthPlan.actions.all { !it.autoApply })
            val providerJoined = dashboard.growthTimeline.first { it.eventType == "provider_joined" }
            assertTrue(providerJoined.impactBullets.any { it.contains("llama3.1:8b") })
            assertTrue(dashboard.nextActions.any { it.actionType == "growth_add_second_provider" })
        }

        @Test
        fun `dashboard next actions prioritize provider protection over optimization`() {
            channelAis.save(ChannelAiEntity(guildId = 502, channelId = 602, displayName = "보호냥"))
            foundation.upsertProviderCapability(
                guildId = 502,
                providerUserId = 777,
                providerState = ProviderAvailability.OVERLOADED,
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.HEAVY,
                maxConcurrency = 1,
                dailyLimit = 5,
                overloadRisk = OverloadRisk.CRITICAL,
            )

            val dashboard = controller.dashboard(502, responseMode = "deep", requestedCandidates = 2, refreshOverview = true)

            assertEquals("protect_providers", dashboard.nextActions.first().actionType)
            assertEquals("critical", dashboard.nextActions.first().severity)
            assertTrue(
                dashboard.nextActions
                    .first()
                    .description
                    .contains("보호 정책"),
            )
        }

        @Test
        fun `dashboard combines network status quality overload model map and customization slices`() {
            val channelAi = channelAis.save(ChannelAiEntity(guildId = 130, channelId = 230, displayName = "요약 니아"))
            behaviorVersions
                .save(
                    AiBehaviorVersionEntity(
                        channelAiId = channelAi.id,
                        version = 1,
                        purpose = "회의록 요약",
                        tone = "concise",
                        answerLength = "short",
                        safetyLevel = "strict",
                    ),
                ).also {
                    channelAi.activeBehaviorVersionId = it.id
                    channelAis.save(channelAi)
                }
            routingPolicies.save(
                ChannelAiRoutingPolicyEntity(
                    guildId = 130,
                    channelId = 230,
                    responseMode = "balanced",
                    preferredModel = "llama3.1:8b",
                ),
            )
            foundation.upsertProviderCapability(
                guildId = 130,
                providerUserId = 330,
                providerState = ProviderAvailability.ONLINE,
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("summary"),
                maxBurden = ModelBurden.STANDARD,
                maxConcurrency = 2,
                dailyLimit = 30,
                overloadRisk = OverloadRisk.NORMAL,
            )
            foundation.upsertProviderCapability(
                guildId = 130,
                providerUserId = 331,
                providerState = ProviderAvailability.OVERLOADED,
                modelNames = listOf("qwen-coder"),
                capabilityTags = listOf("coding"),
                maxBurden = ModelBurden.HEAVY,
                maxConcurrency = 1,
                dailyLimit = 5,
                overloadRisk = OverloadRisk.CRITICAL,
            )
            qualityFeedback.submit(
                guildId = 130,
                channelId = 230,
                requestId = "req-dashboard",
                userId = 44,
                rating = 1,
                feedbackType = "general",
                reason = "좋은 요약",
            )

            val dashboard =
                controller.dashboard(
                    130,
                    audience = "public",
                    responseMode = "deep",
                    requestedCandidates = 2,
                    refreshOverview = true,
                )

            assertEquals("network_overview_projection", dashboard.metadata.source)
            assertEquals(dashboard.overview.refreshedAt, dashboard.metadata.generatedAt)
            assertEquals("fresh", dashboard.metadata.freshnessStatus)
            assertEquals("warning", dashboard.overview.healthStatus)
            assertEquals(1, dashboard.channels.size)
            assertEquals("요약 니아", dashboard.channels.single().name)
            assertTrue(dashboard.modelMap.any { it.modelName == "llama3.1:8b" })
            assertEquals(1, dashboard.quality.feedbackCount)
            assertEquals(1, dashboard.quality.positive)
            assertEquals(1, dashboard.overload.highRiskCount)
            assertEquals("saving", dashboard.executionPlan.effectiveResponseMode)
            assertNull(dashboard.providers.first().providerUserId)
            val alert = dashboard.overload.alerts.single()
            assertNull(alert.providerUserId)
            assertEquals("Provider 1", alert.providerLabel)
            assertEquals("protected", alert.risk)
            assertNull(alert.maxConcurrency)
            assertNull(alert.dailyLimit)
            assertTrue(alert.message.contains("#331").not())
            assertEquals("protect_providers", dashboard.nextActions.first().actionType)
            assertTrue(dashboard.nextActions.any { it.actionType == "add_knowledge" })
        }
    }
