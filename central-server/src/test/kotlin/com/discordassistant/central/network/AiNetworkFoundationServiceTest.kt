package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiFeedbackEntity
import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.AiNetworkProfileRepository
import com.discordassistant.central.persistence.AiPresetEntity
import com.discordassistant.central.persistence.AiPresetRepository
import com.discordassistant.central.persistence.CandidateAnswerEntity
import com.discordassistant.central.persistence.CandidateAnswerRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.KnowledgeSourceEntity
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.MultiResponsePolicyEntity
import com.discordassistant.central.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.persistence.MultiResponseRunEntity
import com.discordassistant.central.persistence.MultiResponseRunRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionRepository
import com.discordassistant.central.persistence.PresetImportEntity
import com.discordassistant.central.persistence.PresetImportRepository
import com.discordassistant.central.persistence.PresetReactionEntity
import com.discordassistant.central.persistence.PresetReactionRepository
import com.discordassistant.central.persistence.PresetReportEntity
import com.discordassistant.central.persistence.PresetReportRepository
import com.discordassistant.central.persistence.PresetRevisionEntity
import com.discordassistant.central.persistence.PresetRevisionRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.persistence.PublishedPresetEntity
import com.discordassistant.central.persistence.PublishedPresetRepository
import com.discordassistant.central.persistence.SynthesisResultEntity
import com.discordassistant.central.persistence.SynthesisResultRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AiNetworkFoundationServiceTest
    @Autowired
    constructor(
        private val networkProfiles: AiNetworkProfileRepository,
        private val providerCapabilities: ProviderCapabilityProfileRepository,
        private val knowledgeSpaces: KnowledgeSpaceRepository,
        private val knowledgeSources: KnowledgeSourceRepository,
        private val overviewProjections: NetworkOverviewProjectionRepository,
        private val channelAis: ChannelAiRepository,
        private val feedbacks: AiFeedbackRepository,
        private val multiResponsePolicies: MultiResponsePolicyRepository,
        private val multiResponseRuns: MultiResponseRunRepository,
        private val candidateAnswers: CandidateAnswerRepository,
        private val synthesisResults: SynthesisResultRepository,
        private val presets: AiPresetRepository,
        private val presetRevisions: PresetRevisionRepository,
        private val publishedPresets: PublishedPresetRepository,
        private val presetImports: PresetImportRepository,
        private val presetReactions: PresetReactionRepository,
        private val presetReports: PresetReportRepository,
    ) {
        private val fixedClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

        private val service =
            AiNetworkFoundationService(
                networkProfiles = networkProfiles,
                providerCapabilities = providerCapabilities,
                knowledgeSpaces = knowledgeSpaces,
                overviewProjections = overviewProjections,
                channelAis = channelAis,
                feedbacks = feedbacks,
                clock = fixedClock,
            )

        @Test
        fun `foundation profile and overview are guild scoped`() {
            val profile = service.ensureNetworkProfile(guildId = 100)
            val same = service.ensureNetworkProfile(guildId = 100)
            assertEquals(profile.id, same.id)
            assertEquals("함께 만드는 AI 네트워크", same.tagline)

            channelAis.save(
                ChannelAiEntity(
                    guildId = 100,
                    channelId = 200,
                    displayName = "코드냥",
                    createdAt = Instant.now(fixedClock),
                    updatedAt = Instant.now(fixedClock),
                ),
            )
            service.upsertProviderCapability(
                guildId = 100,
                providerUserId = 300,
                providerState = "ONLINE",
                modelNames = listOf("llama3.1:8b", "qwen-coder"),
                capabilityTags = listOf("coding"),
                maxBurden = "STANDARD",
                maxConcurrency = 2,
                dailyLimit = 50,
                overloadRisk = "normal",
            )
            feedbacks.save(AiFeedbackEntity(guildId = 100, channelId = 200, rating = 1))

            val overview = service.refreshOverview(guildId = 100)

            assertEquals(1, overview.onlineProviderCount)
            assertEquals(2, overview.modelCount)
            assertEquals(1, overview.channelAiCount)
            assertEquals(1, overview.feedbackCount)
            assertEquals("ready", overview.healthStatus)
            assertNull(overviewProjections.findByGuildId(999))
        }

        @Test
        fun `knowledge foundation stores metadata without document body`() {
            val channelAi =
                channelAis.save(
                    ChannelAiEntity(
                        guildId = 100,
                        channelId = 201,
                        displayName = "지식냥",
                        createdAt = Instant.now(fixedClock),
                        updatedAt = Instant.now(fixedClock),
                    ),
                )
            val space =
                service.createKnowledgeSpace(
                    guildId = 100,
                    channelId = 201,
                    channelAiId = channelAi.id,
                    displayName = "운영규칙",
                    createdBy = 777,
                )
            val source =
                knowledgeSources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 100,
                        sourceType = "link",
                        sourceUri = "https://example.com/rules.md",
                        title = "운영규칙.md",
                        contentHash = "sha256:abc",
                    ),
                )

            assertEquals(1, knowledgeSpaces.findByGuildIdAndChannelId(100, 201).size)
            assertEquals(source.id, knowledgeSources.findByKnowledgeSpaceId(space.id).single().id)
            assertEquals(0, space.chunkCount)
        }

        @Test
        fun `multi response foundation tracks candidates by reference only`() {
            val policy =
                multiResponsePolicies.save(
                    MultiResponsePolicyEntity(
                        guildId = 100,
                        channelId = 202,
                        mode = "compare",
                        maxCandidates = 3,
                        requireDistinctModels = true,
                        synthesisEnabled = true,
                    ),
                )
            val run =
                multiResponseRuns.save(
                    MultiResponseRunEntity(
                        guildId = 100,
                        channelId = 202,
                        requestId = "req-1",
                        policyId = policy.id,
                        status = "running",
                    ),
                )
            candidateAnswers.save(
                CandidateAnswerEntity(
                    runId = run.id,
                    providerUserId = 300,
                    modelName = "llama3.1:8b",
                    answerRef = "answer:req-1:a",
                    status = "completed",
                ),
            )
            synthesisResults.save(
                SynthesisResultEntity(
                    runId = run.id,
                    answerRef = "answer:req-1:final",
                    status = "completed",
                    selectedCandidateIds = "1",
                ),
            )

            assertEquals(policy.id, multiResponsePolicies.findByGuildIdAndChannelId(100, 202)?.id)
            assertEquals(run.id, multiResponseRuns.findByRequestId("req-1")?.id)
            assertEquals("answer:req-1:a", candidateAnswers.findByRunId(run.id).single().answerRef)
            assertEquals("answer:req-1:final", synthesisResults.findByRunId(run.id)?.answerRef)
        }

        @Test
        fun `preset registry supports publish import reactions and reports`() {
            val preset =
                presets.save(
                    AiPresetEntity(
                        guildId = 100,
                        ownerUserId = 77,
                        name = "코딩 튜터",
                        summary = "개발 질문용",
                        visibility = "guild_shared",
                    ),
                )
            val revision =
                presetRevisions.save(
                    PresetRevisionEntity(
                        presetId = preset.id,
                        revision = 1,
                        name = "코딩 튜터",
                        purpose = "개발 질문에 답변",
                        tone = "practical",
                        answerLength = "balanced",
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
            val imported =
                presetImports.save(
                    PresetImportEntity(
                        publishedPresetId = published.id,
                        targetGuildId = 101,
                        targetChannelId = 202,
                        importedBy = 88,
                    ),
                )
            presetReactions.save(PresetReactionEntity(publishedPresetId = published.id, userId = 90, reaction = "like"))
            presetReports.save(PresetReportEntity(publishedPresetId = published.id, reporterUserId = 91, reason = "검토 필요"))

            assertEquals(preset.id, presets.findByGuildId(100).single().id)
            assertEquals(revision.id, presetRevisions.findByPresetIdOrderByRevisionDesc(preset.id).single().id)
            assertEquals(published.id, publishedPresets.findByStatusOrderByLikeCountDescPublishedAtDesc("published").single().id)
            assertEquals(imported.id, presetImports.findByTargetGuildId(101).single().id)
            assertNotNull(presetReactions.findByPublishedPresetIdAndUserIdAndReaction(published.id, 90, "like"))
            assertEquals(1, presetReports.findByStatus("open").size)
        }

        @Test
        fun `preset reaction is unique per user and reaction`() {
            val preset = presets.save(AiPresetEntity(guildId = 100, name = "요약냥"))
            val revision =
                presetRevisions.save(
                    PresetRevisionEntity(
                        presetId = preset.id,
                        revision = 1,
                        name = "요약냥",
                        purpose = "요약",
                        tone = "short",
                    ),
                )
            val published =
                publishedPresets.save(
                    PublishedPresetEntity(
                        presetId = preset.id,
                        revisionId = revision.id,
                        publisherGuildId = 100,
                        title = "요약냥",
                    ),
                )
            presetReactions.saveAndFlush(PresetReactionEntity(publishedPresetId = published.id, userId = 90, reaction = "like"))

            assertThrows(DataIntegrityViolationException::class.java) {
                presetReactions.saveAndFlush(PresetReactionEntity(publishedPresetId = published.id, userId = 90, reaction = "like"))
            }
        }

        @Test
        fun `provider overload risk is surfaced in overview`() {
            service.upsertProviderCapability(
                guildId = 100,
                providerUserId = 301,
                providerState = "ONLINE",
                modelNames = listOf("llama3.1:8b"),
                capabilityTags = emptyList(),
                maxBurden = "LIGHT",
                maxConcurrency = 1,
                dailyLimit = 10,
                overloadRisk = "high",
            )

            val overview = service.refreshOverview(100)

            assertEquals(1, overview.overloadAlertCount)
            assertEquals("warning", overview.healthStatus)
            assertTrue(overview.staleAfter!!.isAfter(overview.refreshedAt))
        }
    }
