package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiFeedbackEntity
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.AiRequestEntity
import com.discordassistant.central.persistence.AiRequestRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
class ChannelAiRoutingPolicyServiceTest
    @Autowired
    constructor(
        private val policies: ChannelAiRoutingPolicyRepository,
        private val channelAis: ChannelAiRepository,
        private val providerCapabilities: ProviderCapabilityProfileRepository,
        private val feedbacks: AiFeedbackRepository,
        private val requests: AiRequestRepository,
    ) {
        private val service =
            ChannelAiRoutingPolicyService(
                policies = policies,
                channelAis = channelAis,
                providerCapabilities = providerCapabilities,
                feedbacks = feedbacks,
                requests = requests,
                clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
            )

        private fun disabledService() =
            ChannelAiRoutingPolicyService(
                policies = policies,
                channelAis = channelAis,
                providerCapabilities = providerCapabilities,
                feedbacks = feedbacks,
                requests = requests,
                clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
                featureGate = AiNetworkFeatureGate(channelAiEnabled = false),
            )

        @Test
        fun `channel ai routing feature gate blocks writes and falls back to default routing`() {
            val disabled = disabledService()

            assertThrows(IllegalStateException::class.java) {
                disabled.save(
                    guildId = 100,
                    channelId = 200,
                    responseMode = "deep",
                    preferredModel = "qwen-coder",
                    allowedModels = listOf("qwen-coder"),
                    minQualityTier = "specialized",
                    maxCandidates = 3,
                    providerTagFilter = listOf("coding"),
                    costGuard = "provider_safe",
                )
            }
            val effective = disabled.effective(100, 200, guildDefaultModel = "llama3.1:8b")
            assertEquals("balanced", effective.responseMode)
            assertEquals("llama3.1:8b", effective.preferredModel)
            assertEquals(emptyList<String>(), effective.allowedModels)
            assertEquals(1, effective.maxCandidates)
            assertThrows(IllegalStateException::class.java) { disabled.list(100) }
            assertThrows(IllegalStateException::class.java) { disabled.modelCandidates(100, 200, guildDefaultModel = null) }
        }

        @Test
        fun `channel routing policy stores response mode and model choice`() {
            val channelAi = channelAis.save(ChannelAiEntity(guildId = 100, channelId = 200, displayName = "코드냥"))

            val saved =
                service.save(
                    guildId = 100,
                    channelId = 200,
                    responseMode = "deep",
                    preferredModel = "qwen-coder",
                    allowedModels = listOf("qwen-coder", "llama3.1:8b", "qwen-coder"),
                    minQualityTier = "specialized",
                    maxCandidates = 10,
                    providerTagFilter = listOf("coding"),
                    costGuard = "provider_safe",
                )
            val effective = service.effective(100, 200, guildDefaultModel = "llama3.1:8b")

            assertEquals(channelAi.id, saved.channelAiId)
            assertEquals("deep", effective.responseMode)
            assertEquals("qwen-coder", effective.preferredModel)
            assertEquals(listOf("qwen-coder", "llama3.1:8b"), effective.allowedModels)
            assertEquals(3, effective.maxCandidates)
        }

        @Test
        fun `model choice explains fallback when requested model is unavailable`() {
            service.save(
                guildId = 100,
                channelId = 200,
                responseMode = "balanced",
                preferredModel = "llama3.1:8b",
                allowedModels = listOf("llama3.1:8b", "qwen-coder"),
                minQualityTier = "standard",
                maxCandidates = 1,
                providerTagFilter = emptyList(),
                costGuard = "provider_safe",
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 1,
                    providerState = "ONLINE",
                    modelNames = "llama3.1:8b",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 2,
                    providerState = "ONLINE",
                    modelNames = "qwen-coder",
                    qualityTier = "specialized",
                    overloadRisk = "critical",
                ),
            )

            val decision = service.resolveModelChoice(100, 200, requestedModel = "qwen-coder", guildDefaultModel = null)

            assertEquals("llama3.1:8b", decision.selectedModel)
            assertEquals("requested_model_unavailable", decision.fallbackReason)
            assertEquals(listOf("llama3.1:8b"), decision.availableModels)
        }

        @Test
        fun `model choice marks constrained policy when allowlist has no safe provider`() {
            service.save(
                guildId = 100,
                channelId = 203,
                responseMode = "balanced",
                preferredModel = null,
                allowedModels = listOf("qwen-coder"),
                minQualityTier = "standard",
                maxCandidates = 1,
                providerTagFilter = emptyList(),
                costGuard = "provider_safe",
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 4,
                    providerState = "ONLINE",
                    modelNames = "qwen-coder",
                    qualityTier = "specialized",
                    overloadRisk = "critical",
                ),
            )

            val decision = service.resolveModelChoice(100, 203, requestedModel = "qwen-coder", guildDefaultModel = null)

            val catalog = service.modelCandidates(100, 203, guildDefaultModel = null)

            assertEquals(null, decision.selectedModel)
            assertEquals("no_available_model", decision.fallbackReason)
            assertEquals("retry_later_or_adjust_channel_model_policy", decision.nextAction)
            assertEquals(true, decision.userMessage?.contains("요청을 보내지 않았습니다"))
            assertEquals(true, decision.requiresAvailableModel)
            assertEquals(emptyList<String>(), catalog.availableModels)
            assertEquals(listOf("qwen-coder"), catalog.unavailableAllowedModels)
            assertEquals("provider_protection_blocks_all_allowed_models", catalog.safetySummary)
            assertEquals(null, catalog.recommendedModel)
            assertEquals(false, catalog.modelSummaries.single().available)
            assertEquals(listOf("provider_critical_overload"), catalog.modelSummaries.single().blockingReasons)
        }

        @Test
        fun `model choice blocks model outside channel allowlist`() {
            service.save(
                guildId = 100,
                channelId = 201,
                responseMode = "fast",
                preferredModel = null,
                allowedModels = listOf("llama3.1:8b"),
                minQualityTier = "standard",
                maxCandidates = 1,
                providerTagFilter = emptyList(),
                costGuard = "provider_safe",
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 3,
                    providerState = "ONLINE",
                    modelNames = "llama3.1:8b,qwen-coder",
                    qualityTier = "specialized",
                    overloadRisk = "normal",
                ),
            )

            val decision = service.resolveModelChoice(100, 201, requestedModel = "qwen-coder", guildDefaultModel = null)

            assertEquals("llama3.1:8b", decision.selectedModel)
            assertEquals("requested_model_not_allowed", decision.fallbackReason)
        }

        @Test
        fun `model candidates explain provider tag quality overload and allowlist eligibility`() {
            service.save(
                guildId = 100,
                channelId = 202,
                responseMode = "deep",
                preferredModel = "qwen-coder",
                allowedModels = listOf("qwen-coder", "llama3.1:8b"),
                minQualityTier = "high",
                maxCandidates = 2,
                providerTagFilter = listOf("coding"),
                costGuard = "provider_safe",
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 10,
                    providerState = "ONLINE",
                    modelNames = "qwen-coder,llama3.1:8b",
                    capabilityTags = "coding,night",
                    qualityTier = "specialized",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 11,
                    providerState = "ONLINE",
                    modelNames = "llama3.1:8b",
                    capabilityTags = "summary",
                    qualityTier = "high",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 12,
                    providerState = "ONLINE",
                    modelNames = "tiny",
                    capabilityTags = "coding",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 13,
                    providerState = "ONLINE",
                    modelNames = "qwen-coder",
                    capabilityTags = "coding",
                    qualityTier = "specialized",
                    overloadRisk = "critical",
                ),
            )

            val catalog = service.modelCandidates(100, 202, guildDefaultModel = null)
            val decision = service.resolveModelChoice(100, 202, requestedModel = "qwen-coder", guildDefaultModel = null)

            assertEquals(listOf("llama3.1:8b", "qwen-coder"), catalog.availableModels)
            assertEquals(emptyList<String>(), catalog.unavailableAllowedModels)
            assertEquals("available", catalog.safetySummary)
            assertEquals("qwen-coder", catalog.recommendedModel)
            val qwenSummary = catalog.modelSummaries.first { it.modelName == "qwen-coder" }
            val llamaSummary = catalog.modelSummaries.first { it.modelName == "llama3.1:8b" }
            val tinySummary = catalog.modelSummaries.first { it.modelName == "tiny" }
            assertEquals(true, qwenSummary.recommended)
            assertEquals(true, qwenSummary.preferred)
            assertEquals(1, qwenSummary.eligibleProviderCount)
            assertEquals(2, qwenSummary.totalProviderCount)
            assertEquals(1, qwenSummary.protectedProviderCount)
            assertEquals("specialized", qwenSummary.bestQualityTier)
            assertEquals(true, llamaSummary.available)
            assertEquals(listOf("model_not_allowed", "quality_below_minimum"), tinySummary.blockingReasons)
            assertEquals("qwen-coder", decision.selectedModel)
            assertEquals(null, decision.fallbackReason)
            assertEquals(2, catalog.candidates.count { it.eligible })
            assertEquals(
                listOf("provider_tag_mismatch"),
                catalog.candidates.single { it.providerUserId == 11L }.ineligibleReasons,
            )
            assertEquals(
                listOf("quality_below_minimum", "model_not_allowed"),
                catalog.candidates.single { it.providerUserId == 12L }.ineligibleReasons,
            )
            assertEquals(
                listOf("provider_critical_overload"),
                catalog.candidates.single { it.providerUserId == 13L }.ineligibleReasons,
            )
        }

        @Test
        fun `model candidates expose feedback based shadow quality without changing live model choice`() {
            service.save(
                guildId = 101,
                channelId = 202,
                responseMode = "balanced",
                preferredModel = null,
                allowedModels = emptyList(),
                minQualityTier = "standard",
                maxCandidates = 1,
                providerTagFilter = emptyList(),
                costGuard = "provider_safe",
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 101,
                    providerUserId = 21,
                    providerState = "ONLINE",
                    modelNames = "z-good",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 101,
                    providerUserId = 22,
                    providerState = "ONLINE",
                    modelNames = "a-bad",
                    qualityTier = "standard",
                    overloadRisk = "normal",
                ),
            )
            requests.save(
                AiRequestEntity(requestId = "good-1", guildId = 101, channelId = 202, userId = 1, providerId = 21),
            )
            requests.save(
                AiRequestEntity(requestId = "bad-1", guildId = 101, channelId = 202, userId = 2, providerId = 22),
            )
            requests.save(
                AiRequestEntity(requestId = "bad-2", guildId = 101, channelId = 202, userId = 3, providerId = 22),
            )
            feedbacks.save(
                AiFeedbackEntity(
                    guildId = 101,
                    channelId = 202,
                    requestId = "good-1",
                    userId = 1,
                    rating = 1,
                    feedbackType = "positive",
                ),
            )
            feedbacks.save(
                AiFeedbackEntity(
                    guildId = 101,
                    channelId = 202,
                    requestId = "bad-1",
                    userId = 2,
                    rating = -1,
                    feedbackType = "negative",
                ),
            )
            feedbacks.save(
                AiFeedbackEntity(
                    guildId = 101,
                    channelId = 202,
                    requestId = "bad-2",
                    userId = 3,
                    rating = -1,
                    feedbackType = "report",
                ),
            )

            val catalog = service.modelCandidates(101, 202, guildDefaultModel = null)
            val decision = service.resolveModelChoice(101, 202, requestedModel = null, guildDefaultModel = null)

            val good = catalog.modelSummaries.single { it.modelName == "z-good" }
            val bad = catalog.modelSummaries.single { it.modelName == "a-bad" }
            assertEquals("z-good", catalog.recommendedModel)
            assertEquals(10, good.shadowQualityScore)
            assertEquals(1, good.feedbackPositive)
            assertEquals(-37, bad.shadowQualityScore)
            assertEquals(1, bad.feedbackReports)
            assertEquals("a-bad", decision.selectedModel)
            assertEquals(null, decision.fallbackReason)
        }

        @Test
        fun `channel routing policy falls back to guild default model`() {
            val effective = service.effective(100, 200, guildDefaultModel = "llama3.1:8b")

            assertEquals("balanced", effective.responseMode)
            assertEquals("llama3.1:8b", effective.preferredModel)
            assertNull(policies.findByGuildIdAndChannelId(100, 200))
        }
    }
