package com.discordassistant.central.preset.adapter.inbound.web

import com.discordassistant.central.preset.adapter.inbound.web.dto.CreatePresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.ImportPresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.ImportPresetResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.LikePresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.LikePresetResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetCatalogResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetImportHistoryResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetRecommendationResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetWebReadinessResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetWriteResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PublishPresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.PublishPresetResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.ReportPresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.ReportPresetResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.ReviewPresetReportRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.ReviewPresetReportResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.SaveChannelPresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.UpdatePresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.UpdatePublishedPresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.UpdatePublishedPresetResponse
import com.discordassistant.central.preset.application.PresetBehaviorInput
import com.discordassistant.central.preset.application.PresetRegistryService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 프리셋 공유/가져오기/추천 API. 인증/권한 게이트는 웹 대시보드 권한 레이어에서 확장한다. */
@RestController
@RequestMapping("/api/ai-network/presets")
class PresetRegistryController(
    private val registry: PresetRegistryService,
) {
    @GetMapping("/guilds/{guildId}")
    fun listGuildPresets(
        @PathVariable guildId: Long,
    ): Map<String, Any?> = mapOf("presets" to registry.listGuildPresets(guildId))

    @GetMapping("/local/{presetId}")
    fun presetDetail(
        @PathVariable presetId: Long,
    ): Map<String, Any?> = mapOf("preset" to registry.presetDetail(presetId))

    @GetMapping("/catalog")
    fun publishedPresets(
        @RequestParam(required = false) query: String? = null,
        @RequestParam(required = false) category: String? = null,
        @RequestParam(defaultValue = "popular") sort: String = "popular",
        @RequestParam(defaultValue = "20") limit: Int = 20,
    ): Map<String, Any?> =
        PresetCatalogResponse
            .from(registry.searchPublishedPresetsResult(query = query, category = category, sort = sort, limit = limit))
            .toMap()

    @GetMapping("/catalog/recommended")
    fun recommendedPresets(
        @RequestParam(required = false) category: String? = null,
        @RequestParam(defaultValue = "10") limit: Int = 10,
    ): Map<String, Any?> =
        PresetRecommendationResponse
            .from(registry.recommendedPublishedPresetsResult(category = category, limit = limit))
            .toMap()

    @GetMapping("/catalog/facets")
    fun catalogFacets(): Map<String, Any?> = mapOf("facets" to registry.catalogFacets())

    @GetMapping("/web-readiness")
    fun webReadiness(): Map<String, Any?> = PresetWebReadinessResponse.from(registry.webReadiness()).toMap()

    @GetMapping("/moderation/summary")
    fun moderationSummary(): Map<String, Any?> = mapOf("summary" to registry.moderationSummary())

    @GetMapping("/guilds/{guildId}/imports")
    fun importHistory(
        @PathVariable guildId: Long,
        @RequestParam(required = false) channelId: Long? = null,
    ): Map<String, Any?> =
        PresetImportHistoryResponse
            .from(registry.importHistoryResult(targetGuildId = guildId, targetChannelId = channelId))
            .toMap()

    @GetMapping("/reports")
    fun reports(): Map<String, Any?> = mapOf("reports" to registry.listReports())

    @GetMapping("/reports/{status}")
    fun reportsByStatus(
        @PathVariable status: String,
    ): Map<String, Any?> = mapOf("reports" to registry.listReports(status))

    @GetMapping("/catalog/slug/{slug}")
    fun publishedPresetDetailBySlug(
        @PathVariable slug: String,
    ): Map<String, Any?> = mapOf("preset" to registry.publishedPresetDetailBySlug(slug))

    @GetMapping("/catalog/{publishedPresetId}")
    fun publishedPresetDetail(
        @PathVariable publishedPresetId: Long,
    ): Map<String, Any?> = mapOf("preset" to registry.publishedPresetDetail(publishedPresetId))

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
        return PresetWriteResponse.from(preset).toMap()
    }

    @PostMapping("/guilds/{guildId}/channels/{channelId}/save-from-channel")
    fun saveFromChannel(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: SaveChannelPresetRequest,
    ): Map<String, Any?> {
        val preset =
            registry.saveChannelAsPreset(
                guildId = guildId,
                channelId = channelId,
                ownerUserId = request.actorUserId,
                name = request.name,
                summary = request.summary,
                category = request.category ?: "channel_ai",
                visibility = request.visibility ?: "guild_private",
            )
        return PresetWriteResponse.from(preset).toMap()
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
        return PresetWriteResponse.from(preset).toMap()
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
        return PublishPresetResponse.from(published).toMap()
    }

    @PutMapping("/published/{publishedPresetId}")
    fun updatePublished(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: UpdatePublishedPresetRequest,
    ): Map<String, Any?> {
        val published =
            registry.updatePublishedPreset(
                publishedPresetId = publishedPresetId,
                actorUserId = request.actorUserId,
                title = request.title,
                description = request.description,
                behavior = request.behavior,
            )
        return UpdatePublishedPresetResponse.from(published).toMap()
    }

    @PostMapping("/published/{publishedPresetId}/import-preview")
    fun importPreview(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: ImportPresetRequest,
    ): Map<String, Any?> =
        mapOf(
            "preview" to
                registry.previewImport(
                    publishedPresetId = publishedPresetId,
                    targetGuildId = request.targetGuildId,
                    targetChannelId = request.targetChannelId,
                ),
        )

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
                confirmConflicts = request.confirmConflicts,
            )
        return ImportPresetResponse.from(imported).toMap()
    }

    @PostMapping("/published/{publishedPresetId}/like")
    fun like(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: LikePresetRequest,
    ): Map<String, Any?> {
        val published = registry.likePreset(publishedPresetId, request.userId)
        return LikePresetResponse.from(published).toMap()
    }

    @DeleteMapping("/published/{publishedPresetId}/like")
    fun unlike(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: LikePresetRequest,
    ): Map<String, Any?> {
        val published = registry.unlikePreset(publishedPresetId, request.userId)
        return LikePresetResponse.from(published).toMap()
    }

    @PostMapping("/published/{publishedPresetId}/report")
    fun report(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: ReportPresetRequest,
    ): Map<String, Any?> {
        val report =
            registry.reportPreset(
                publishedPresetId = publishedPresetId,
                reporterUserId = request.reporterUserId,
                reason = request.reason ?: request.details ?: request.reasonCode ?: "other",
                reasonCode = request.reasonCode,
                details = request.details,
            )
        return ReportPresetResponse.from(report).toMap()
    }

    @PostMapping("/reports/{reportId}/review")
    fun reviewReport(
        @PathVariable reportId: Long,
        @RequestBody request: ReviewPresetReportRequest,
    ): Map<String, Any?> {
        val report = registry.reviewReport(reportId, request.decision, request.reviewerUserId)
        return ReviewPresetReportResponse.from(report).toMap()
    }

    @DeleteMapping("/{presetId}")
    fun delete(
        @PathVariable presetId: Long,
    ): Map<String, Any> {
        val preset = registry.deletePreset(presetId)
        return mapOf("deleted" to true, "status" to preset.status)
    }

    @DeleteMapping("/published/{publishedPresetId}")
    fun deletePublished(
        @PathVariable publishedPresetId: Long,
    ): Map<String, Any> {
        val published = registry.deletePublishedPreset(publishedPresetId)
        return mapOf("deleted" to true, "status" to published.status)
    }

    @PostMapping("/published/{publishedPresetId}/unlist")
    fun unlistPublished(
        @PathVariable publishedPresetId: Long,
    ): Map<String, Any> {
        val published = registry.unlistPublishedPreset(publishedPresetId)
        return mapOf("unlisted" to true, "status" to published.status)
    }

    @PostMapping("/published/{publishedPresetId}/republish")
    fun republishPublished(
        @PathVariable publishedPresetId: Long,
    ): Map<String, Any> {
        val published = registry.republishPreset(publishedPresetId)
        return mapOf("republished" to true, "status" to published.status)
    }
}
