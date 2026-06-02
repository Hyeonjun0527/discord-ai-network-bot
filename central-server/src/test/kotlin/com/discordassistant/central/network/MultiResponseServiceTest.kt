package com.discordassistant.central.network

import com.discordassistant.central.dashboard.AdoptCandidateRequest
import com.discordassistant.central.dashboard.CompleteBestMultiResponseRunRequest
import com.discordassistant.central.dashboard.MultiResponseController
import com.discordassistant.central.dashboard.PseudoStreamPlanRequest
import com.discordassistant.central.dashboard.RecordCandidateRequest
import com.discordassistant.central.dashboard.SaveMultiResponsePolicyRequest
import com.discordassistant.central.dashboard.StartMultiResponseRunRequest
import com.discordassistant.central.dashboard.SynthesizeRunRequest
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.AiNetworkEventRepository
import com.discordassistant.central.persistence.AiNetworkProfileRepository
import com.discordassistant.central.persistence.CandidateAnswerRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.persistence.MultiResponseRunRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.persistence.SynthesisResultRepository
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.ProviderHelloFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class BusyProbeConnection : AgentConnection {
    override val remoteId: String = "busy-probe"

    override fun sendFrame(frame: Frame) = Unit

    override fun close(reason: String) = Unit
}

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MultiResponseServiceTest
    @Autowired
    constructor(
        private val policies: MultiResponsePolicyRepository,
        private val runs: MultiResponseRunRepository,
        private val candidates: CandidateAnswerRepository,
        private val syntheses: SynthesisResultRepository,
        private val providerCapabilities: ProviderCapabilityProfileRepository,
        private val networkProfiles: AiNetworkProfileRepository,
        private val knowledgeSpaces: KnowledgeSpaceRepository,
        private val knowledgeSources: KnowledgeSourceRepository,
        private val overviewProjections: NetworkOverviewProjectionRepository,
        private val channelAis: ChannelAiRepository,
        private val feedbacks: AiFeedbackRepository,
        private val events: AiNetworkEventRepository,
    ) {
        private val fixedClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
        private val service =
            MultiResponseService(
                policies = policies,
                runs = runs,
                candidates = candidates,
                syntheses = syntheses,
                providerCapabilities = providerCapabilities,
                feedbacks = feedbacks,
                clock = fixedClock,
            )
        private val controller = MultiResponseController(service)

        private fun ragAwareController(): MultiResponseController {
            val ingestion =
                KnowledgeIngestionService(
                    spaces = knowledgeSpaces,
                    sources = knowledgeSources,
                    clock = fixedClock,
                )
            val search = KnowledgeSearchService(knowledgeSources, knowledgeSpaces)
            return MultiResponseController(
                MultiResponseService(
                    policies = policies,
                    runs = runs,
                    candidates = candidates,
                    syntheses = syntheses,
                    providerCapabilities = providerCapabilities,
                    clock = fixedClock,
                    knowledgeSearch = search,
                ),
            ).also {
                val space = ingestion.createSpace(100, 207, null, "다중응답 지식", 77, null, null)
                val source =
                    ingestion.addSource(
                        guildId = 100,
                        spaceId = space.id,
                        sourceType = "link",
                        title = "Kotlin Spring 운영 가이드",
                        sourceUri = "https://example.com/kotlin-spring.md",
                        contentPreview = "운영",
                        addedBy = 77,
                    )
                ingestion.markSourceIndexed(100, space.id, source.id, chunkCount = 1)
            }
        }

        private fun safetyAwareService(): MultiResponseService {
            val foundation =
                AiNetworkFoundationService(
                    networkProfiles = networkProfiles,
                    providerCapabilities = providerCapabilities,
                    knowledgeSpaces = knowledgeSpaces,
                    overviewProjections = overviewProjections,
                    channelAis = channelAis,
                    feedbacks = feedbacks,
                    clock = fixedClock,
                )
            val safety = ProviderSafetyService(providerCapabilities, events, foundation, fixedClock)
            return MultiResponseService(
                policies = policies,
                runs = runs,
                candidates = candidates,
                syntheses = syntheses,
                providerCapabilities = providerCapabilities,
                clock = fixedClock,
                safety = safety,
            )
        }

        @Test
        fun `multi response policy enforces configured global fanout cap`() {
            val saved =
                controller.savePolicy(
                    100,
                    SaveMultiResponsePolicyRequest(channelId = 199, mode = "compare", maxCandidates = 10, synthesisEnabled = true),
                )

            assertEquals(2, saved["maxCandidates"])
            assertEquals(2, policies.findByGuildIdAndChannelId(100, 199)!!.maxCandidates)
        }

        @Test
        fun `configured max fanout limits saved policies and provider selection`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 101,
                    providerUserId = 11,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3.1:8b",
                    capabilityTags = "multi-response",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 101,
                    providerUserId = 12,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "mistral",
                    capabilityTags = "multi-response",
                    overloadRisk = "normal",
                ),
            )
            val cappedService =
                MultiResponseService(
                    policies = policies,
                    runs = runs,
                    candidates = candidates,
                    syntheses = syntheses,
                    providerCapabilities = providerCapabilities,
                    clock = fixedClock,
                    featureGate = AiNetworkFeatureGate(multiResponseMaxFanout = 1),
                )
            val cappedController = MultiResponseController(cappedService)

            val saved =
                cappedController.savePolicy(
                    101,
                    SaveMultiResponsePolicyRequest(channelId = 201, mode = "compare", maxCandidates = 5, synthesisEnabled = true),
                )
            val started = cappedController.startRun(101, StartMultiResponseRunRequest(channelId = 201, requestId = "fanout-1"))

            assertEquals(1, saved["maxCandidates"])
            assertEquals(false, policies.findByGuildIdAndChannelId(101, 201)!!.synthesisEnabled)
            assertEquals(1, started["candidateCount"])
            assertEquals(1, candidates.findByRunId(started["id"] as Long).size)
        }

        @Test
        fun `provider at live concurrency limit is excluded from fanout candidates`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 152,
                    providerUserId = 201,
                    providerState = "ONLINE",
                    modelCount = 2,
                    modelNames = "llama3.1:8b,qwen-coder",
                    capabilityTags = "multi-response",
                    qualityTier = "specialized",
                    maxConcurrency = 1,
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 152,
                    providerUserId = 202,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "mistral",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    maxConcurrency = 1,
                    overloadRisk = "normal",
                ),
            )
            val registry = ConnectionRegistry()
            val busySession = ProviderSession(BusyProbeConnection(), providerId = 201, guildId = 152)
            registry.register(busySession)
            busySession.sendInfer("keep provider busy")
            val capacityAwareService =
                MultiResponseService(
                    policies = policies,
                    runs = runs,
                    candidates = candidates,
                    syntheses = syntheses,
                    providerCapabilities = providerCapabilities,
                    clock = fixedClock,
                    connectionRegistry = registry,
                )
            val capacityAwareController = MultiResponseController(capacityAwareService)
            capacityAwareController.savePolicy(
                152,
                SaveMultiResponsePolicyRequest(channelId = 252, mode = "compare", maxCandidates = 2, synthesisEnabled = true),
            )

            val started = capacityAwareController.startRun(152, StartMultiResponseRunRequest(channelId = 252, requestId = "busy-provider"))
            val planned = candidates.findByRunId(started["id"] as Long)

            assertEquals(1, started["candidateCount"])
            assertEquals(listOf(202L), planned.map { it.providerUserId })
        }

        @Test
        fun `provider with exhausted live daily limit is excluded from fanout candidates`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 153,
                    providerUserId = 301,
                    providerState = "ONLINE",
                    modelCount = 2,
                    modelNames = "llama3.1:8b,qwen-coder",
                    capabilityTags = "multi-response",
                    qualityTier = "specialized",
                    maxConcurrency = 2,
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 153,
                    providerUserId = 302,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "mistral",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    maxConcurrency = 1,
                    overloadRisk = "normal",
                ),
            )
            val registry = ConnectionRegistry()
            val exhaustedSession = ProviderSession(BusyProbeConnection(), providerId = 301, guildId = 153)
            exhaustedSession.applyHello(
                ProviderHelloFrame(
                    models = listOf("llama3.1:8b", "qwen-coder"),
                    maxConcurrency = 2,
                    remainingDailyRequests = 1,
                ),
            )
            registry.register(exhaustedSession)
            exhaustedSession.sendInfer("consume last daily slot")
            val capacityAwareService =
                MultiResponseService(
                    policies = policies,
                    runs = runs,
                    candidates = candidates,
                    syntheses = syntheses,
                    providerCapabilities = providerCapabilities,
                    clock = fixedClock,
                    connectionRegistry = registry,
                )
            val capacityAwareController = MultiResponseController(capacityAwareService)
            capacityAwareController.savePolicy(
                153,
                SaveMultiResponsePolicyRequest(channelId = 253, mode = "compare", maxCandidates = 2, synthesisEnabled = true),
            )

            val started =
                capacityAwareController.startRun(
                    153,
                    StartMultiResponseRunRequest(channelId = 253, requestId = "daily-exhausted"),
                )
            val planned = candidates.findByRunId(started["id"] as Long)

            assertEquals(1, started["candidateCount"])
            assertEquals(listOf(302L), planned.map { it.providerUserId })
        }

        @Test
        fun `multi response run plans safe candidates and completes synthesis`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 1,
                    providerState = "ONLINE",
                    modelCount = 2,
                    modelNames = "llama3.1:8b,qwen-coder",
                    capabilityTags = "coding,multi-response",
                    qualityTier = "specialized",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 2,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "mistral",
                    qualityTier = "standard",
                    capabilityTags = "multi-response",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 3,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "overloaded",
                    qualityTier = "high",
                    overloadRisk = "high",
                ),
            )

            val policy =
                controller.savePolicy(
                    100,
                    SaveMultiResponsePolicyRequest(
                        channelId = 200,
                        mode = "compare",
                        maxCandidates = 2,
                        synthesisEnabled = true,
                    ),
                )
            assertEquals(2, policy["maxCandidates"])

            val started = controller.startRun(100, StartMultiResponseRunRequest(channelId = 200, requestId = "req-1"))
            val runId = started["id"] as Long
            assertEquals("running", started["status"])
            assertEquals(2, started["candidateCount"])
            val planned = candidates.findByRunId(runId)
            assertEquals(listOf(1L, 2L), planned.map { it.providerUserId })

            val first = planned.first()
            controller.recordCandidate(
                runId,
                first.id,
                RecordCandidateRequest(answerRef = "answer:req-1:a", qualityScore = 90, safetyFlags = listOf("ok")),
            )
            val synthesis =
                controller.synthesize(
                    runId,
                    SynthesizeRunRequest(answerRef = "answer:req-1:final", selectedCandidateIds = listOf(first.id)),
                )

            assertEquals("completed", synthesis["status"])
            assertEquals("best_by_heuristic", synthesis["strategy"])
            assertEquals("no candidate safety flags", synthesis["safetySummary"])
            assertEquals("completed", runs.findById(runId).get().status)
            assertEquals(first.id, runs.findById(runId).get().selectedCandidateId)
            assertNotNull(syntheses.findByRunId(runId))
            assertEquals("no candidate safety flags", syntheses.findByRunId(runId)?.safetySummary)
            val detail = controller.runDetail(runId)
            assertEquals("completed", detail["status"])
            assertEquals("avg=90.0, best=90, scored=1", detail["qualitySummary"])
            assertEquals(2, (detail["candidates"] as List<*>).size)
            val publicCandidate = (detail["candidates"] as List<*>).first() as Map<*, *>
            assertEquals(null, publicCandidate["providerUserId"])
            assertTrue(publicCandidate["providerLabel"].toString().startsWith("Provider "))
            assertTrue(!publicCandidate.containsKey("answerRef"))
            assertTrue(!(detail["synthesis"] as Map<*, *>).containsKey("answerRef"))
            val adminDetail = controller.runDetail(runId, audience = "admin")
            val adminCandidate = (adminDetail["candidates"] as List<*>).first() as Map<*, *>
            assertEquals(1L, adminCandidate["providerUserId"])
            assertEquals("answer:req-1:a", adminCandidate["answerRef"])
            val stats = controller.stats(100)
            assertEquals(1, stats["recentRunCount"])
            assertEquals(1, stats["completedRunCount"])
            assertEquals(2.0, stats["averageActualFanout"])
            assertEquals(1, controller.recentRuns(100).size)
        }

        @Test
        fun `multi response run never stores sensitive request id`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 4,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(100, SaveMultiResponsePolicyRequest(channelId = 206, mode = "compare", maxCandidates = 1))

            val started =
                controller.startRun(
                    100,
                    StartMultiResponseRunRequest(
                        channelId = 206,
                        requestId = "DISCORD_BOT_TOKEN=super-secret-value",
                    ),
                )

            val run = runs.findById(started["id"] as Long).get()
            assertTrue(run.requestId.startsWith("redacted-"))
            assertTrue(!run.requestId.contains("super-secret-value"))
            assertEquals(run.requestId, controller.recentRuns(100).first()["requestId"])
        }

        @Test
        fun `user can adopt candidate and leave quality feedback without storing answer body`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 41,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(
                100,
                SaveMultiResponsePolicyRequest(
                    channelId = 201,
                    mode = "compare",
                    maxCandidates = 1,
                    synthesisEnabled = true,
                ),
            )
            val started = controller.startRun(100, StartMultiResponseRunRequest(channelId = 201, requestId = "adopt-1"))
            val runId = started["id"] as Long
            val candidate = candidates.findByRunId(runId).single()
            controller.recordCandidate(
                runId,
                candidate.id,
                RecordCandidateRequest(answerRef = "answer:adopt-1:a", status = "completed", qualityScore = 70),
            )

            val adopted =
                controller.adoptCandidate(
                    runId,
                    candidate.id,
                    AdoptCandidateRequest(userId = 99, rating = 1, reason = "token=abc123 이 답이 제일 정확"),
                )

            assertEquals("completed", adopted["status"])
            assertEquals(candidate.id, adopted["selectedCandidateId"])
            assertEquals(100, adopted["candidateQualityScore"])
            assertNotNull(adopted["synthesisId"])
            assertNotNull(adopted["feedbackId"])
            assertEquals(candidate.id, runs.findById(runId).get().selectedCandidateId)
            assertEquals("user_selected_candidate", syntheses.findByRunId(runId)?.strategy)
            val feedback = feedbacks.findTop20ByGuildIdAndChannelIdOrderByCreatedAtDesc(100, 201).single()
            assertEquals("candidate_adoption", feedback.feedbackType)
            assertEquals("[redacted] 이 답이 제일 정확", feedback.reason)
            assertEquals("adopt-1", feedback.requestId)
        }

        @Test
        fun `provider fanout load summarizes timeout latency quality and risk`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 71,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 72,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "qwen",
                    capabilityTags = "multi-response",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(
                100,
                SaveMultiResponsePolicyRequest(channelId = 202, mode = "compare", maxCandidates = 2, synthesisEnabled = true),
            )
            val firstRunId = controller.startRun(100, StartMultiResponseRunRequest(channelId = 202, requestId = "load-1"))["id"] as Long
            val firstCandidates = candidates.findByRunId(firstRunId)
            controller.recordCandidate(
                firstRunId,
                firstCandidates.first { it.providerUserId == 71L }.id,
                RecordCandidateRequest(answerRef = "answer:load-1:ok", status = "completed", latencyMs = 1200, qualityScore = 90),
            )
            controller.recordCandidate(
                firstRunId,
                firstCandidates.first { it.providerUserId == 72L }.id,
                RecordCandidateRequest(status = "timeout", latencyMs = 15_000),
            )
            val secondRunId = controller.startRun(100, StartMultiResponseRunRequest(channelId = 202, requestId = "load-2"))["id"] as Long
            val secondCandidates = candidates.findByRunId(secondRunId)
            controller.recordCandidate(
                secondRunId,
                secondCandidates.first { it.providerUserId == 71L }.id,
                RecordCandidateRequest(answerRef = "answer:load-2:ok", status = "completed", latencyMs = 1400, qualityScore = 80),
            )
            controller.recordCandidate(
                secondRunId,
                secondCandidates.first { it.providerUserId == 72L }.id,
                RecordCandidateRequest(status = "failed", latencyMs = 12_000),
            )

            val publicLoad = controller.providerLoad(100)
            assertEquals(null, publicLoad.first().providerUserId)
            assertTrue(publicLoad.first().providerLabel.startsWith("Provider "))

            val load = controller.providerLoad(100, audience = "admin")
            val risky = load.first()
            val stable = load.first { it.providerUserId == 71L }

            assertEquals(72L, risky.providerUserId)
            assertEquals("critical", risky.loadRisk)
            assertEquals(1, risky.timeoutCount)
            assertEquals(1, risky.failedCount)
            assertEquals("normal", stable.loadRisk)
            assertEquals(2, stable.completedCount)
            assertEquals(85.0, stable.averageQualityScore)
        }

        @Test
        fun `complete best effort uses successful candidate when another candidate times out`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 51,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 52,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "qwen",
                    capabilityTags = "multi-response",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(
                100,
                SaveMultiResponsePolicyRequest(channelId = 205, mode = "compare", maxCandidates = 2, synthesisEnabled = true),
            )
            val started = controller.startRun(100, StartMultiResponseRunRequest(channelId = 205, requestId = "req-best-effort"))
            val runId = started["id"] as Long
            val planned = candidates.findByRunId(runId)
            val good = planned.first { it.providerUserId == 51L }
            val timedOut = planned.first { it.providerUserId == 52L }
            controller.recordCandidate(
                runId,
                good.id,
                RecordCandidateRequest(answerRef = "answer:req-best-effort:good", qualityScore = 88, latencyMs = 900),
            )
            controller.recordCandidate(
                runId,
                timedOut.id,
                RecordCandidateRequest(status = "timeout", latencyMs = 5000),
            )

            val completed = controller.completeBest(runId, CompleteBestMultiResponseRunRequest())

            assertEquals("completed", completed["status"])
            assertEquals(good.id, completed["selectedCandidateId"])
            assertEquals("answer:req-best-effort:good", completed["answerRef"])
            assertEquals("completed", runs.findById(runId).get().status)
            assertEquals(good.id, runs.findById(runId).get().selectedCandidateId)
            assertEquals("best_successful_candidate", syntheses.findByRunId(runId)?.strategy)
        }

        @Test
        fun `complete best effort fails run when all candidates fail or are unsafe`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 61,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(100, SaveMultiResponsePolicyRequest(channelId = 206, mode = "compare", maxCandidates = 1))
            val started = controller.startRun(100, StartMultiResponseRunRequest(channelId = 206, requestId = "req-all-fail"))
            val runId = started["id"] as Long
            val planned = candidates.findByRunId(runId).single()
            controller.recordCandidate(
                runId,
                planned.id,
                RecordCandidateRequest(answerRef = "answer:req-all-fail:unsafe", safetyFlags = listOf("unsafe"), qualityScore = 99),
            )

            val completed = controller.completeBest(runId, CompleteBestMultiResponseRunRequest())

            assertEquals("failed", completed["status"])
            assertNotNull(completed["fallbackReason"])
            assertEquals("failed", runs.findById(runId).get().status)
            assertEquals(null, syntheses.findByRunId(runId))
        }

        @Test
        fun `synthesis rejects candidate ids from another run`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 41,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(100, SaveMultiResponsePolicyRequest(channelId = 204, mode = "compare", maxCandidates = 1))
            val firstRun = controller.startRun(100, StartMultiResponseRunRequest(channelId = 204, requestId = "req-a"))
            val secondRun = controller.startRun(100, StartMultiResponseRunRequest(channelId = 204, requestId = "req-b"))
            val foreignCandidate = candidates.findByRunId(firstRun["id"] as Long).first()

            assertThrows(IllegalArgumentException::class.java) {
                controller.synthesize(
                    secondRun["id"] as Long,
                    SynthesizeRunRequest(answerRef = "answer:req-b:final", selectedCandidateIds = listOf(foreignCandidate.id)),
                )
            }
        }

        @Test
        fun `multi response run becomes no_provider when all candidates are unsafe`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 3,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "overloaded",
                    qualityTier = "high",
                    overloadRisk = "high",
                ),
            )
            controller.savePolicy(100, SaveMultiResponsePolicyRequest(channelId = 200, mode = "compare", maxCandidates = 3))

            val started = controller.startRun(100, StartMultiResponseRunRequest(channelId = 200, requestId = "req-2"))

            assertEquals("no_provider", started["status"])
            assertEquals(0, started["candidateCount"])
        }

        @Test
        fun `multi response requires provider fanout opt-in and can enforce distinct models`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 10,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 11,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 12,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "qwen",
                    capabilityTags = "coding",
                    qualityTier = "specialized",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(
                100,
                SaveMultiResponsePolicyRequest(
                    channelId = 201,
                    mode = "compare",
                    maxCandidates = 3,
                    requireDistinctModels = true,
                ),
            )

            val started = controller.startRun(100, StartMultiResponseRunRequest(channelId = 201, requestId = "req-distinct"))

            assertEquals("running", started["status"])
            assertEquals(1, started["candidateCount"])
            val planned = candidates.findByRunId(started["id"] as Long)
            assertEquals(listOf(10L), planned.map { it.providerUserId })
        }

        @Test
        fun `multi response blocks sensitive prompts before provider fanout`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 20,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(100, SaveMultiResponsePolicyRequest(channelId = 202, mode = "compare", maxCandidates = 2))

            val started =
                controller.startRun(
                    100,
                    StartMultiResponseRunRequest(
                        channelId = 202,
                        requestId = "req-sensitive",
                        promptPreview = "내 DISCORD_BOT_TOKEN=abc 를 분석해줘",
                    ),
                )

            assertEquals("blocked_sensitive", started["status"])
            assertEquals("skipped_sensitive_prompt", started["ragContextStatus"])
            assertEquals(0, candidates.findByRunId(started["id"] as Long).size)
            val sensitiveRun = runs.findById(started["id"] as Long).get()
            assertEquals("skipped_sensitive_prompt", sensitiveRun.ragContextStatus)
            assertEquals(
                "multi-response fan-out disabled for sensitive-looking prompt",
                sensitiveRun.failureReason,
            )

            val passwordOnly =
                controller.startRun(
                    100,
                    StartMultiResponseRunRequest(
                        channelId = 202,
                        requestId = "req-sensitive-password",
                        promptPreview = "이 password 값이 안전한지 여러 모델로 비교해줘",
                    ),
                )
            assertEquals("blocked_sensitive", passwordOnly["status"])
            assertEquals("skipped_sensitive_prompt", passwordOnly["ragContextStatus"])
            assertEquals(0, candidates.findByRunId(passwordOnly["id"] as Long).size)
        }

        @Test
        fun `provider fanout opt-out is reflected on the next run`() {
            val provider =
                providerCapabilities.save(
                    ProviderCapabilityProfileEntity(
                        guildId = 100,
                        providerUserId = 21,
                        providerState = "ONLINE",
                        modelCount = 1,
                        modelNames = "llama3",
                        capabilityTags = "multi-response",
                        qualityTier = "high",
                        overloadRisk = "normal",
                    ),
                )
            controller.savePolicy(100, SaveMultiResponsePolicyRequest(channelId = 204, mode = "compare", maxCandidates = 2))

            val beforeOptOut = controller.startRun(100, StartMultiResponseRunRequest(channelId = 204, requestId = "req-opt-in"))
            assertEquals("running", beforeOptOut["status"])
            assertEquals(listOf(21L), candidates.findByRunId(beforeOptOut["id"] as Long).map { it.providerUserId })

            provider.capabilityTags = "coding"
            providerCapabilities.saveAndFlush(provider)

            val afterOptOut = controller.startRun(100, StartMultiResponseRunRequest(channelId = 204, requestId = "req-opt-out"))
            assertEquals("no_provider", afterOptOut["status"])
            assertEquals(0, afterOptOut["candidateCount"])
            assertEquals(0, candidates.findByRunId(afterOptOut["id"] as Long).size)
        }

        @Test
        fun `multi response blocks fanout when any provider has critical overload`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 30,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 31,
                    providerState = "OVERLOADED",
                    modelCount = 1,
                    modelNames = "qwen",
                    capabilityTags = "multi-response",
                    qualityTier = "specialized",
                    overloadRisk = "critical",
                ),
            )
            controller.savePolicy(100, SaveMultiResponsePolicyRequest(channelId = 203, mode = "compare", maxCandidates = 2))

            val started = controller.startRun(100, StartMultiResponseRunRequest(channelId = 203, requestId = "req-critical"))

            assertEquals("no_provider", started["status"])
            assertEquals(0, started["candidateCount"])
        }

        @Test
        fun `multi response snapshots one scoped RAG context before provider fanout`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 40,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            val ragController = ragAwareController()
            ragController.savePolicy(100, SaveMultiResponsePolicyRequest(channelId = 207, mode = "compare", maxCandidates = 2))

            val started =
                ragController.startRun(
                    100,
                    StartMultiResponseRunRequest(
                        channelId = 207,
                        requestId = "req-rag",
                        promptPreview = "Kotlin Spring 설정",
                        responseMode = "deep",
                    ),
                )

            assertEquals("running", started["status"])
            assertEquals("ready", started["ragContextStatus"])
            assertEquals(1, started["candidateCount"])
            val run = runs.findById(started["id"] as Long).get()
            assertEquals("ready", run.ragContextStatus)
            assertNotNull(run.ragContextSourceIds)
            assertEquals(1, run.ragContextSourceIds!!.split(",").size)
            assertEquals(1, candidates.findByRunId(run.id).size)
        }

        @Test
        fun `multi response uses provider safety plan to degrade fanout before selecting candidates`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 300,
                    providerUserId = 101,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 300,
                    providerUserId = 102,
                    providerState = "OVERLOADED",
                    modelCount = 1,
                    modelNames = "qwen",
                    capabilityTags = "multi-response",
                    qualityTier = "specialized",
                    overloadRisk = "high",
                ),
            )
            val safetyController = MultiResponseController(safetyAwareService())
            safetyController.savePolicy(
                300,
                SaveMultiResponsePolicyRequest(channelId = 230, mode = "compare", maxCandidates = 3, synthesisEnabled = true),
            )

            val started = safetyController.startRun(300, StartMultiResponseRunRequest(channelId = 230, requestId = "req-safe-plan"))

            assertEquals("running", started["status"])
            assertEquals(1, started["candidateCount"])
            assertEquals(listOf(101L), candidates.findByRunId(started["id"] as Long).map { it.providerUserId })
        }

        @Test
        fun `pseudo stream plan creates throttled edit snapshots without exceeding discord limits`() {
            val answer = "가".repeat(300)

            val response =
                controller.pseudoStreamPlan(
                    PseudoStreamPlanRequest(
                        answer = answer,
                        steps = listOf(33, 66, 100),
                        maxDiscordChars = 200,
                    ),
                )

            assertEquals(200, response["finalLength"])
            assertEquals(true, response["truncated"])
            assertEquals(1200, response["editIntervalMs"])
            assertEquals("discord_message_truncated_to_200", response["warning"])
            val snapshots = response["snapshots"] as List<*>
            assertEquals(3, snapshots.size)
            val first = snapshots[0] as PseudoStreamSnapshot
            val second = snapshots[1] as PseudoStreamSnapshot
            val final = snapshots[2] as PseudoStreamSnapshot
            assertEquals(33, first.percent)
            assertEquals(66, first.charCount)
            assertEquals(66, second.percent)
            assertEquals(132, second.charCount)
            assertEquals(100, final.percent)
            assertEquals(200, final.charCount)
            assertEquals(true, final.final)
        }

        @Test
        fun `multi response uses provider safety plan to block when no safe capacity remains`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 301,
                    providerUserId = 201,
                    providerState = "OVERLOADED",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "critical",
                ),
            )
            val safetyController = MultiResponseController(safetyAwareService())
            safetyController.savePolicy(301, SaveMultiResponsePolicyRequest(channelId = 231, mode = "compare", maxCandidates = 2))

            val started = safetyController.startRun(301, StartMultiResponseRunRequest(channelId = 231, requestId = "req-safe-block"))

            assertEquals("no_provider", started["status"])
            assertEquals(0, started["candidateCount"])
            assertEquals(0, candidates.findByRunId(started["id"] as Long).size)
            assertNotNull(runs.findById(started["id"] as Long).get().failureReason)
        }

        @Test
        fun `decision summary explains selected and timed out candidates`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 81,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 82,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "qwen",
                    capabilityTags = "multi-response",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(
                100,
                SaveMultiResponsePolicyRequest(channelId = 208, mode = "compare", maxCandidates = 2, synthesisEnabled = true),
            )
            val runId = controller.startRun(100, StartMultiResponseRunRequest(channelId = 208, requestId = "decision-1"))["id"] as Long
            val planned = candidates.findByRunId(runId)
            val selected = planned.first { it.providerUserId == 81L }
            val timedOut = planned.first { it.providerUserId == 82L }
            controller.recordCandidate(
                runId,
                selected.id,
                RecordCandidateRequest(answerRef = "answer:decision-1:selected", latencyMs = 800, qualityScore = 92),
            )
            controller.recordCandidate(
                runId,
                timedOut.id,
                RecordCandidateRequest(status = "timeout", latencyMs = 9000),
            )
            controller.synthesize(runId, SynthesizeRunRequest("answer:decision-1:final", listOf(selected.id)))

            val response = controller.decisionSummary(100, channelId = 208, limit = 10)

            assertEquals(1, response["recentRunCount"])
            assertEquals(1, response["completedRunCount"])
            assertEquals(2, response["totalCandidateCount"])
            assertEquals(1, response["acceptedCandidateCount"])
            assertEquals(1, response["timeoutCandidateCount"])
            assertEquals(92.0, response["averageQualityScore"])
            assertEquals(1.0, response["adoptionRate"])
            assertTrue((response["riskCodes"] as List<*>).contains("high_timeout_rate"))
            val statusCounts = response["statusCounts"] as Map<*, *>
            assertEquals(1, statusCounts["completed"])
            assertEquals(1, statusCounts["timeout"])
            val decisions = response["recentDecisions"] as List<*>
            val selectedDecision =
                decisions.first {
                    (it as MultiResponseDecisionItem).candidateId == selected.id
                } as MultiResponseDecisionItem
            val timeoutDecision =
                decisions.first {
                    (it as MultiResponseDecisionItem).candidateId == timedOut.id
                } as MultiResponseDecisionItem
            assertEquals(true, selectedDecision.selected)
            assertEquals("selected_by_best_by_heuristic", selectedDecision.reason)
            assertEquals(false, timeoutDecision.selected)
            assertEquals("candidate_timeout", timeoutDecision.reason)
        }

        @Test
        fun `operations summary combines decision quality provider load and next actions`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 420,
                    providerUserId = 181,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3",
                    capabilityTags = "multi-response",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 420,
                    providerUserId = 182,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "qwen",
                    capabilityTags = "multi-response",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            controller.savePolicy(
                420,
                SaveMultiResponsePolicyRequest(channelId = 520, mode = "compare", maxCandidates = 2, synthesisEnabled = true),
            )
            val runId = controller.startRun(420, StartMultiResponseRunRequest(channelId = 520, requestId = "ops-1"))["id"] as Long
            val planned = candidates.findByRunId(runId)
            val selected = planned.first { it.providerUserId == 181L }
            val timedOut = planned.first { it.providerUserId == 182L }
            controller.recordCandidate(
                runId,
                selected.id,
                RecordCandidateRequest(answerRef = "answer:ops-1:selected", latencyMs = 700, qualityScore = 94),
            )
            controller.recordCandidate(
                runId,
                timedOut.id,
                RecordCandidateRequest(status = "timeout", latencyMs = 12_000),
            )
            controller.synthesize(runId, SynthesizeRunRequest("answer:ops-1:final", listOf(selected.id)))

            val response = controller.operationsSummary(420, channelId = 520)
            val summary = response["summary"] as com.discordassistant.central.dashboard.MultiResponseOperationsDashboardResponse

            assertEquals("blocked", summary.status)
            assertEquals(false, summary.safeToEnableAdvanced)
            assertEquals(1, summary.recentRunCount)
            assertEquals(1, summary.completedRunCount)
            assertEquals(1, summary.acceptedCandidateCount)
            assertEquals(1, summary.timeoutCandidateCount)
            assertEquals(1, summary.criticalLoadProviderCount)
            assertTrue(summary.riskCodes.contains("high_timeout_rate"))
            assertTrue(summary.riskCodes.contains("provider_fanout_load_critical"))
            assertTrue(summary.nextActions.any { it.contains("과부하 Provider") })
            assertEquals(2, summary.providerLoads.size)
            val publicProviderLoad = summary.providerLoads.first()
            assertEquals(null, publicProviderLoad.providerUserId)
            assertTrue(publicProviderLoad.providerLabel.startsWith("Provider "))
            assertEquals(2, summary.decisionSummary.totalCandidateCount)

            val adminResponse = controller.operationsSummary(420, channelId = 520, audience = "admin")
            val adminSummary =
                adminResponse["summary"] as com.discordassistant.central.dashboard.MultiResponseOperationsDashboardResponse
            assertTrue(adminSummary.providerLoads.any { it.providerUserId == 182L })
        }

        @Test
        fun `synthesis feature flag blocks multi candidate synthesis without disabling best candidate selection`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 421,
                    providerUserId = 191,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3.1:8b",
                    capabilityTags = "multi-response",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 421,
                    providerUserId = 192,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "qwen-coder",
                    capabilityTags = "multi-response",
                    overloadRisk = "normal",
                ),
            )
            val noSynthesisService =
                MultiResponseService(
                    policies = policies,
                    runs = runs,
                    candidates = candidates,
                    syntheses = syntheses,
                    providerCapabilities = providerCapabilities,
                    clock = fixedClock,
                    featureGate = AiNetworkFeatureGate(multiResponseSynthesisEnabled = false, multiResponseMaxFanout = 2),
                )

            noSynthesisService.savePolicy(
                guildId = 421,
                channelId = 521,
                channelAiId = null,
                mode = "compare",
                maxCandidates = 2,
                requireDistinctModels = false,
                providerDailyLimit = 0,
                timeoutSeconds = 120,
                synthesisEnabled = true,
            )
            assertEquals(false, policies.findByGuildIdAndChannelId(421, 521)!!.synthesisEnabled)
            val run = noSynthesisService.startRun(421, 521, requestId = "no-synth")
            val planned = candidates.findByRunId(run.id)
            planned.forEachIndexed { index, candidate ->
                noSynthesisService.recordCandidate(
                    run.id,
                    candidate.id,
                    answerRef = "answer:${index + 1}",
                    status = "completed",
                    latencyMs = 100 + index,
                    safetyFlags = emptyList(),
                    qualityScore = 80 + index,
                )
            }

            assertThrows(IllegalStateException::class.java) {
                noSynthesisService.synthesize(run.id, "answer:final", planned.map { it.id }, strategy = "cross_model_synthesis")
            }
            val completed = noSynthesisService.completeBestEffort(run.id)
            assertEquals("completed", completed.run.status)
            assertNotNull(completed.synthesis)
        }

        @Test
        fun `multi response rag feature flag skips rag context without disabling run planning`() {
            val noRagService =
                MultiResponseService(
                    policies = policies,
                    runs = runs,
                    candidates = candidates,
                    syntheses = syntheses,
                    providerCapabilities = providerCapabilities,
                    clock = fixedClock,
                    featureGate = AiNetworkFeatureGate(multiResponseRagEnabled = false),
                    knowledgeSearch = KnowledgeSearchService(knowledgeSources, knowledgeSpaces),
                )

            val run =
                noRagService.startRuntimeObservation(
                    guildId = 422,
                    channelId = 522,
                    requestId = "rag-disabled",
                    promptPreview = "Kotlin 운영 가이드 알려줘",
                    responseMode = "deep",
                    maxCandidates = 1,
                )

            assertEquals("skipped_feature_disabled", run.ragContextStatus)
        }

        @Test
        fun `multi response kill switch blocks advanced fanout workflow`() {
            val disabledService =
                MultiResponseService(
                    policies = policies,
                    runs = runs,
                    candidates = candidates,
                    syntheses = syntheses,
                    providerCapabilities = providerCapabilities,
                    clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
                    featureGate = AiNetworkFeatureGate(multiResponseEnabled = false),
                )

            assertThrows(IllegalStateException::class.java) {
                disabledService.savePolicy(
                    guildId = 100,
                    channelId = 200,
                    channelAiId = null,
                    mode = "compare",
                    maxCandidates = 2,
                    requireDistinctModels = false,
                    providerDailyLimit = 0,
                    timeoutSeconds = 120,
                    synthesisEnabled = true,
                )
            }
        }
    }
