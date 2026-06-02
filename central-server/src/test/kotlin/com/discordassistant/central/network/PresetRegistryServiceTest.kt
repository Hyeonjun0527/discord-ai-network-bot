package com.discordassistant.central.network

import com.discordassistant.central.dashboard.CreatePresetRequest
import com.discordassistant.central.dashboard.ImportPresetRequest
import com.discordassistant.central.dashboard.LikePresetRequest
import com.discordassistant.central.dashboard.PresetRegistryController
import com.discordassistant.central.dashboard.PublishPresetRequest
import com.discordassistant.central.dashboard.ReportPresetRequest
import com.discordassistant.central.dashboard.ReviewPresetReportRequest
import com.discordassistant.central.dashboard.SaveChannelPresetRequest
import com.discordassistant.central.dashboard.UpdatePresetRequest
import com.discordassistant.central.dashboard.UpdatePublishedPresetRequest
import com.discordassistant.central.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.AiPresetRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.persistence.PresetImportRepository
import com.discordassistant.central.persistence.PresetReactionRepository
import com.discordassistant.central.persistence.PresetReportRepository
import com.discordassistant.central.persistence.PresetRevisionRepository
import com.discordassistant.central.persistence.PublishedPresetRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
        fun `active channel ai can be saved as reusable preset snapshot`() {
            val channelAi =
                channelAis.saveAndFlush(
                    ChannelAiEntity(
                        guildId = 360,
                        channelId = 460,
                        displayName = "코드냥",
                        source = "wizard",
                    ),
                )
            val behavior =
                behaviorVersions.saveAndFlush(
                    AiBehaviorVersionEntity(
                        channelAiId = channelAi.id,
                        version = 1,
                        purpose = "Kotlin Spring Boot 개발 질문",
                        tone = "짧고 실용적으로",
                        answerLength = "balanced",
                        constitution = "코드는 실행 가능한 예시와 테스트 방법을 함께 제안합니다.",
                        safetyLevel = "standard",
                    ),
                )
            channelAi.activeBehaviorVersionId = behavior.id
            channelAis.saveAndFlush(channelAi)
            routingPolicies.save(
                ChannelAiRoutingPolicyEntity(
                    guildId = 360,
                    channelId = 460,
                    channelAiId = channelAi.id,
                    responseMode = "deep",
                    preferredModel = "qwen-coder",
                    minQualityTier = "high",
                    maxCandidates = 2,
                    providerTagFilter = "coding,night",
                    costGuard = "provider_safe",
                ),
            )

            val saved =
                controller.saveFromChannel(
                    360,
                    460,
                    SaveChannelPresetRequest(actorUserId = 77, name = "코딩 튜터 프리셋", category = "dev"),
                )

            val presetId = saved["id"] as Long
            assertEquals("draft", saved["status"])
            val detail = controller.presetDetail(presetId)["preset"] as PresetDetail
            assertEquals("코딩 튜터 프리셋", detail.preset.name)
            assertEquals("dev", detail.preset.category)
            val revision = detail.revisions.single()
            assertEquals("Kotlin Spring Boot 개발 질문", revision.purpose)
            assertEquals("짧고 실용적으로", revision.tone)
            assertEquals("deep", revision.responseMode)
            assertEquals("qwen-coder", revision.preferredModel)
            assertEquals("high", revision.minQualityTier)
            assertEquals(2, revision.maxCandidates)
            assertEquals(listOf("coding", "night"), revision.providerTagFilter)
            assertTrue(revision.changeSummary!!.contains("saved from channel AI"))
        }

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
            assertEquals("코딩-튜터-v2-$presetId", published["slug"])
            val publishedList = controller.publishedPresets()["presets"] as List<*>
            assertEquals(1, publishedList.size)
            val publishedSummary = publishedList.first() as PublishedPresetSummary
            assertEquals("코드 리뷰", publishedSummary.purpose)
            assertEquals("standard", publishedSummary.safetyLevel)
            assertEquals("deep", publishedSummary.responseMode)
            assertEquals("qwen-coder", publishedSummary.preferredModel)
            assertNull(publishedSummary.publisherGuildId)
            assertNull(publishedSummary.publisherUserId)
            assertEquals("공개 프리셋 작성자", publishedSummary.publisherLabel)
            assertEquals("코딩-튜터-v2-$presetId", publishedSummary.slug)
            assertNotNull(publishedPresets.findBySlug(publishedSummary.slug))
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
                    ImportPresetRequest(targetGuildId = 101, targetChannelId = 202, actorUserId = 89, confirmConflicts = true),
                )
            assertNotNull(imported["importedPresetId"])
            assertEquals(publishedDetail.published.revisionId, imported["sourceRevisionId"])
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
            val importHistoryBeforeDelete = controller.importHistory(101, channelId = 202)["imports"] as List<*>
            val importedSummaryBeforeDelete = importHistoryBeforeDelete.single() as PresetImportSummary
            assertEquals(imported["id"], importedSummaryBeforeDelete.id)
            assertEquals(publishedId, importedSummaryBeforeDelete.publishedPresetId)
            assertEquals(imported["sourceRevisionId"], importedSummaryBeforeDelete.sourceRevisionId)
            assertEquals(imported["importedPresetId"], importedSummaryBeforeDelete.importedPresetId)
            assertEquals("applied", importedSummaryBeforeDelete.status)
            assertTrue(importedSummaryBeforeDelete.detachedCopy)

            val report =
                controller.report(
                    publishedId,
                    ReportPresetRequest(
                        reporterUserId = 90,
                        reasonCode = "sensitive_data",
                        details = "검토 필요 token=super-secret",
                    ),
                )
            val reportId = report["id"] as Long
            assertEquals("open", report["status"])
            assertEquals("sensitive_data", report["reasonCode"])
            val duplicateReport =
                controller.report(
                    publishedId,
                    ReportPresetRequest(reporterUserId = 90, reason = "중복 신고"),
                )
            assertEquals(reportId, duplicateReport["id"])
            assertEquals(1, reports.findByStatus("open").size)
            val openReports = controller.reports()["reports"] as List<*>
            assertEquals(1, openReports.size)
            val openReport = openReports.single() as PresetReportSummary
            assertEquals("검토 필요 [redacted]", openReport.reason)
            assertEquals("sensitive_data", openReport.reasonCode)
            assertEquals("검토 필요 [redacted]", openReport.details)
            assertEquals(1, publishedPresets.findById(publishedId).get().reportCount)
            assertEquals("under_review", publishedPresets.findById(publishedId).get().status)
            assertEquals(0, (controller.publishedPresets()["presets"] as List<*>).size)
            assertThrows(IllegalArgumentException::class.java) {
                controller.importPreset(
                    publishedId,
                    ImportPresetRequest(targetGuildId = 102, targetChannelId = 203, actorUserId = 89),
                )
            }

            val reviewed = controller.reviewReport(reportId, ReviewPresetReportRequest(decision = "dismiss", reviewerUserId = 91))
            assertEquals("dismiss", reviewed["status"])
            assertEquals(91L, reviewed["reviewedBy"])
            assertEquals(91L, reports.findById(reportId).orElseThrow().reviewedBy)
            assertEquals("published", publishedPresets.findById(publishedId).get().status)
            assertEquals(1, (controller.publishedPresets()["presets"] as List<*>).size)

            val removed = controller.deletePublished(publishedId)
            assertEquals("removed", removed["status"])
            assertEquals("removed", publishedPresets.findById(publishedId).get().status)
            assertThrows(IllegalArgumentException::class.java) {
                controller.importPreset(
                    publishedId,
                    ImportPresetRequest(targetGuildId = 103, targetChannelId = 204, actorUserId = 89, confirmConflicts = true),
                )
            }
            val importHistoryAfterDelete = controller.importHistory(101, channelId = 202)["imports"] as List<*>
            val preservedImport = importHistoryAfterDelete.single() as PresetImportSummary
            assertEquals(importedSummaryBeforeDelete.id, preservedImport.id)
            assertEquals(importedSummaryBeforeDelete.publishedPresetId, preservedImport.publishedPresetId)
            assertEquals(importedSummaryBeforeDelete.sourceRevisionId, preservedImport.sourceRevisionId)
            assertEquals(importedSummaryBeforeDelete.importedPresetId, preservedImport.importedPresetId)
            assertEquals(importedSummaryBeforeDelete.status, preservedImport.status)
        }

        @Test
        fun `published preset catalog searches filters sorts and limits for web registry`() {
            val coding =
                service.createPreset(
                    guildId = 340,
                    ownerUserId = 77,
                    name = "코딩 튜터",
                    summary = "Kotlin Spring 개발 질문",
                    category = "dev",
                    visibility = "guild_private",
                    behavior =
                        PresetBehaviorInput(
                            purpose = "코드 리뷰와 에러 분석",
                            tone = "practical",
                            responseMode = "deep",
                            tags = listOf("Kotlin", "code review", "token=secret"),
                            exampleQuestions = listOf("이 Kotlin 에러 왜 나나요?", "이 코드 리뷰해줘", "api_key=secret"),
                        ),
                )
            val translation =
                service.createPreset(
                    guildId = 340,
                    ownerUserId = 78,
                    name = "번역냥",
                    summary = "한국어 영어 번역",
                    category = "translation",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "번역과 문장 다듬기", tone = "polite", responseMode = "fast"),
                )
            val ops =
                service.createPreset(
                    guildId = 340,
                    ownerUserId = 79,
                    name = "운영냥",
                    summary = "런북 기반 운영 안내",
                    category = "ops",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "운영 질문 대응", tone = "formal", responseMode = "balanced"),
                )
            val risky =
                service.createPreset(
                    guildId = 340,
                    ownerUserId = 80,
                    name = "위험하지만 인기 프리셋",
                    summary = "안전 검토가 필요한 자동화",
                    category = "dev",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "위험 자동화", tone = "direct", safetyLevel = "high"),
                )
            val publishedCoding = service.publishPreset(coding.id, publisherUserId = 77, title = null, description = null)
            val publishedTranslation = service.publishPreset(translation.id, publisherUserId = 78, title = null, description = null)
            service.publishPreset(ops.id, publisherUserId = 79, title = null, description = null)
            val publishedRisky = service.publishPreset(risky.id, publisherUserId = 80, title = null, description = null)
            service.likePreset(publishedCoding.id, userId = 900)
            service.likePreset(publishedTranslation.id, userId = 901)
            service.likePreset(publishedTranslation.id, userId = 902)
            (910L..919L).forEach { service.likePreset(publishedRisky.id, userId = it) }

            val queryResult = controller.publishedPresets(query = "코드", sort = "popular", limit = 10)["presets"] as List<*>
            val queryPreset = queryResult.single() as PublishedPresetSummary
            assertEquals("코딩 튜터", queryPreset.title)
            assertEquals("dev", queryPreset.category)
            assertEquals(listOf("kotlin", "code-review"), queryPreset.tags)

            val tagQueryResult = controller.publishedPresets(query = "code-review", sort = "popular", limit = 10)["presets"] as List<*>
            assertEquals("코딩 튜터", (tagQueryResult.single() as PublishedPresetSummary).title)
            val codingDetail = controller.publishedPresetDetail(publishedCoding.id)["preset"] as PublishedPresetDetail
            assertEquals(listOf("kotlin", "code-review"), codingDetail.published.tags)
            assertEquals(listOf("kotlin", "code-review"), codingDetail.behavior.tags)
            assertEquals(listOf("이 Kotlin 에러 왜 나나요?", "이 코드 리뷰해줘"), codingDetail.behavior.exampleQuestions)
            val slugDetail = controller.publishedPresetDetailBySlug(publishedCoding.slug)["preset"] as PublishedPresetDetail
            assertEquals(publishedCoding.id, slugDetail.published.id)

            val categoryResult = controller.publishedPresets(category = "translation", sort = "popular", limit = 10)["presets"] as List<*>
            val categoryPreset = categoryResult.single() as PublishedPresetSummary
            assertEquals("번역냥", categoryPreset.title)

            val popular = controller.publishedPresets(sort = "likes", limit = 2)["presets"] as List<*>
            val topPreset = popular.first() as PublishedPresetSummary
            assertEquals(2, popular.size)
            assertEquals("위험하지만 인기 프리셋", topPreset.title)
            assertEquals(10, topPreset.likeCount)
            assertEquals("standard", topPreset.minQualityTier)

            val recommendations = controller.recommendedPresets(limit = 4)["recommendations"] as List<*>
            val topRecommendation = recommendations.first() as PresetRecommendation
            assertEquals("번역냥", topRecommendation.preset.title)
            assertTrue(
                recommendations.any {
                    val recommendation = it as PresetRecommendation
                    recommendation.preset.title == "위험하지만 인기 프리셋" &&
                        recommendation.reasons.any { reason ->
                            reason.startsWith("safetyPenalty=")
                        }
                },
            )
            val devRecommendations = controller.recommendedPresets(category = "dev", limit = 1)["recommendations"] as List<*>
            assertEquals("코딩 튜터", (devRecommendations.single() as PresetRecommendation).preset.title)

            val facets = controller.catalogFacets()["facets"] as PresetCatalogFacets
            assertEquals(4, facets.totalPublished)
            assertEquals(13, facets.totalLikes)
            assertTrue(facets.categories.any { it.value == "dev" && it.count == 2 })
            assertTrue(facets.tags.any { it.value == "kotlin" && it.count == 1 })
            assertTrue(facets.tags.any { it.value == "code-review" && it.count == 1 })
            assertTrue(facets.categories.any { it.value == "translation" && it.count == 1 })
            assertTrue(facets.responseModes.any { it.value == "deep" && it.count == 1 })
            assertTrue(facets.qualityTiers.any { it.value == "standard" && it.count == 4 })
            assertEquals("위험하지만 인기 프리셋", facets.topPresets.first().title)

            val report = service.reportPreset(publishedRisky.id, reporterUserId = 990, reason = "추천 제외 검토")
            service.reviewReport(report.id, decision = "dismiss")

            val catalogAfterDismiss = controller.publishedPresets(sort = "likes", limit = 2)["presets"] as List<*>
            assertEquals("위험하지만 인기 프리셋", (catalogAfterDismiss.first() as PublishedPresetSummary).title)
            val recommendationsAfterDismiss = controller.recommendedPresets(limit = 4)["recommendations"] as List<*>
            assertTrue(
                recommendationsAfterDismiss.none {
                    val recommendation = it as PresetRecommendation
                    recommendation.preset.title == "위험하지만 인기 프리셋"
                },
            )
            val facetsAfterDismiss = controller.catalogFacets()["facets"] as PresetCatalogFacets
            assertEquals(4, facetsAfterDismiss.totalPublished)
            assertEquals("번역냥", facetsAfterDismiss.topPresets.first().title)
        }

        @Test
        fun `published preset rejects secret bearing revisions`() {
            val preset =
                service.createPreset(
                    guildId = 320,
                    ownerUserId = 77,
                    name = "비밀 포함 프리셋",
                    summary = "공개되면 안 됨",
                    category = "security",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "개발", constitution = "api_key=secret-value"),
                )

            assertThrows(IllegalArgumentException::class.java) {
                service.publishPreset(preset.id, publisherUserId = 77, title = null, description = null)
            }
            assertEquals("draft", presets.findById(preset.id).orElseThrow().status)
            assertEquals(0, publishedPresets.findByStatusOrderByLikeCountDescPublishedAtDesc("published").size)

            service.updatePreset(
                presetId = preset.id,
                actorUserId = 77,
                name = null,
                summary = null,
                category = null,
                visibility = null,
                behavior = PresetBehaviorInput(purpose = "개발", constitution = "민감정보를 요구하지 않음"),
            )
            val published = service.publishPreset(preset.id, publisherUserId = 77, title = null, description = null)

            assertThrows(IllegalArgumentException::class.java) {
                controller.updatePublished(
                    published.id,
                    UpdatePublishedPresetRequest(
                        actorUserId = 77,
                        behavior = PresetBehaviorInput(purpose = "운영", changeSummary = "token=hidden"),
                    ),
                )
            }
            val publishedAfterFailedUpdate = publishedPresets.findById(published.id).orElseThrow()
            assertEquals(published.revisionId, publishedAfterFailedUpdate.revisionId)
        }

        @Test
        fun `published preset rejects secret bearing public metadata`() {
            val preset =
                service.createPreset(
                    guildId = 321,
                    ownerUserId = 77,
                    name = "안전 프리셋",
                    summary = "공개 설명",
                    category = "dev",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "개발 질문", tone = "practical"),
                )

            assertThrows(IllegalArgumentException::class.java) {
                service.publishPreset(preset.id, publisherUserId = 77, title = "코딩 token=super-secret", description = null)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.publishPreset(preset.id, publisherUserId = 77, title = null, description = "api_key=secret-value")
            }
            assertEquals("draft", presets.findById(preset.id).orElseThrow().status)

            val categorySecret =
                service.createPreset(
                    guildId = 321,
                    ownerUserId = 77,
                    name = "카테고리 비밀",
                    summary = "안전",
                    category = "token=category-secret",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "개발 질문"),
                )
            assertThrows(IllegalArgumentException::class.java) {
                service.publishPreset(categorySecret.id, publisherUserId = 77, title = null, description = null)
            }

            val published = service.publishPreset(preset.id, publisherUserId = 77, title = "안전 제목", description = "안전 설명")
            assertThrows(IllegalArgumentException::class.java) {
                service.updatePublishedPreset(
                    publishedPresetId = published.id,
                    actorUserId = 77,
                    title = "업데이트 token=hidden",
                    description = null,
                    behavior = null,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.updatePublishedPreset(
                    publishedPresetId = published.id,
                    actorUserId = 77,
                    title = null,
                    description = "DISCORD_BOT_TOKEN=hidden",
                    behavior = null,
                )
            }

            val afterFailedUpdate = publishedPresets.findById(published.id).orElseThrow()
            assertEquals("안전 제목", afterFailedUpdate.title)
            assertEquals("안전 설명", afterFailedUpdate.description)
        }

        @Test
        fun `legacy secret bearing published preset is masked and cannot be imported`() {
            val preset =
                service.createPreset(
                    guildId = 322,
                    ownerUserId = 77,
                    name = "레거시 프리셋",
                    summary = "공개 설명",
                    category = "dev",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "개발 질문", tone = "practical"),
                )
            val published = service.publishPreset(preset.id, publisherUserId = 77, title = "레거시", description = "안전")
            published.title = "token=legacy-secret"
            published.description = "api_key=legacy-secret"
            published.slug = "token-legacy-secret"
            publishedPresets.saveAndFlush(published)
            val revision = revisions.findById(published.revisionId).orElseThrow()
            revision.purpose = "api_key=legacy-purpose"
            revisions.saveAndFlush(revision)

            val summary = service.searchPublishedPresets().single()
            assertEquals("비공개 프리셋", summary.title)
            assertEquals("[비공개 처리됨]", summary.description)
            assertEquals("preset-${published.id}", summary.slug)
            assertEquals("[비공개 처리됨]", summary.purpose)
            val detail = service.publishedPresetDetail(published.id)
            assertEquals("[비공개 처리됨]", detail.behavior.purpose)
            assertThrows(IllegalArgumentException::class.java) {
                service.previewImport(published.id, targetGuildId = 323, targetChannelId = 423)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.importPreset(published.id, targetGuildId = 323, targetChannelId = 423, importedBy = 88)
            }
        }

        @Test
        fun `preset shares rag knowledge slots as placeholders without copying source text`() {
            val preset =
                service.createPreset(
                    guildId = 330,
                    ownerUserId = 77,
                    name = "운영 RAG 프리셋",
                    summary = "지식 슬롯만 공유",
                    category = "ops",
                    visibility = "guild_private",
                    behavior =
                        PresetBehaviorInput(
                            purpose = "운영 지식 기반 답변",
                            tone = "practical",
                            tags = listOf("ops", "runbook"),
                            knowledgeSlotNames = listOf("운영 규칙", "장애 대응", "운영 규칙"),
                            knowledgeGuide = "README/런북/FAQ를 대상 서버 지식으로 등록하세요. token=secret-value",
                            exampleQuestions = listOf("장애 대응 절차 알려줘", "운영 규칙 요약해줘"),
                        ),
                )
            val revision = revisions.findByPresetIdOrderByRevisionDesc(preset.id).single()
            assertEquals("ops,runbook", revision.tags)
            assertEquals("운영 규칙,장애 대응", revision.knowledgeSlotNames)
            assertEquals("장애 대응 절차 알려줘\n운영 규칙 요약해줘", revision.exampleQuestions)
            assertEquals("README/런북/FAQ를 대상 서버 지식으로 등록하세요. [redacted]", revision.knowledgeGuide)

            val published = service.publishPreset(preset.id, publisherUserId = 77, title = null, description = null)
            val detail = service.publishedPresetDetail(published.id)

            assertEquals(listOf("ops", "runbook"), detail.behavior.tags)
            assertEquals(listOf("운영 규칙", "장애 대응"), detail.behavior.knowledgeSlotNames)
            assertEquals(listOf("장애 대응 절차 알려줘", "운영 규칙 요약해줘"), detail.behavior.exampleQuestions)
            assertEquals("README/런북/FAQ를 대상 서버 지식으로 등록하세요. [redacted]", detail.behavior.knowledgeGuide)

            val preview = service.previewImport(published.id, targetGuildId = 331, targetChannelId = 431)
            assertEquals(listOf("ops", "runbook"), preview.tags)
            assertEquals(listOf("운영 규칙", "장애 대응"), preview.knowledgeSlotNames)
            assertEquals(listOf("장애 대응 절차 알려줘", "운영 규칙 요약해줘"), preview.exampleQuestions)

            val imported =
                service.importPreset(
                    publishedPresetId = published.id,
                    targetGuildId = 331,
                    targetChannelId = 431,
                    importedBy = 88,
                )
            val importedPresetId = imported.importedPresetId!!
            val importedRevision = revisions.findByPresetIdOrderByRevisionDesc(importedPresetId).single()
            assertEquals("ops,runbook", importedRevision.tags)
            assertEquals("운영 규칙,장애 대응", importedRevision.knowledgeSlotNames)
            assertEquals("장애 대응 절차 알려줘\n운영 규칙 요약해줘", importedRevision.exampleQuestions)
            assertEquals(
                "imported from published preset #${published.id}",
                importedRevision.changeSummary,
                "프리셋 import 는 지식 원문이나 source id 를 복사하지 않고 슬롯 안내만 유지한다",
            )
        }

        @Test
        fun `import preview detects overwrites and does not mutate target channel`() {
            val preset =
                service.createPreset(
                    guildId = 300,
                    ownerUserId = 77,
                    name = "운영 도우미",
                    summary = "운영 채널용",
                    category = "ops",
                    visibility = "guild_private",
                    behavior =
                        PresetBehaviorInput(
                            purpose = "운영 문의 응답",
                            tone = "formal",
                            answerLength = "short",
                            responseMode = "deep",
                            preferredModel = "llama3.1:8b",
                            maxCandidates = 3,
                            providerTagFilter = listOf("ops"),
                        ),
                )
            val published = service.publishPreset(preset.id, publisherUserId = 77, title = null, description = null)
            val existingChannel =
                channelAis.saveAndFlush(
                    ChannelAiEntity(
                        guildId = 301,
                        channelId = 401,
                        displayName = "기존 채널 AI",
                        source = "manual",
                        activeBehaviorVersionId = 999,
                        createdAt = Instant.parse("2026-05-30T00:00:00Z"),
                    ),
                )
            routingPolicies.saveAndFlush(
                ChannelAiRoutingPolicyEntity(
                    guildId = 301,
                    channelId = 401,
                    channelAiId = existingChannel.id,
                    responseMode = "fast",
                    preferredModel = "old-model",
                    createdAt = Instant.parse("2026-05-30T00:00:00Z"),
                    updatedAt = Instant.parse("2026-05-30T00:00:00Z"),
                ),
            )

            val response =
                controller.importPreview(
                    published.id,
                    ImportPresetRequest(targetGuildId = 301, targetChannelId = 401, actorUserId = 88),
                )
            val preview = response["preview"] as PresetImportPreview

            assertEquals("overwrite_channel_ai", preview.action)
            assertEquals(true, preview.willOverwriteChannelAi)
            assertEquals(true, preview.willOverwriteRoutingPolicy)
            assertEquals(false, preview.willCreateApprovalProposal)
            assertEquals("deep", preview.responseMode)
            assertEquals("llama3.1:8b", preview.preferredModel)
            assertEquals(listOf("ops"), preview.providerTagFilter)
            assertTrue(preview.conflicts.any { it.code == "existing_channel_ai_behavior" })
            assertTrue(preview.conflicts.any { it.code == "existing_routing_policy" })
            assertTrue(preview.conflicts.any { it.code == "multi_candidate_fanout" })
            assertEquals(0, imports.findByTargetGuildId(301).size)
            assertEquals("기존 채널 AI", channelAis.findByGuildIdAndChannelId(301, 401)?.displayName)
            assertEquals("fast", routingPolicies.findByGuildIdAndChannelId(301, 401)?.responseMode)
            assertThrows(IllegalArgumentException::class.java) {
                controller.importPreset(
                    published.id,
                    ImportPresetRequest(targetGuildId = 301, targetChannelId = 401, actorUserId = 88),
                )
            }
            assertEquals(0, imports.findByTargetGuildId(301).size)

            val imported =
                controller.importPreset(
                    published.id,
                    ImportPresetRequest(targetGuildId = 301, targetChannelId = 401, actorUserId = 88, confirmConflicts = true),
                )
            assertEquals("applied", imported["status"])
            assertEquals(1, imports.findByTargetGuildId(301).size)
        }

        @Test
        fun `published preset update creates new revision and never mutates existing import`() {
            val preset =
                service.createPreset(
                    guildId = 310,
                    ownerUserId = 77,
                    name = "지원냥",
                    summary = "고객지원",
                    category = "support",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "고객지원 v1", tone = "friendly", responseMode = "fast"),
                )
            val published = service.publishPreset(preset.id, publisherUserId = 77, title = "지원냥", description = "v1")
            val originalRevisionId = published.revisionId
            val importedV1 =
                controller.importPreset(
                    published.id,
                    ImportPresetRequest(targetGuildId = 311, targetChannelId = 411, actorUserId = 88),
                )
            assertEquals(originalRevisionId, importedV1["sourceRevisionId"])
            val v1BehaviorId = importedV1["createdBehaviorVersionId"] as Long
            val importedChannel = channelAis.findByGuildIdAndChannelId(311, 411)!!
            assertEquals("고객지원 v1", behaviorVersions.findByChannelAiIdAndId(importedChannel.id, v1BehaviorId)?.purpose)

            val updated =
                controller.updatePublished(
                    published.id,
                    UpdatePublishedPresetRequest(
                        actorUserId = 77,
                        title = "지원냥 v2",
                        description = "v2",
                        behavior = PresetBehaviorInput(purpose = "고객지원 v2", tone = "professional", responseMode = "deep"),
                    ),
                )
            val newRevisionId = updated["revisionId"] as Long

            assertTrue(newRevisionId != originalRevisionId)
            assertEquals(2, revisions.findByPresetIdOrderByRevisionDesc(preset.id).first().revision)
            assertEquals(v1BehaviorId, channelAis.findByGuildIdAndChannelId(311, 411)?.activeBehaviorVersionId)
            assertEquals("고객지원 v1", behaviorVersions.findByChannelAiIdAndId(importedChannel.id, v1BehaviorId)?.purpose)
            val detail = controller.publishedPresetDetail(published.id)["preset"] as PublishedPresetDetail
            assertEquals("고객지원 v2", detail.behavior.purpose)
            assertEquals("deep", detail.behavior.responseMode)

            val importedV2 =
                controller.importPreset(
                    published.id,
                    ImportPresetRequest(targetGuildId = 312, targetChannelId = 412, actorUserId = 89),
                )
            assertEquals(newRevisionId, importedV2["sourceRevisionId"])
            val v2Channel = channelAis.findByGuildIdAndChannelId(312, 412)!!
            val v2Behavior = behaviorVersions.findByChannelAiIdAndId(v2Channel.id, importedV2["createdBehaviorVersionId"] as Long)
            assertEquals("고객지원 v2", v2Behavior?.purpose)
            assertEquals("professional", v2Behavior?.tone)

            val history = controller.importHistory(311)["imports"] as List<*>
            val importedSummary = history.single() as PresetImportSummary
            assertEquals(published.id, importedSummary.publishedPresetId)
            assertEquals(311, importedSummary.targetGuildId)
            assertEquals(411, importedSummary.targetChannelId)
            assertEquals(88, importedSummary.importedBy)
            assertEquals(originalRevisionId, importedSummary.sourceRevisionId)
            assertEquals(true, importedSummary.detachedCopy)
            assertEquals(v1BehaviorId, importedSummary.createdBehaviorVersionId)

            val channelHistory = controller.importHistory(311, channelId = 999)["imports"] as List<*>
            assertTrue(channelHistory.isEmpty())
        }

        @Test
        fun `high risk import preview clearly shows approval proposal action`() {
            val preset =
                service.createPreset(
                    guildId = 302,
                    ownerUserId = 77,
                    name = "위험 프리셋 미리보기",
                    summary = null,
                    category = "ops",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "운영", tone = "direct", safetyLevel = "high"),
                )
            val published = service.publishPreset(preset.id, publisherUserId = 77, title = null, description = null)

            val preview = service.previewImport(published.id, targetGuildId = 303, targetChannelId = 402)

            assertEquals("propose_review", preview.action)
            assertEquals(true, preview.willApplyToChannel)
            assertEquals(true, preview.willCreateApprovalProposal)
            assertTrue(preview.conflicts.any { it.code == "high_risk_requires_review" && it.severity == "blocker" })
            assertEquals(0, proposals.findByGuildIdAndStatus(303, "pending").size)
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
        fun `removed published preset is hidden and blocked while preserving import history`() {
            val preset =
                service.createPreset(
                    guildId = 120,
                    ownerUserId = 77,
                    name = "공유 번역 프리셋",
                    summary = "번역 채널용",
                    category = "translation",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "번역", tone = "polite", responseMode = "fast"),
                )
            val published = service.publishPreset(preset.id, publisherUserId = 77, title = null, description = null)
            service.likePreset(published.id, userId = 88)
            val imported =
                service.importPreset(
                    publishedPresetId = published.id,
                    targetGuildId = 121,
                    targetChannelId = 221,
                    importedBy = 89,
                )

            val removed = controller.deletePublished(published.id)

            assertEquals("removed", removed["status"])
            assertEquals(0, service.searchPublishedPresets().size)
            assertEquals(0, service.recommendedPublishedPresets().size)
            assertEquals(0, service.catalogFacets().totalPublished)
            assertThrows(IllegalArgumentException::class.java) {
                service.publishedPresetDetail(published.id)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.previewImport(published.id, targetGuildId = 122, targetChannelId = 222)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.importPreset(published.id, targetGuildId = 122, targetChannelId = 222, importedBy = 90)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.likePreset(published.id, userId = 91)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.unlikePreset(published.id, userId = 88)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.reportPreset(published.id, reporterUserId = 92, reason = "삭제 후 신고 차단")
            }
            val history = service.importHistory(targetGuildId = 121).single()
            assertEquals(published.id, history.publishedPresetId)
            assertEquals(published.revisionId, history.sourceRevisionId)
            assertEquals(imported.importedPresetId, history.importedPresetId)
            assertEquals(imported.createdBehaviorVersionId, history.createdBehaviorVersionId)
        }

        @Test
        fun `unlisted published preset is hidden blocked and can be republished`() {
            val preset =
                service.createPreset(
                    guildId = 130,
                    ownerUserId = 77,
                    name = "공유 공지 프리셋",
                    summary = "공지 작성용",
                    category = "notice",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "공지 작성", tone = "clear", responseMode = "balanced"),
                )
            val published = service.publishPreset(preset.id, publisherUserId = 77, title = null, description = null)
            service.likePreset(published.id, userId = 88)

            val unlisted = controller.unlistPublished(published.id)

            assertEquals("unlisted", unlisted["status"])
            assertEquals(0, service.searchPublishedPresets().size)
            assertEquals(0, service.recommendedPublishedPresets().size)
            assertThrows(IllegalArgumentException::class.java) {
                service.publishedPresetDetail(published.id)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.previewImport(published.id, targetGuildId = 131, targetChannelId = 231)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.importPreset(published.id, targetGuildId = 131, targetChannelId = 231, importedBy = 89)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.likePreset(published.id, userId = 89)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.unlikePreset(published.id, userId = 88)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.reportPreset(published.id, reporterUserId = 90, reason = "비공개 후 신고 차단")
            }

            val republished = controller.republishPublished(published.id)

            assertEquals("published", republished["status"])
            assertEquals(1, service.searchPublishedPresets().size)
            assertEquals("공유 공지 프리셋", service.publishedPresetDetail(published.id).published.title)
            val imported = service.importPreset(published.id, targetGuildId = 131, targetChannelId = 231, importedBy = 89)
            assertEquals(published.revisionId, imported.sourceRevisionId)
            assertEquals(1, service.importHistory(targetGuildId = 131).size)
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

            val imported =
                service.importPreset(
                    published.id,
                    targetGuildId = 201,
                    targetChannelId = 301,
                    importedBy = 88,
                    confirmConflicts = true,
                )

            assertEquals("needs_review", imported.status)
            assertNotNull(imported.createdChannelAiId)
            assertNotNull(imported.createdBehaviorVersionId)
            val channelAi = channelAis.findByGuildIdAndChannelId(201, 301)
            assertNotNull(channelAi)
            assertEquals(null, channelAi?.activeBehaviorVersionId)
            assertEquals(null, routingPolicies.findByGuildIdAndChannelId(201, 301))
            val proposal = proposals.findByGuildIdAndStatus(201, "pending").single()
            assertNotNull(proposal.payloadHash)
            assertNotNull(proposal.routingSnapshot)
            assertEquals(imported.createdBehaviorVersionId, proposal.proposedBehaviorId)
            assertEquals(1, audits.findTop10ByGuildIdAndChannelIdOrderByCreatedAtDesc(201, 301).size)
        }

        @Test
        fun `high risk preset import does not overwrite existing routing before approval`() {
            val preset =
                service.createPreset(
                    guildId = 210,
                    ownerUserId = 77,
                    name = "위험 라우팅 프리셋",
                    summary = null,
                    category = "ops",
                    visibility = "guild_private",
                    behavior =
                        PresetBehaviorInput(
                            purpose = "운영",
                            tone = "direct",
                            safetyLevel = "high",
                            responseMode = "deep",
                            preferredModel = "qwen-coder",
                            maxCandidates = 3,
                        ),
                )
            val published = service.publishPreset(preset.id, publisherUserId = 77, title = null, description = null)
            val existing =
                channelAis.saveAndFlush(
                    ChannelAiEntity(
                        guildId = 211,
                        channelId = 311,
                        displayName = "기존",
                        source = "manual",
                        activeBehaviorVersionId = 999,
                        createdAt = Instant.parse("2026-05-30T00:00:00Z"),
                    ),
                )
            routingPolicies.saveAndFlush(
                ChannelAiRoutingPolicyEntity(
                    guildId = 211,
                    channelId = 311,
                    channelAiId = existing.id,
                    responseMode = "fast",
                    preferredModel = "old-model",
                    maxCandidates = 1,
                    createdAt = Instant.parse("2026-05-30T00:00:00Z"),
                    updatedAt = Instant.parse("2026-05-30T00:00:00Z"),
                ),
            )

            val imported =
                service.importPreset(
                    published.id,
                    targetGuildId = 211,
                    targetChannelId = 311,
                    importedBy = 88,
                    confirmConflicts = true,
                )

            assertEquals("needs_review", imported.status)
            assertEquals(999, channelAis.findByGuildIdAndChannelId(211, 311)?.activeBehaviorVersionId)
            val routing = routingPolicies.findByGuildIdAndChannelId(211, 311)
            assertEquals("fast", routing?.responseMode)
            assertEquals("old-model", routing?.preferredModel)
            assertEquals(1, routing?.maxCandidates)
            val proposal = proposals.findByGuildIdAndStatus(211, "pending").single()
            val customization =
                ChannelAiCustomizationService(
                    channelAis = channelAis,
                    versions = behaviorVersions,
                    proposals = proposals,
                    audits = audits,
                    routingPolicies = routingPolicies,
                    clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
                )

            val approved = customization.approveProposal(proposal.id, reviewerUserId = 99)

            assertEquals("approved", approved.status)
            assertEquals(imported.createdBehaviorVersionId, channelAis.findByGuildIdAndChannelId(211, 311)?.activeBehaviorVersionId)
            val approvedRouting = routingPolicies.findByGuildIdAndChannelId(211, 311)
            assertEquals("deep", approvedRouting?.responseMode)
            assertEquals("qwen-coder", approvedRouting?.preferredModel)
            assertEquals(3, approvedRouting?.maxCandidates)
            assertEquals(imported.createdChannelAiId, approvedRouting?.channelAiId)
        }

        @Test
        fun `moderation summary prioritizes reported popular and high safety presets`() {
            val popularPreset =
                service.createPreset(
                    guildId = 360,
                    ownerUserId = 77,
                    name = "인기 운영 프리셋",
                    summary = "신고 테스트",
                    category = "ops",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "운영", tone = "friendly"),
                )
            val highRiskPreset =
                service.createPreset(
                    guildId = 360,
                    ownerUserId = 78,
                    name = "고위험 검토 프리셋",
                    summary = "수동 검토 필요",
                    category = "ops",
                    visibility = "guild_private",
                    behavior = PresetBehaviorInput(purpose = "운영", tone = "direct", safetyLevel = "high"),
                )
            val popular = service.publishPreset(popularPreset.id, publisherUserId = 77, title = null, description = null)
            val highRisk = service.publishPreset(highRiskPreset.id, publisherUserId = 78, title = null, description = null)
            (1L..5L).forEach { service.likePreset(popular.id, userId = 1000 + it) }
            service.reportPreset(
                popular.id,
                reporterUserId = 88,
                reason = "자동화가 위험해 보여요 token=hidden",
                reasonCode = "sensitive_data",
            )

            val summary = controller.moderationSummary()["summary"] as PresetModerationSummary

            assertEquals(2, summary.totalPublishedRows)
            assertEquals(1, summary.activePublishedCount)
            assertEquals(1, summary.underReviewCount)
            assertEquals(1, summary.openReportCount)
            assertTrue(summary.nextActions.any { it.contains("open 신고") })
            assertTrue(summary.nextActions.any { it.contains("high/restricted") })
            val popularQueueItem = summary.queue.first { it.publishedPresetId == popular.id }
            assertEquals("under_review", popularQueueItem.status)
            assertTrue(popularQueueItem.riskCodes.contains("popular_reported"))
            assertTrue(popularQueueItem.riskCodes.contains("reported"))
            assertEquals(1, popularQueueItem.reportReasonCodes["sensitive_data"])
            assertTrue(popularQueueItem.recommendedAction.contains("인기 프리셋"))
            val highRiskQueueItem = summary.queue.first { it.publishedPresetId == highRisk.id }
            assertTrue(highRiskQueueItem.riskCodes.contains("high_safety_level"))
            assertEquals("high", highRiskQueueItem.safetyLevel)
        }
    }
