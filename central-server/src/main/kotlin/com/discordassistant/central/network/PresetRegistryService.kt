package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiPresetEntity
import com.discordassistant.central.persistence.AiPresetRepository
import com.discordassistant.central.persistence.PresetImportEntity
import com.discordassistant.central.persistence.PresetImportRepository
import com.discordassistant.central.persistence.PresetReactionEntity
import com.discordassistant.central.persistence.PresetReactionRepository
import com.discordassistant.central.persistence.PresetReportEntity
import com.discordassistant.central.persistence.PresetReportRepository
import com.discordassistant.central.persistence.PresetRevisionEntity
import com.discordassistant.central.persistence.PresetRevisionRepository
import com.discordassistant.central.persistence.PublishedPresetEntity
import com.discordassistant.central.persistence.PublishedPresetRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class PresetRegistryService(
    private val presets: AiPresetRepository,
    private val revisions: PresetRevisionRepository,
    private val publishedPresets: PublishedPresetRepository,
    private val imports: PresetImportRepository,
    private val reactions: PresetReactionRepository,
    private val reports: PresetReportRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun createPreset(
        guildId: Long,
        ownerUserId: Long?,
        name: String,
        summary: String?,
        category: String,
        visibility: String,
        behavior: PresetBehaviorInput,
    ): AiPresetEntity {
        val now = Instant.now(clock)
        val preset =
            presets.save(
                AiPresetEntity(
                    guildId = guildId,
                    ownerUserId = ownerUserId,
                    name = name.trim(),
                    summary = summary?.trim(),
                    category = category.trim().ifBlank { "general" },
                    visibility = visibility.trim().ifBlank { "guild_private" },
                    status = "draft",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        val revision = createRevision(preset, revision = 1, behavior = behavior, createdBy = ownerUserId, now = now)
        preset.currentRevisionId = revision.id
        preset.updatedAt = now
        return presets.save(preset)
    }

    @Transactional
    fun updatePreset(
        presetId: Long,
        actorUserId: Long?,
        name: String?,
        summary: String?,
        category: String?,
        visibility: String?,
        behavior: PresetBehaviorInput?,
    ): AiPresetEntity {
        val now = Instant.now(clock)
        val preset = presets.findById(presetId).orElseThrow { IllegalArgumentException("preset not found: $presetId") }
        name?.trim()?.takeIf { it.isNotBlank() }?.let { preset.name = it }
        summary?.trim()?.let { preset.summary = it.ifBlank { null } }
        category?.trim()?.takeIf { it.isNotBlank() }?.let { preset.category = it }
        visibility?.trim()?.takeIf { it.isNotBlank() }?.let { preset.visibility = it }
        if (behavior != null) {
            val nextRevision = (revisions.findByPresetIdOrderByRevisionDesc(preset.id).firstOrNull()?.revision ?: 0) + 1
            val revision = createRevision(preset, nextRevision, behavior, actorUserId, now)
            preset.currentRevisionId = revision.id
        }
        preset.updatedAt = now
        return presets.save(preset)
    }

    @Transactional
    fun publishPreset(
        presetId: Long,
        publisherUserId: Long?,
        title: String?,
        description: String?,
    ): PublishedPresetEntity {
        val preset = presets.findById(presetId).orElseThrow { IllegalArgumentException("preset not found: $presetId") }
        val revisionId =
            preset.currentRevisionId
                ?: revisions.findByPresetIdOrderByRevisionDesc(preset.id).firstOrNull()?.id
                ?: throw IllegalArgumentException("preset has no revision: $presetId")
        preset.status = "published"
        preset.visibility = "published"
        presets.save(preset)
        return publishedPresets.save(
            PublishedPresetEntity(
                presetId = preset.id,
                revisionId = revisionId,
                publisherGuildId = preset.guildId,
                publisherUserId = publisherUserId,
                title = title?.trim()?.ifBlank { null } ?: preset.name,
                description = description?.trim()?.ifBlank { null } ?: preset.summary,
                status = "published",
                publishedAt = Instant.now(clock),
            ),
        )
    }

    @Transactional
    fun importPreset(
        publishedPresetId: Long,
        targetGuildId: Long,
        targetChannelId: Long?,
        importedBy: Long?,
    ): PresetImportEntity {
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        val sourceRevision =
            revisions.findById(published.revisionId).orElseThrow {
                IllegalArgumentException("published revision not found: ${published.revisionId}")
            }
        val importedPreset =
            createPreset(
                guildId = targetGuildId,
                ownerUserId = importedBy,
                name = published.title,
                summary = published.description,
                category = "imported",
                visibility = "guild_private",
                behavior =
                    PresetBehaviorInput(
                        purpose = sourceRevision.purpose,
                        tone = sourceRevision.tone,
                        answerLength = sourceRevision.answerLength,
                        constitution = sourceRevision.constitution,
                        safetyLevel = sourceRevision.safetyLevel,
                        changeSummary = "imported from published preset #${published.id}",
                    ),
            )
        published.importCount += 1
        publishedPresets.save(published)
        return imports.save(
            PresetImportEntity(
                publishedPresetId = publishedPresetId,
                targetGuildId = targetGuildId,
                targetChannelId = targetChannelId,
                importedBy = importedBy,
                importedPresetId = importedPreset.id,
                importedAt = Instant.now(clock),
            ),
        )
    }

    @Transactional
    fun likePreset(
        publishedPresetId: Long,
        userId: Long,
    ): PublishedPresetEntity {
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        if (reactions.findByPublishedPresetIdAndUserIdAndReaction(publishedPresetId, userId, "like") == null) {
            reactions.save(
                PresetReactionEntity(
                    publishedPresetId = publishedPresetId,
                    userId = userId,
                    reaction = "like",
                    createdAt = Instant.now(clock),
                ),
            )
            published.likeCount += 1
        }
        return publishedPresets.save(published)
    }

    @Transactional
    fun reportPreset(
        publishedPresetId: Long,
        reporterUserId: Long?,
        reason: String,
    ): PresetReportEntity {
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        published.reportCount += 1
        publishedPresets.save(published)
        return reports.save(
            PresetReportEntity(
                publishedPresetId = publishedPresetId,
                reporterUserId = reporterUserId,
                reason = reason.trim().take(500),
                createdAt = Instant.now(clock),
            ),
        )
    }

    @Transactional
    fun deletePreset(presetId: Long) {
        presets.findById(presetId).ifPresent {
            it.currentRevisionId = null
            presets.save(it)
        }
        revisions.deleteByPresetId(presetId)
        presets.deleteById(presetId)
    }

    @Transactional
    fun deletePublishedPreset(publishedPresetId: Long) {
        publishedPresets.deleteById(publishedPresetId)
    }

    private fun createRevision(
        preset: AiPresetEntity,
        revision: Int,
        behavior: PresetBehaviorInput,
        createdBy: Long?,
        now: Instant,
    ): PresetRevisionEntity =
        revisions.save(
            PresetRevisionEntity(
                presetId = preset.id,
                revision = revision,
                name = preset.name,
                purpose = behavior.purpose.trim().ifBlank { "general_assistant" },
                tone = behavior.tone.trim().ifBlank { "friendly" },
                answerLength = behavior.answerLength.trim().ifBlank { "balanced" },
                constitution = behavior.constitution?.trim()?.ifBlank { null },
                safetyLevel = behavior.safetyLevel.trim().ifBlank { "standard" },
                changeSummary = behavior.changeSummary?.trim()?.ifBlank { null },
                createdBy = createdBy,
                createdAt = now,
            ),
        )
}

data class PresetBehaviorInput(
    val purpose: String = "general_assistant",
    val tone: String = "friendly",
    val answerLength: String = "balanced",
    val constitution: String? = null,
    val safetyLevel: String = "standard",
    val changeSummary: String? = null,
)
