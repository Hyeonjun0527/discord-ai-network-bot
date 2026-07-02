package com.discordassistant.central.preset.adapter.inbound.web

import com.discordassistant.central.preset.adapter.inbound.web.dto.CreatePresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.ImportPresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.ImportPresetResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.ImportPreviewResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.LikePresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.LikePresetResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetCatalogFacetsResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetCatalogResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetDeleteResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetDetailResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetImportHistoryResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetListResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetModerationSummaryResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetRecommendationResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetReportsResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetWebReadinessResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PresetWriteResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PublishPresetRequest
import com.discordassistant.central.preset.adapter.inbound.web.dto.PublishPresetResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PublishedPresetDeleteResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PublishedPresetDetailResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PublishedPresetRepublishResponse
import com.discordassistant.central.preset.adapter.inbound.web.dto.PublishedPresetUnlistResponse
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
    ): PresetListResponse = PresetListResponse(registry.listGuildPresets(guildId))

    @GetMapping("/local/{presetId}")
    fun presetDetail(
        @PathVariable presetId: Long,
    ): PresetDetailResponse = PresetDetailResponse(registry.presetDetail(presetId))

    @GetMapping("/catalog")
    fun publishedPresets(
        @RequestParam(required = false) query: String? = null,
        @RequestParam(required = false) category: String? = null,
        @RequestParam(defaultValue = "popular") sort: String = "popular",
        @RequestParam(defaultValue = "20") limit: Int = 20,
    ): PresetCatalogResponse =
        PresetCatalogResponse
            .from(registry.searchPublishedPresetsResult(query = query, category = category, sort = sort, limit = limit))

    @GetMapping("/catalog/recommended")
    fun recommendedPresets(
        @RequestParam(required = false) category: String? = null,
        @RequestParam(defaultValue = "10") limit: Int = 10,
    ): PresetRecommendationResponse =
        PresetRecommendationResponse
            .from(registry.recommendedPublishedPresetsResult(category = category, limit = limit))

    @GetMapping("/catalog/facets")
    fun catalogFacets(): PresetCatalogFacetsResponse = PresetCatalogFacetsResponse(registry.catalogFacets())

    @GetMapping("/web-readiness")
    fun webReadiness(): PresetWebReadinessResponse = PresetWebReadinessResponse.from(registry.webReadiness())

    @GetMapping("/moderation/summary")
    fun moderationSummary(): PresetModerationSummaryResponse = PresetModerationSummaryResponse(registry.moderationSummary())

    @GetMapping("/guilds/{guildId}/imports")
    fun importHistory(
        @PathVariable guildId: Long,
        @RequestParam(required = false) channelId: Long? = null,
    ): PresetImportHistoryResponse =
        PresetImportHistoryResponse
            .from(registry.importHistoryResult(targetGuildId = guildId, targetChannelId = channelId))

    @GetMapping("/reports")
    fun reports(): PresetReportsResponse = PresetReportsResponse(registry.listReports())

    @GetMapping("/reports/{status}")
    fun reportsByStatus(
        @PathVariable status: String,
    ): PresetReportsResponse = PresetReportsResponse(registry.listReports(status))

    @GetMapping("/catalog/slug/{slug}")
    fun publishedPresetDetailBySlug(
        @PathVariable slug: String,
    ): PublishedPresetDetailResponse = PublishedPresetDetailResponse(registry.publishedPresetDetailBySlug(slug))

    @GetMapping("/catalog/{publishedPresetId}")
    fun publishedPresetDetail(
        @PathVariable publishedPresetId: Long,
    ): PublishedPresetDetailResponse = PublishedPresetDetailResponse(registry.publishedPresetDetail(publishedPresetId))

    @PostMapping("/{guildId}")
    fun create(
        @PathVariable guildId: Long,
        @RequestBody request: CreatePresetRequest,
    ): PresetWriteResponse {
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
        return PresetWriteResponse.from(preset)
    }

    @PostMapping("/guilds/{guildId}/channels/{channelId}/save-from-channel")
    fun saveFromChannel(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: SaveChannelPresetRequest,
    ): PresetWriteResponse {
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
        return PresetWriteResponse.from(preset)
    }

    @PutMapping("/{presetId}")
    fun update(
        @PathVariable presetId: Long,
        @RequestBody request: UpdatePresetRequest,
    ): PresetWriteResponse {
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
        return PresetWriteResponse.from(preset)
    }

    @PostMapping("/{presetId}/publish")
    fun publish(
        @PathVariable presetId: Long,
        @RequestBody request: PublishPresetRequest,
    ): PublishPresetResponse {
        val published =
            registry.publishPreset(
                presetId = presetId,
                publisherUserId = request.actorUserId,
                title = request.title,
                description = request.description,
            )
        return PublishPresetResponse.from(published)
    }

    @PutMapping("/published/{publishedPresetId}")
    fun updatePublished(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: UpdatePublishedPresetRequest,
    ): UpdatePublishedPresetResponse {
        val published =
            registry.updatePublishedPreset(
                publishedPresetId = publishedPresetId,
                actorUserId = request.actorUserId,
                title = request.title,
                description = request.description,
                behavior = request.behavior,
            )
        return UpdatePublishedPresetResponse.from(published)
    }

    @PostMapping("/published/{publishedPresetId}/import-preview")
    fun importPreview(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: ImportPresetRequest,
    ): ImportPreviewResponse =
        ImportPreviewResponse(
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
    ): ImportPresetResponse {
        val imported =
            registry.importPreset(
                publishedPresetId = publishedPresetId,
                targetGuildId = request.targetGuildId,
                targetChannelId = request.targetChannelId,
                importedBy = request.actorUserId,
                confirmConflicts = request.confirmConflicts,
            )
        return ImportPresetResponse.from(imported)
    }

    @PostMapping("/published/{publishedPresetId}/like")
    fun like(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: LikePresetRequest,
    ): LikePresetResponse {
        val published = registry.likePreset(publishedPresetId, request.userId)
        return LikePresetResponse.from(published)
    }

    @DeleteMapping("/published/{publishedPresetId}/like")
    fun unlike(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: LikePresetRequest,
    ): LikePresetResponse {
        val published = registry.unlikePreset(publishedPresetId, request.userId)
        return LikePresetResponse.from(published)
    }

    @PostMapping("/published/{publishedPresetId}/report")
    fun report(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: ReportPresetRequest,
    ): ReportPresetResponse {
        val report =
            registry.reportPreset(
                publishedPresetId = publishedPresetId,
                reporterUserId = request.reporterUserId,
                reason = request.reason ?: request.details ?: request.reasonCode ?: "other",
                reasonCode = request.reasonCode,
                details = request.details,
            )
        return ReportPresetResponse.from(report)
    }

    @PostMapping("/reports/{reportId}/review")
    fun reviewReport(
        @PathVariable reportId: Long,
        @RequestBody request: ReviewPresetReportRequest,
    ): ReviewPresetReportResponse {
        val report = registry.reviewReport(reportId, request.decision, request.reviewerUserId)
        return ReviewPresetReportResponse.from(report)
    }

    @DeleteMapping("/{presetId}")
    fun delete(
        @PathVariable presetId: Long,
    ): PresetDeleteResponse {
        val preset = registry.deletePreset(presetId)
        return PresetDeleteResponse(deleted = true, status = preset.status)
    }

    @DeleteMapping("/published/{publishedPresetId}")
    fun deletePublished(
        @PathVariable publishedPresetId: Long,
    ): PublishedPresetDeleteResponse {
        val published = registry.deletePublishedPreset(publishedPresetId)
        return PublishedPresetDeleteResponse(deleted = true, status = published.status)
    }

    @PostMapping("/published/{publishedPresetId}/unlist")
    fun unlistPublished(
        @PathVariable publishedPresetId: Long,
    ): PublishedPresetUnlistResponse {
        val published = registry.unlistPublishedPreset(publishedPresetId)
        return PublishedPresetUnlistResponse(unlisted = true, status = published.status)
    }

    @PostMapping("/published/{publishedPresetId}/republish")
    fun republishPublished(
        @PathVariable publishedPresetId: Long,
    ): PublishedPresetRepublishResponse {
        val published = registry.republishPreset(publishedPresetId)
        return PublishedPresetRepublishResponse(republished = true, status = published.status)
    }
}
