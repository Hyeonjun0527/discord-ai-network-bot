package com.discordassistant.central.network

import com.discordassistant.central.dashboard.CreatePresetRequest
import com.discordassistant.central.dashboard.ImportPresetRequest
import com.discordassistant.central.dashboard.LikePresetRequest
import com.discordassistant.central.dashboard.PresetRegistryController
import com.discordassistant.central.dashboard.PublishPresetRequest
import com.discordassistant.central.dashboard.ReportPresetRequest
import com.discordassistant.central.dashboard.UpdatePresetRequest
import com.discordassistant.central.persistence.AiPresetRepository
import com.discordassistant.central.persistence.PresetImportRepository
import com.discordassistant.central.persistence.PresetReactionRepository
import com.discordassistant.central.persistence.PresetReportRepository
import com.discordassistant.central.persistence.PresetRevisionRepository
import com.discordassistant.central.persistence.PublishedPresetRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
class PresetRegistryServiceTest
    @Autowired
    constructor(
        private val presets: AiPresetRepository,
        private val revisions: PresetRevisionRepository,
        private val publishedPresets: PublishedPresetRepository,
        private val imports: PresetImportRepository,
        private val reactions: PresetReactionRepository,
        private val reports: PresetReportRepository,
    ) {
        private val service =
            PresetRegistryService(
                presets = presets,
                revisions = revisions,
                publishedPresets = publishedPresets,
                imports = imports,
                reactions = reactions,
                reports = reports,
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
                        behavior = PresetBehaviorInput(purpose = "코드 리뷰", tone = "concise", changeSummary = "리뷰 특화"),
                    ),
                )
            assertEquals("draft", updated["status"])
            assertEquals(2, revisions.findByPresetIdOrderByRevisionDesc(presetId).first().revision)

            val published = controller.publish(presetId, PublishPresetRequest(actorUserId = 77))
            val publishedId = published["id"] as Long
            assertEquals("published", published["status"])

            controller.like(publishedId, LikePresetRequest(userId = 88))
            controller.like(publishedId, LikePresetRequest(userId = 88))
            assertEquals(1, publishedPresets.findById(publishedId).get().likeCount)

            val imported =
                controller.importPreset(
                    publishedId,
                    ImportPresetRequest(targetGuildId = 101, targetChannelId = 202, actorUserId = 89),
                )
            assertNotNull(imported["importedPresetId"])
            assertEquals(1, imports.findByTargetGuildId(101).size)
            assertEquals(1, publishedPresets.findById(publishedId).get().importCount)

            val report = controller.report(publishedId, ReportPresetRequest(reporterUserId = 90, reason = "검토 필요"))
            assertEquals("open", report["status"])
            assertEquals(1, reports.findByStatus("open").size)
            assertEquals(1, publishedPresets.findById(publishedId).get().reportCount)

            controller.deletePublished(publishedId)
            assertFalse(publishedPresets.findById(publishedId).isPresent)
        }

        @Test
        fun `delete local preset removes revisions`() {
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

            controller.delete(preset.id)

            assertFalse(presets.findById(preset.id).isPresent)
            assertEquals(emptyList<Any>(), revisions.findByPresetIdOrderByRevisionDesc(preset.id))
        }
    }
