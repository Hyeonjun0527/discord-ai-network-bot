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
            assertEquals(5, effective.maxCandidates)
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
        fun `channel routing policy falls back to guild default model`() {
            val effective = service.effective(100, 200, guildDefaultModel = "llama3.1:8b")

            assertEquals("balanced", effective.responseMode)
            assertEquals("llama3.1:8b", effective.preferredModel)
            assertNull(policies.findByGuildIdAndChannelId(100, 200))
        }
    }
