package com.discordassistant.central.preset.adapter.outbound.persistence

import com.discordassistant.central.preset.domain.model.PresetImportStatus
import com.discordassistant.central.preset.domain.model.PresetReportStatus
import com.discordassistant.central.preset.domain.model.PresetStatus
import com.discordassistant.central.preset.domain.model.PublishedPresetStatus
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** preset 도메인 JPA(adapter/out): 프리셋/리비전/발행/가져오기/반응/신고. */

@Entity
@Table(name = "ai_preset")
class AiPresetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var ownerUserId: Long? = null,
    var name: String = "",
    var summary: String? = null,
    var category: String = "general",
    var visibility: String = "guild_private",
    @Convert(converter = PresetStatusConverter::class)
    var status: PresetStatus = PresetStatus.DRAFT,
    var currentRevisionId: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "preset_revision")
class PresetRevisionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var presetId: Long = 0,
    var revision: Int = 1,
    var name: String = "",
    var purpose: String = "",
    var tone: String = "",
    var answerLength: String = "balanced",
    var constitution: String? = null,
    var safetyLevel: String = "standard",
    var responseMode: String = "balanced",
    var preferredModel: String? = null,
    var minQualityTier: String = "standard",
    var maxCandidates: Int = 1,
    var providerTagFilter: String? = null,
    var tags: String? = null,
    var costGuard: String = "provider_safe",
    var knowledgeSlotNames: String? = null,
    var knowledgeGuide: String? = null,
    var exampleQuestions: String? = null,
    var changeSummary: String? = null,
    var createdBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "published_preset")
class PublishedPresetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var presetId: Long = 0,
    var revisionId: Long = 0,
    var publisherGuildId: Long = 0,
    var publisherUserId: Long? = null,
    var slug: String = "",
    var title: String = "",
    var description: String? = null,
    @Convert(converter = PublishedPresetStatusConverter::class)
    var status: PublishedPresetStatus = PublishedPresetStatus.PUBLISHED,
    var likeCount: Int = 0,
    var importCount: Int = 0,
    var reportCount: Int = 0,
    var publishedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "preset_import")
class PresetImportEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var publishedPresetId: Long = 0,
    var sourceRevisionId: Long? = null,
    var targetGuildId: Long = 0,
    var targetChannelId: Long? = null,
    var importedBy: Long? = null,
    var importedPresetId: Long? = null,
    var createdChannelAiId: Long? = null,
    var createdBehaviorVersionId: Long? = null,
    @Convert(converter = PresetImportStatusConverter::class)
    var status: PresetImportStatus = PresetImportStatus.IMPORTED,
    var importedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "preset_reaction")
class PresetReactionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var publishedPresetId: Long = 0,
    var userId: Long = 0,
    var reaction: String = "like",
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "preset_report")
class PresetReportEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var publishedPresetId: Long = 0,
    var reporterUserId: Long? = null,
    var reason: String = "",
    var reasonCode: String = "other",
    var details: String? = null,
    @Convert(converter = PresetReportStatusConverter::class)
    var status: PresetReportStatus = PresetReportStatus.OPEN,
    var createdAt: Instant = Instant.EPOCH,
    var reviewedBy: Long? = null,
    var reviewedAt: Instant? = null,
)
