package com.discordassistant.central.network

import com.discordassistant.central.dashboard.CompleteBestMultiResponseRunRequest
import com.discordassistant.central.dashboard.MultiResponseController
import com.discordassistant.central.dashboard.RecordCandidateRequest
import com.discordassistant.central.dashboard.SaveMultiResponsePolicyRequest
import com.discordassistant.central.dashboard.StartMultiResponseRunRequest
import com.discordassistant.central.dashboard.SynthesizeRunRequest
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.AiNetworkEventRepository
import com.discordassistant.central.persistence.AiNetworkProfileRepository
import com.discordassistant.central.persistence.CandidateAnswerRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.persistence.MultiResponseRunRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.persistence.SynthesisResultRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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
                clock = fixedClock,
            )
        private val controller = MultiResponseController(service)

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
            val stats = controller.stats(100)
            assertEquals(1, stats["recentRunCount"])
            assertEquals(1, stats["completedRunCount"])
            assertEquals(2.0, stats["averageActualFanout"])
            assertEquals(1, controller.recentRuns(100).size)
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
            assertEquals(0, candidates.findByRunId(started["id"] as Long).size)
            assertEquals(
                "multi-response fan-out disabled for sensitive-looking prompt",
                runs.findById(started["id"] as Long).get().failureReason,
            )
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
