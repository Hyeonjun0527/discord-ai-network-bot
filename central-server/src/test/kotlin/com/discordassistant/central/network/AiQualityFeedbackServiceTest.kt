package com.discordassistant.central.network

import com.discordassistant.central.dashboard.AiQualityFeedbackController
import com.discordassistant.central.dashboard.SubmitAiFeedbackRequest
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.CandidateAnswerEntity
import com.discordassistant.central.persistence.CandidateAnswerRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.MultiResponseRunEntity
import com.discordassistant.central.persistence.MultiResponseRunRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
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
class AiQualityFeedbackServiceTest
    @Autowired
    constructor(
        private val feedbacks: AiFeedbackRepository,
        private val channelAis: ChannelAiRepository,
        private val candidates: CandidateAnswerRepository,
        private val runs: MultiResponseRunRepository,
        private val providerCapabilities: ProviderCapabilityProfileRepository,
    ) {
        private val service =
            AiQualityFeedbackService(
                feedbacks = feedbacks,
                channelAis = channelAis,
                candidateAnswers = candidates,
                providerCapabilities = providerCapabilities,
                clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
            )
        private val controller = AiQualityFeedbackController(service)

        @Test
        fun `feedback summary counts positive negative and reports without answer body`() {
            val channelAi = channelAis.save(ChannelAiEntity(guildId = 100, channelId = 200, displayName = "코드냥"))

            controller.submit(
                100,
                200,
                SubmitAiFeedbackRequest(requestId = "r1", userId = 1, rating = 1, reason = "좋음"),
            )
            controller.submit(
                100,
                200,
                SubmitAiFeedbackRequest(
                    requestId = "r2",
                    userId = 2,
                    rating = -1,
                    feedbackType = "report",
                    reason = "부정확",
                ),
            )
            val summary = controller.channelSummary(100, 200)

            assertEquals(2, summary.feedbackCount)
            assertEquals(1, summary.positive)
            assertEquals(1, summary.negative)
            assertEquals(1, summary.reports)
            assertEquals(channelAi.id, feedbacks.findTop20ByGuildIdAndChannelIdOrderByCreatedAtDesc(100, 200).first().channelAiId)
        }

        @Test
        fun `model and candidate quality summaries are exposed`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 1,
                    providerState = "ONLINE",
                    modelCount = 2,
                    modelNames = "llama3.1:8b,qwen-coder",
                    qualityTier = "specialized",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 2,
                    providerState = "ONLINE",
                    modelCount = 1,
                    modelNames = "llama3.1:8b",
                    qualityTier = "standard",
                    overloadRisk = "high",
                ),
            )
            val run = runs.save(MultiResponseRunEntity(guildId = 100, channelId = 200, requestId = "run-55"))
            candidates.save(
                CandidateAnswerEntity(
                    runId = run.id,
                    providerUserId = 1,
                    modelName = "qwen-coder",
                    status = "completed",
                    qualityScore = 90,
                    safetyFlags = "ok",
                    latencyMs = 1000,
                ),
            )

            val models = controller.modelQuality(100)
            val candidateQuality = controller.candidateQuality(run.id)

            assertEquals("llama3.1:8b", models.first().modelName)
            assertTrue(models.any { it.modelName == "llama3.1:8b" && it.overloadRiskCount == 1 })
            assertEquals(90, candidateQuality.single().qualityScore)
        }
    }
