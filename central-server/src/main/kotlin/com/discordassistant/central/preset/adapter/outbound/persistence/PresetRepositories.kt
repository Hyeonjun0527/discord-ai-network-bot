package com.discordassistant.central.preset.adapter.outbound.persistence

import com.discordassistant.central.preset.domain.model.PresetReportStatus
import com.discordassistant.central.preset.domain.model.PublishedPresetStatus
import org.springframework.data.jpa.repository.JpaRepository

/** preset Spring Data JPA 리포지토리(adapter/out). */

interface AiPresetRepository : JpaRepository<AiPresetEntity, Long> {
    fun findByGuildId(guildId: Long): List<AiPresetEntity>
}

interface PresetRevisionRepository : JpaRepository<PresetRevisionEntity, Long> {
    fun findByPresetIdOrderByRevisionDesc(presetId: Long): List<PresetRevisionEntity>

    fun deleteByPresetId(presetId: Long)
}

interface PublishedPresetRepository : JpaRepository<PublishedPresetEntity, Long> {
    fun findByStatusOrderByLikeCountDescPublishedAtDesc(status: PublishedPresetStatus): List<PublishedPresetEntity>

    fun findBySlug(slug: String): PublishedPresetEntity?
}

interface PresetImportRepository : JpaRepository<PresetImportEntity, Long> {
    fun findByTargetGuildId(targetGuildId: Long): List<PresetImportEntity>
}

interface PresetReactionRepository : JpaRepository<PresetReactionEntity, Long> {
    fun findByPublishedPresetIdAndUserIdAndReaction(
        publishedPresetId: Long,
        userId: Long,
        reaction: String,
    ): PresetReactionEntity?
}

interface PresetReportRepository : JpaRepository<PresetReportEntity, Long> {
    fun findByStatus(status: PresetReportStatus): List<PresetReportEntity>

    fun findByPublishedPresetIdAndReporterUserIdAndStatus(
        publishedPresetId: Long,
        reporterUserId: Long,
        status: PresetReportStatus,
    ): PresetReportEntity?
}
