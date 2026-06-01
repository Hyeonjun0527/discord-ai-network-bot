package com.discordassistant.central.network

import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
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
    ) {
        private val service =
            ChannelAiRoutingPolicyService(
                policies = policies,
                channelAis = channelAis,
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
        fun `channel routing policy falls back to guild default model`() {
            val effective = service.effective(100, 200, guildDefaultModel = "llama3.1:8b")

            assertEquals("balanced", effective.responseMode)
            assertEquals("llama3.1:8b", effective.preferredModel)
            assertNull(policies.findByGuildIdAndChannelId(100, 200))
        }
    }
