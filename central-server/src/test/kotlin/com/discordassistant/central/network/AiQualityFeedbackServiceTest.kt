package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.adapter.inbound.web.AiQualityFeedbackController
import com.discordassistant.central.ainetwork.adapter.inbound.web.ResolveAiFeedbackRequest
import com.discordassistant.central.ainetwork.adapter.inbound.web.SubmitAiFeedbackRequest
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.application.AiQualityFeedbackService
import com.discordassistant.central.ainetwork.domain.model.FeedbackStatus
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunRepository
import com.discordassistant.central.multiresponse.domain.model.CandidateStatus
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
        fun `feedback submit deduplicates request user and redacts sensitive reason`() {
            val first =
                controller.submit(
                    100,
                    200,
                    SubmitAiFeedbackRequest(
                        requestId = "req-1",
                        userId = 9,
                        rating = -1,
                        feedbackType = "report",
                        reason = "token=abc123 응답이 이상함",
                    ),
                )
            val second =
                controller.submit(
                    100,
                    200,
                    SubmitAiFeedbackRequest(
                        requestId = "req-1",
                        userId = 9,
                        rating = 1,
                        reason = "다시 제출",
                    ),
                )

            val saved = feedbacks.findTop20ByGuildIdAndChannelIdOrderByCreatedAtDesc(100, 200).single()
            val guildSummary = controller.guildSummary(100)

            assertEquals(first["id"], second["id"])
            assertEquals("needs_review", first["status"])
            assertEquals("[redacted] 응답이 이상함", saved.reason)
            assertEquals(1, guildSummary.feedbackCount)
            assertEquals(1, guildSummary.openReports)
            assertEquals(listOf("[redacted] 응답이 이상함"), guildSummary.recentReasons)
        }

        @Test
        fun `feedback metadata never stores sensitive request identifiers or types`() {
            val first =
                controller.submit(
                    100,
                    203,
                    SubmitAiFeedbackRequest(
                        requestId = "password=super-secret",
                        userId = 9,
                        rating = -1,
                        feedbackType = "report token=hidden",
                        reason = "sk-123456789012345678901234567890",
                    ),
                )
            val second =
                controller.submit(
                    100,
                    203,
                    SubmitAiFeedbackRequest(
                        requestId = "password=super-secret",
                        userId = 9,
                        rating = 1,
                        feedbackType = "general",
                        reason = "중복",
                    ),
                )

            val saved = feedbacks.findTop20ByGuildIdAndChannelIdOrderByCreatedAtDesc(100, 203).single()

            assertEquals(first["id"], second["id"])
            assertTrue(saved.requestId!!.startsWith("redacted-"))
            assertEquals("report", saved.feedbackType)
            assertEquals("[redacted]", saved.reason)
            assertEquals(FeedbackStatus.NEEDS_REVIEW, saved.status)
        }

        @Test
        fun `quality review summary exposes open reports and resolves without raw answer body`() {
            val report =
                controller.submit(
                    100,
                    201,
                    SubmitAiFeedbackRequest(
                        requestId = "report-1",
                        userId = 10,
                        rating = -1,
                        feedbackType = "report",
                        reason = "api_key=abc 응답이 위험함",
                    ),
                )
            controller.submit(
                100,
                202,
                SubmitAiFeedbackRequest(
                    requestId = "report-2",
                    userId = 11,
                    rating = -1,
                    feedbackType = "report",
                    reason = "부정확",
                ),
            )

            val summary = controller.reviewSummary(100)
            assertEquals(2, summary.openReportCount)
            assertEquals(2, summary.affectedChannelCount)
            assertEquals("[redacted] 응답이 위험함", summary.queue.first { it.requestId == "report-1" }.reason)
            assertTrue(summary.topChannels.any { it.channelId == 201L && it.openReports == 1 })

            val resolved =
                controller.resolveFeedback(
                    100,
                    report["id"] as Long,
                    ResolveAiFeedbackRequest(
                        status = "resolved",
                        reviewerUserId = 77,
                        resolutionReason = "token=abc 조치 완료",
                    ),
                )
            val saved = feedbacks.findByGuildIdAndId(100, report["id"] as Long)!!

            assertEquals("resolved", resolved["status"])
            assertEquals(77L, resolved["reviewedBy"])
            assertEquals("[redacted] 조치 완료", saved.resolutionReason)
            assertEquals(1, controller.reviewSummary(100).openReportCount)
        }

        @Test
        fun `model and candidate quality summaries are exposed`() {
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 1,
                    providerState = ProviderAvailability.ONLINE,
                    modelCount = 2,
                    modelNames = "llama3.1:8b,qwen-coder",
                    qualityTier = ModelQualityTier.SPECIALIZED,
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 2,
                    providerState = ProviderAvailability.ONLINE,
                    modelCount = 1,
                    modelNames = "llama3.1:8b",
                    qualityTier = ModelQualityTier.STANDARD,
                    overloadRisk = OverloadRisk.HIGH,
                ),
            )
            val run = runs.save(MultiResponseRunEntity(guildId = 100, channelId = 200, requestId = "run-55"))
            candidates.save(
                CandidateAnswerEntity(
                    runId = run.id,
                    providerUserId = 1,
                    modelName = "qwen-coder",
                    status = CandidateStatus.COMPLETED,
                    qualityScore = 90,
                    safetyFlags = "ok",
                    latencyMs = 1000,
                ),
            )

            val models = controller.modelQuality(100)
            val publicCandidateQuality = controller.candidateQuality(run.id)
            val adminCandidateQuality = controller.candidateQuality(run.id, audience = "admin")

            assertEquals("llama3.1:8b", models.first().modelName)
            assertTrue(models.any { it.modelName == "llama3.1:8b" && it.overloadRiskCount == 1 })
            assertEquals(90, publicCandidateQuality.single().qualityScore)
            assertEquals(null, publicCandidateQuality.single().providerUserId)
            assertEquals("Provider 1", publicCandidateQuality.single().providerLabel)
            assertEquals(1L, adminCandidateQuality.single().providerUserId)
            assertEquals("provider:1", adminCandidateQuality.single().providerLabel)
        }
    }
