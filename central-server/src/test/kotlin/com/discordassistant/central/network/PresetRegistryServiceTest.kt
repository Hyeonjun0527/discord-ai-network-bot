package com.discordassistant.central.network

import com.discordassistant.central.dashboard.CreatePresetRequest
import com.discordassistant.central.dashboard.ImportPresetRequest
import com.discordassistant.central.dashboard.LikePresetRequest
import com.discordassistant.central.dashboard.PresetRegistryController
import com.discordassistant.central.dashboard.PublishPresetRequest
import com.discordassistant.central.dashboard.ReportPresetRequest
import com.discordassistant.central.dashboard.ReviewPresetReportRequest
import com.discordassistant.central.dashboard.UpdatePresetRequest
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.AiPresetRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.persistence.PresetImportRepository
import com.discordassistant.central.persistence.PresetReactionRepository
import com.discordassistant.central.persistence.PresetReportRepository
import com.discordassistant.central.persistence.PresetRevisionRepository
import com.discordassistant.central.persistence.PublishedPresetRepository
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
class PresetRegistryServiceTest
    @Autowired
    constructor(
        private val presets: AiPresetRepository,
        private val revisions: PresetRevisionRepository,
        private val publishedPresets: PublishedPresetRepository,
        private val imports: PresetImportRepository,
        private val reactions: PresetReactionRepository,
        private val reports: PresetReportRepository,
        private val channelAis: ChannelAiRepository,
        private val routingPolicies: ChannelAiRoutingPolicyRepository,
        private val behaviorVersions: AiBehaviorVersionRepository,
        private val proposals: AiChangeProposalRepository,
        private val audits: CustomizationAuditLogRepository,
    ) {
        private val service =
            PresetRegistryService(
                presets = presets,
                revisions = revisions,
                publishedPresets = publishedPresets,
                imports = imports,
                reactions = reactions,
                reports = reports,
                channelAis = channelAis,
                routingPolicies = routingPolicies,
                behaviorVersions = behaviorVersions,
                proposals = proposals,
                audits = audits,
                clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
            )

        private val controller = PresetRegistryController(service)

        @Test
        fun `preset lifecycle create update publish import like report delete`() {
            val created =
                controller.create(
                    100,
                    CreatePresetRequest(
                        actorUserId = 77,
                        name = "코딩 튜터",
                        summary = "개발 질문용",
                        behavior =
                            PresetBehaviorInput(
                                purpose = "개발 질문",
                                tone = "practical",
                                answerLength = "balanced",
                                constitution = "모르면 모른다고 말하기",
                                responseMode = "deep",
                                preferredModel = "qwen-coder",
                                minQualityTier = "high",
                                maxCandidates = 2,
                                providerTagFilter = listOf("coding", "night"),
                                costGuard = "provider_safe",
                            ),
                    ),
                )
            val presetId = created["id"] as Long
            assertNotNull(created["currentRevisionId"])

            val updated =
                controller.update(
                    presetId,
                    UpdatePresetRequest(
                        actorUserId = 77,
                        name = "코딩 튜터 v2",
                        behavior =
                            PresetBehaviorInput(
                                purpose = "코드 리뷰",
                                tone = "concise",
                                responseMode = "deep",
                                preferredModel = "qwen-coder",
                                minQualityTier = "high",
                                maxCandidates = 2,
                                providerTagFilter = listOf("coding", "night"),
                                changeSummary = "리뷰 특화",
                            ),
                    ),
                )
            assertEquals("draft", updated["status"])
            assertEquals(2, revisions.findByPresetIdOrderByRevisionDesc(presetId).first().revision)
            val guildList = controller.listGuildPresets(100)["presets"] as List<*>
            assertEquals(1, guildList.size)
            val localDetail = controller.presetDetail(presetId)["preset"] as PresetDetail
            assertEquals(2, localDetail.revisions.size)
            assertEquals("코드 리뷰", localDetail.revisions.first().purpose)

            val published = controller.publish(presetId, PublishPresetRequest(actorUserId = 77))
            val publishedId = published["id"] as Long
            assertEquals("published", published["status"])
            val publishedList = controller.publishedPresets()["presets"] as List<*>
            assertEquals(1, publishedList.size)
            val publishedSummary = publishedList.first() as PublishedPresetSummary
            assertEquals("코드 리뷰", publishedSummary.purpose)
            assertEquals("standard", publishedSummary.safetyLevel)
            assertEquals("deep", publishedSummary.responseMode)
            assertEquals("qwen-coder", publishedSummary.preferredModel)
            val publishedDetail = controller.publishedPresetDetail(publishedId)["preset"] as PublishedPresetDetail
            assertEquals("concise", publishedDetail.behavior.tone)
            assertEquals(listOf("coding", "night"), publishedDetail.behavior.providerTagFilter)

            controller.like(publishedId, LikePresetRequest(userId = 88))
            controller.like(publishedId, LikePresetRequest(userId = 88))
            assertEquals(1, publishedPresets.findById(publishedId).get().likeCount)
            controller.unlike(publishedId, LikePresetRequest(userId = 88))
            controller.unlike(publishedId, LikePresetRequest(userId = 88))
            assertEquals(0, publishedPresets.findById(publishedId).get().likeCount)
            controller.like(publishedId, LikePresetRequest(userId = 88))
            assertEquals(1, publishedPresets.findById(publishedId).get().likeCount)

            val imported =
                controller.importPreset(
                    publishedId,
                    ImportPresetRequest(targetGuildId = 101, targetChannelId = 202, actorUserId = 89),
                )
            assertNotNull(imported["importedPresetId"])
            assertEquals("applied", imported["status"])
            assertNotNull(imported["createdChannelAiId"])
            assertNotNull(imported["createdBehaviorVersionId"])
            assertEquals(1, imports.findByTargetGuildId(101).size)
            val channelAi = channelAis.findByGuildIdAndChannelId(101, 202)
            assertNotNull(channelAi)
            assertEquals(imported["createdChannelAiId"], channelAi?.id)
            assertEquals(imported["createdBehaviorVersionId"], channelAi?.activeBehaviorVersionId)
            val importedBehavior = behaviorVersions.findByChannelAiIdAndId(channelAi!!.id, channelAi.activeBehaviorVersionId!!)
            assertEquals("코드 리뷰", importedBehavior?.purpose)
            assertEquals("concise", importedBehavior?.tone)
            val importedRouting = routingPolicies.findByGuildIdAndChannelId(101, 202)
            assertNotNull(importedRouting)
            assertEquals("deep", importedRouting?.responseMode)
            assertEquals("qwen-coder", importedRouting?.preferredModel)
            assertEquals("high", importedRouting?.minQualityTier)
            assertEquals(2, importedRouting?.maxCandidates)
            assertEquals("coding,night", importedRouting?.providerTagFilter)
            assertEquals(1, publishedPresets.findById(publishedId).get().importCount)

            val report =
                controller.report(
                    publishedId,
                    ReportPresetRequest(reporterUserId = 90, reason = "검토 필요 token=super-secret"),
                )
            val reportId = report["id"] as Long
            assertEquals("open", report["status"])
            assertEquals(1, reports.findByStatus("open").size)
            val openReports = controller.reports()["reports"] as List<*>
            assertEquals(1, openReports.size)
            assertEquals("검토 필요 [redacted]", (openReports.single() as PresetReportSummary).reason)
            assertEquals(1, publishedPresets.findById(publishedId).get().reportCount)
            assertEquals("under_review", publishedPresets.findById(publishedId).get().status)
            assertEquals(0, (controller.publishedPresets()["presets"] as List<*>).size)
            assertThrows(IllegalArgumentException::class.java) {
                controller.importPreset(
                    publishedId,
                    ImportPresetRequest(targetGuildId = 102, targetChannelId = 203, actorUserId = 89),
                )
            }

            val reviewed = controller.reviewReport(reportId, ReviewPresetReportRequest(decision = "dismiss"))
            assertEquals("dismiss", reviewed["status"])
            assertEquals("published", publishedPresets.findById(publishedId).get().status)
            assertEquals(1, (controller.publishedPresets()["presets"] as List<*>).size)

            val removed = controller.deletePublished(publishedId)
            assertEquals("removed", removed["status"])
            assertEquals("removed", publishedPresets.findById(publishedId).get().status)
        }

        @Test
        fun `delete local preset soft removes and preserves revisions`() {
            val preset =
                service.createPreset(
                    guildId = 100,
                    ownerUserId = 77,
                    name = "요약냥",
                    summary = null,
                    category = "summary",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "요약", tone = "short"),
                )

            val removed = controller.delete(preset.id)

            assertEquals("removed", removed["status"])
            assertEquals("removed", presets.findById(preset.id).get().status)
            assertEquals(1, revisions.findByPresetIdOrderByRevisionDesc(preset.id).size)
            assertThrows(IllegalArgumentException::class.java) {
                service.updatePreset(
                    presetId = preset.id,
                    actorUserId = 77,
                    name = "삭제된 프리셋 수정",
                    summary = null,
                    category = null,
                    visibility = null,
                    behavior = null,
                )
            }
        }

        @Test
        fun `high risk preset import creates pending channel proposal instead of publishing immediately`() {
            val preset =
                service.createPreset(
                    guildId = 200,
                    ownerUserId = 77,
                    name = "위험 프리셋",
                    summary = null,
                    category = "ops",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "운영", tone = "direct", safetyLevel = "high"),
                )
            val published = service.publishPreset(preset.id, publisherUserId = 77, title = null, description = null)

            val imported = service.importPreset(published.id, targetGuildId = 201, targetChannelId = 301, importedBy = 88)

            assertEquals("needs_review", imported.status)
            assertNotNull(imported.createdChannelAiId)
            assertNotNull(imported.createdBehaviorVersionId)
            val channelAi = channelAis.findByGuildIdAndChannelId(201, 301)
            assertNotNull(channelAi)
            assertEquals(null, channelAi?.activeBehaviorVersionId)
            assertEquals(1, proposals.findByGuildIdAndStatus(201, "pending").size)
            assertEquals(1, audits.findTop10ByGuildIdAndChannelIdOrderByCreatedAtDesc(201, 301).size)
        }
    }
