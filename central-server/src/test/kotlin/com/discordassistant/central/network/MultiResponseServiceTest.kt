package com.discordassistant.central.network

import com.discordassistant.central.dashboard.MultiResponseController
import com.discordassistant.central.dashboard.RecordCandidateRequest
import com.discordassistant.central.dashboard.SaveMultiResponsePolicyRequest
import com.discordassistant.central.dashboard.StartMultiResponseRunRequest
import com.discordassistant.central.dashboard.SynthesizeRunRequest
import com.discordassistant.central.persistence.CandidateAnswerRepository
import com.discordassistant.central.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.persistence.MultiResponseRunRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.persistence.SynthesisResultRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    ) {
        private val service =
            MultiResponseService(
                policies = policies,
                runs = runs,
                candidates = candidates,
                syntheses = syntheses,
                providerCapabilities = providerCapabilities,
                clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
            )
        private val controller = MultiResponseController(service)

        @Test
        fun `multi response run plans safe candidates and completes synthesis`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 1,
                    providerState = "ONLINE",
                    modelCount = 2,
                    modelNames = "llama3.1:8b,qwen-coder",
                    capabilityTags = "coding",
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
            assertEquals("completed", runs.findById(runId).get().status)
            assertEquals(first.id, runs.findById(runId).get().selectedCandidateId)
            assertNotNull(syntheses.findByRunId(runId))
            assertEquals(1, controller.recentRuns(100).size)
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
    }
