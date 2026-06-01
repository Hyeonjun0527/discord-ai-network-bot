package com.discordassistant.central.dashboard

import com.discordassistant.central.network.PresetBehaviorInput
import com.discordassistant.central.network.PresetRegistryService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 프리셋 공유/가져오기/추천 API. 인증/권한 게이트는 웹 대시보드 권한 레이어에서 확장한다. */
@RestController
@RequestMapping("/api/ai-network/presets")
class PresetRegistryController(
    private val registry: PresetRegistryService,
) {
    @PostMapping("/{guildId}")
    fun create(
        @PathVariable guildId: Long,
        @RequestBody request: CreatePresetRequest,
    ): Map<String, Any?> {
        val preset =
            registry.createPreset(
                guildId = guildId,
                ownerUserId = request.actorUserId,
                name = request.name,
                summary = request.summary,
                category = request.category ?: "general",
                visibility = request.visibility ?: "guild_private",
                behavior = request.behavior ?: PresetBehaviorInput(),
            )
        return mapOf("id" to preset.id, "currentRevisionId" to preset.currentRevisionId, "status" to preset.status)
    }

    @PutMapping("/{presetId}")
    fun update(
        @PathVariable presetId: Long,
        @RequestBody request: UpdatePresetRequest,
    ): Map<String, Any?> {
        val preset =
            registry.updatePreset(
                presetId = presetId,
                actorUserId = request.actorUserId,
                name = request.name,
                summary = request.summary,
                category = request.category,
                visibility = request.visibility,
                behavior = request.behavior,
            )
        return mapOf("id" to preset.id, "currentRevisionId" to preset.currentRevisionId, "status" to preset.status)
    }

    @PostMapping("/{presetId}/publish")
    fun publish(
        @PathVariable presetId: Long,
        @RequestBody request: PublishPresetRequest,
    ): Map<String, Any?> {
        val published =
            registry.publishPreset(
                presetId = presetId,
                publisherUserId = request.actorUserId,
                title = request.title,
                description = request.description,
            )
        return mapOf("id" to published.id, "status" to published.status, "title" to published.title)
    }

    @PostMapping("/published/{publishedPresetId}/import")
    fun importPreset(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: ImportPresetRequest,
    ): Map<String, Any?> {
        val imported =
            registry.importPreset(
                publishedPresetId = publishedPresetId,
                targetGuildId = request.targetGuildId,
                targetChannelId = request.targetChannelId,
                importedBy = request.actorUserId,
            )
        return mapOf("id" to imported.id, "importedPresetId" to imported.importedPresetId, "status" to imported.status)
    }

    @PostMapping("/published/{publishedPresetId}/like")
    fun like(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: LikePresetRequest,
    ): Map<String, Any?> {
        val published = registry.likePreset(publishedPresetId, request.userId)
        return mapOf("id" to published.id, "likeCount" to published.likeCount)
    }

    @PostMapping("/published/{publishedPresetId}/report")
    fun report(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: ReportPresetRequest,
    ): Map<String, Any?> {
        val report = registry.reportPreset(publishedPresetId, request.reporterUserId, request.reason)
        return mapOf("id" to report.id, "status" to report.status)
    }

    @DeleteMapping("/{presetId}")
    fun delete(
        @PathVariable presetId: Long,
    ): Map<String, Any> {
        registry.deletePreset(presetId)
        return mapOf("deleted" to true)
    }

    @DeleteMapping("/published/{publishedPresetId}")
    fun deletePublished(
        @PathVariable publishedPresetId: Long,
    ): Map<String, Any> {
        registry.deletePublishedPreset(publishedPresetId)
        return mapOf("deleted" to true)
    }
}

data class CreatePresetRequest(
    val actorUserId: Long? = null,
    val name: String,
    val summary: String? = null,
    val category: String? = null,
    val visibility: String? = null,
    val behavior: PresetBehaviorInput? = null,
)

data class UpdatePresetRequest(
    val actorUserId: Long? = null,
    val name: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val visibility: String? = null,
    val behavior: PresetBehaviorInput? = null,
)

data class PublishPresetRequest(
    val actorUserId: Long? = null,
    val title: String? = null,
    val description: String? = null,
)

data class ImportPresetRequest(
    val targetGuildId: Long,
    val targetChannelId: Long? = null,
    val actorUserId: Long? = null,
)

data class LikePresetRequest(
    val userId: Long,
)

data class ReportPresetRequest(
    val reporterUserId: Long? = null,
    val reason: String,
)
