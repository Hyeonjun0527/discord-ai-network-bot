package com.discordassistant.central.network

import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    ) {
        private val service =
            ChannelAiRoutingPolicyService(
                policies = policies,
                channelAis = channelAis,
                providerCapabilities = providerCapabilities,
                clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
            )

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
        fun `channel routing policy falls back to guild default model`() {
            val effective = service.effective(100, 200, guildDefaultModel = "llama3.1:8b")

            assertEquals("balanced", effective.responseMode)
            assertEquals("llama3.1:8b", effective.preferredModel)
            assertNull(policies.findByGuildIdAndChannelId(100, 200))
        }
    }
