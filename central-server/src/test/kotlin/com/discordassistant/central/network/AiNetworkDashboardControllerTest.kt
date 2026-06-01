package com.discordassistant.central.network

import com.discordassistant.central.dashboard.AiNetworkDashboardController
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.AiNetworkProfileRepository
import com.discordassistant.central.persistence.AiPresetEntity
import com.discordassistant.central.persistence.AiPresetRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionRepository
import com.discordassistant.central.persistence.PresetImportEntity
import com.discordassistant.central.persistence.PresetImportRepository
import com.discordassistant.central.persistence.PresetRevisionEntity
import com.discordassistant.central.persistence.PresetRevisionRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.persistence.PublishedPresetEntity
import com.discordassistant.central.persistence.PublishedPresetRepository
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
class AiNetworkDashboardControllerTest
    @Autowired
    constructor(
        private val networkProfiles: AiNetworkProfileRepository,
        private val providerCapabilities: ProviderCapabilityProfileRepository,
        private val knowledgeSpaces: KnowledgeSpaceRepository,
        private val overviewProjections: NetworkOverviewProjectionRepository,
        private val channelAis: ChannelAiRepository,
        private val feedbacks: AiFeedbackRepository,
        private val presets: AiPresetRepository,
        private val presetRevisions: PresetRevisionRepository,
        private val publishedPresets: PublishedPresetRepository,
        private val presetImports: PresetImportRepository,
    ) {
        private val fixedClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

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

        private val controller =
            AiNetworkDashboardController(
                foundation = foundation,
                channelAis = channelAis,
                providerCapabilities = providerCapabilities,
                knowledgeSpaces = knowledgeSpaces,
                presets = presets,
                publishedPresets = publishedPresets,
                presetImports = presetImports,
            )

        @Test
        fun `overview returns refreshed AI network snapshot`() {
            channelAis.save(ChannelAiEntity(guildId = 100, channelId = 200, displayName = "코드냥"))
            foundation.upsertProviderCapability(
                guildId = 100,
                providerUserId = 300,
                providerState = "ONLINE",
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = listOf("coding"),
                maxBurden = "STANDARD",
                maxConcurrency = 2,
                dailyLimit = 20,
                overloadRisk = "normal",
            )

            val response = controller.overview(100)

            assertEquals("함께 만드는 AI 네트워크", response.tagline)
            assertEquals(1, response.onlineProviderCount)
            assertEquals(1, response.channelAiCount)
            assertEquals("ready", response.healthStatus)
        }

        @Test
        fun `dashboard lists channels providers knowledge and presets without prompt bodies`() {
            val channelAi = channelAis.save(ChannelAiEntity(guildId = 100, channelId = 200, displayName = "코드냥"))
            foundation.upsertProviderCapability(
                guildId = 100,
                providerUserId = 300,
                providerState = "ONLINE",
                modelNames = listOf("llama3.1:8b", "qwen-coder"),
                capabilityTags = listOf("coding", "night"),
                maxBurden = "STANDARD",
                maxConcurrency = 2,
                dailyLimit = 20,
                overloadRisk = "normal",
            )
            foundation.createKnowledgeSpace(100, 200, channelAi.id, "코드 지식", 77)
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
                        title = "코딩 튜터",
                    ),
                )
            presetImports.save(PresetImportEntity(publishedPresetId = published.id, targetGuildId = 100, targetChannelId = 200))

            val channels = controller.channels(100)
            val providers = controller.providers(100)
            val knowledge = controller.knowledgeSpaces(100)
            val guildPresets = controller.guildPresets(100)

            assertEquals("코드냥", channels.single().name)
            assertEquals(listOf("llama3.1:8b", "qwen-coder"), providers.single().models)
            assertEquals(listOf("coding", "night"), providers.single().tags)
            assertEquals("코드 지식", knowledge.single().name)
            val localPresets = guildPresets["local"] as List<*>
            assertTrue(localPresets.single().toString().contains(preset.name))
            assertTrue(guildPresets.toString().contains("publishedPresetId"))
        }
    }
