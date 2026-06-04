package com.discordassistant.central.dashboard

import com.discordassistant.central.network.PresetBehaviorInput
import com.discordassistant.central.network.PresetRegistryService
import com.discordassistant.central.network.PresetWriteResult
import com.discordassistant.central.network.PublishedPresetWriteResult
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
        mapOf(
            "presets" to registry.searchPublishedPresets(query = query, category = category, sort = sort, limit = limit),
            "query" to query,
            "category" to category,
            "sort" to sort,
            "limit" to limit.coerceIn(1, 100),
        )

    @GetMapping("/catalog/recommended")
    fun recommendedPresets(
        @RequestParam(required = false) category: String? = null,
        @RequestParam(defaultValue = "10") limit: Int = 10,
    ): Map<String, Any?> =
        mapOf(
            "recommendations" to registry.recommendedPublishedPresets(category = category, limit = limit),
            "category" to category,
            "limit" to limit.coerceIn(1, 50),
        )

    @GetMapping("/catalog/facets")
    fun catalogFacets(): Map<String, Any?> = mapOf("facets" to registry.catalogFacets())

    @GetMapping("/web-readiness")
    fun webReadiness(): Map<String, Any?> =
        mapOf(
            "status" to "ready",
            "capabilities" to
                listOf(
                    PresetWebCapability("browse", "공개 프리셋 목록 확인", requiresAdminToken = false),
                    PresetWebCapability("detail", "프리셋 상세/공유 링크 확인", requiresAdminToken = false),
                    PresetWebCapability("recommend", "추천 프리셋과 빠른 탐색", requiresAdminToken = false),
                    PresetWebCapability("preview_import", "서버·채널 충돌 미리보기", requiresAdminToken = true),
                    PresetWebCapability("import", "현재 채널 AI로 가져오기", requiresAdminToken = true),
                    PresetWebCapability("like", "따봉 추천/추천 취소", requiresAdminToken = false),
                    PresetWebCapability("report", "부적절한 프리셋 신고", requiresAdminToken = false),
                ),
            "adminTokenHeader" to "X-Dashboard-Admin-Token",
            "nextAction" to "목록은 바로 볼 수 있고, 가져오기는 관리자 토큰을 입력한 뒤 미리보기부터 진행하세요.",
        )

    @GetMapping("/moderation/summary")
    fun moderationSummary(): Map<String, Any?> = mapOf("summary" to registry.moderationSummary())

    @GetMapping("/guilds/{guildId}/imports")
    fun importHistory(
        @PathVariable guildId: Long,
        @RequestParam(required = false) channelId: Long? = null,
    ): Map<String, Any?> =
        mapOf(
            "guildId" to guildId,
            "channelId" to channelId,
            "imports" to registry.importHistory(targetGuildId = guildId, targetChannelId = channelId),
        )

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
        return presetWriteResult(preset)
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
        return presetWriteResult(preset)
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
        return presetWriteResult(preset)
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
        return mapOf("id" to published.id, "status" to published.status, "slug" to published.slug, "title" to published.title)
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
        return mapOf(
            "id" to published.id,
            "revisionId" to published.revisionId,
            "status" to published.status,
            "slug" to published.slug,
            "title" to published.title,
        )
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
        return mapOf(
            "id" to imported.id,
            "importedPresetId" to imported.importedPresetId,
            "sourceRevisionId" to imported.sourceRevisionId,
            "createdChannelAiId" to imported.createdChannelAiId,
            "createdBehaviorVersionId" to imported.createdBehaviorVersionId,
            "status" to imported.status,
        )
    }

    @PostMapping("/published/{publishedPresetId}/like")
    fun like(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: LikePresetRequest,
    ): Map<String, Any?> {
        val published = registry.likePreset(publishedPresetId, request.userId)
        return likeWriteResult(published)
    }

    @DeleteMapping("/published/{publishedPresetId}/like")
    fun unlike(
        @PathVariable publishedPresetId: Long,
        @RequestBody request: LikePresetRequest,
    ): Map<String, Any?> {
        val published = registry.unlikePreset(publishedPresetId, request.userId)
        return likeWriteResult(published)
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
        return mapOf("id" to report.id, "status" to report.status, "reasonCode" to report.reasonCode)
    }

    @PostMapping("/reports/{reportId}/review")
    fun reviewReport(
        @PathVariable reportId: Long,
        @RequestBody request: ReviewPresetReportRequest,
    ): Map<String, Any?> {
        val report = registry.reviewReport(reportId, request.decision, request.reviewerUserId)
        return mapOf(
            "id" to report.id,
            "status" to report.status,
            "reviewedBy" to report.reviewedBy,
            "reviewedAt" to report.reviewedAt,
        )
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

data class CreatePresetRequest(
    val actorUserId: Long? = null,
    val name: String,
    val summary: String? = null,
    val category: String? = null,
    val visibility: String? = null,
    val behavior: PresetBehaviorInput? = null,
)

data class SaveChannelPresetRequest(
    val actorUserId: Long? = null,
    val name: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val visibility: String? = null,
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

data class UpdatePublishedPresetRequest(
    val actorUserId: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val behavior: PresetBehaviorInput? = null,
)

data class PresetWebCapability(
    val key: String,
    val label: String,
    val requiresAdminToken: Boolean,
)

data class ImportPresetRequest(
    val targetGuildId: Long,
    val targetChannelId: Long? = null,
    val actorUserId: Long? = null,
    val confirmConflicts: Boolean = false,
)

data class LikePresetRequest(
    val userId: Long,
)

data class ReportPresetRequest(
    val reporterUserId: Long? = null,
    val reason: String? = null,
    val reasonCode: String? = null,
    val details: String? = null,
)

data class ReviewPresetReportRequest(
    val decision: String,
    val reviewerUserId: Long? = null,
)

// 동일하게 반복되던 응답 직렬화를 한곳으로(중복 제거). 응답 키/값은 기존과 동일.
private fun presetWriteResult(preset: PresetWriteResult): Map<String, Any?> =
    mapOf("id" to preset.id, "currentRevisionId" to preset.currentRevisionId, "status" to preset.status)

private fun likeWriteResult(published: PublishedPresetWriteResult): Map<String, Any?> =
    mapOf("id" to published.id, "likeCount" to published.likeCount)
