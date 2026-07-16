package com.discordassistant.central.preset.adapter.inbound.web.dto

import com.discordassistant.central.preset.application.PresetCatalogFacets
import com.discordassistant.central.preset.application.PresetCatalogResult
import com.discordassistant.central.preset.application.PresetDetail
import com.discordassistant.central.preset.application.PresetImportHistoryResult
import com.discordassistant.central.preset.application.PresetImportPreview
import com.discordassistant.central.preset.application.PresetImportResult
import com.discordassistant.central.preset.application.PresetImportSummary
import com.discordassistant.central.preset.application.PresetModerationSummary
import com.discordassistant.central.preset.application.PresetRecommendation
import com.discordassistant.central.preset.application.PresetRecommendationResult
import com.discordassistant.central.preset.application.PresetReportSummary
import com.discordassistant.central.preset.application.PresetReportWriteResult
import com.discordassistant.central.preset.application.PresetSummary
import com.discordassistant.central.preset.application.PresetWebCapability
import com.discordassistant.central.preset.application.PresetWebReadiness
import com.discordassistant.central.preset.application.PresetWriteResult
import com.discordassistant.central.preset.application.PublishedPresetDetail
import com.discordassistant.central.preset.application.PublishedPresetSummary
import com.discordassistant.central.preset.application.PublishedPresetWriteResult

// 응답 DTO (인바운드 웹 어댑터). 기존 JSON field 이름은 유지하되 Map 기반 응답 조립을 제거한다.

data class PresetListResponse(
    val presets: List<PresetSummary>,
)

data class PresetDetailResponse(
    val preset: PresetDetail,
)

data class PublishedPresetDetailResponse(
    val preset: PublishedPresetDetail,
)

data class PresetCatalogFacetsResponse(
    val facets: PresetCatalogFacets,
)

data class PresetModerationSummaryResponse(
    val summary: PresetModerationSummary,
)

data class PresetReportsResponse(
    val reports: List<PresetReportSummary>,
)

data class ImportPreviewResponse(
    val preview: PresetImportPreview,
)

data class PresetDeleteResponse(
    val deleted: Boolean,
    val status: String,
)

data class PublishedPresetDeleteResponse(
    val deleted: Boolean,
    val status: String,
)

data class PublishedPresetUnlistResponse(
    val unlisted: Boolean,
    val status: String,
)

data class PublishedPresetRepublishResponse(
    val republished: Boolean,
    val status: String,
)

/** preset write 응답: create/saveFromChannel/update 가 공유하던 top-level 필드. */
data class PresetWriteResponse(
    val id: Long,
    val currentRevisionId: Long?,
    val status: String,
) {
    companion object {
        fun from(result: PresetWriteResult): PresetWriteResponse =
            PresetWriteResponse(id = result.id, currentRevisionId = result.currentRevisionId, status = result.status)
    }
}

/** publish 응답. */
data class PublishPresetResponse(
    val id: Long,
    val status: String,
    val slug: String,
    val title: String,
) {
    companion object {
        fun from(result: PublishedPresetWriteResult): PublishPresetResponse =
            PublishPresetResponse(id = result.id, status = result.status, slug = result.slug, title = result.title)
    }
}

/** updatePublished 응답. */
data class UpdatePublishedPresetResponse(
    val id: Long,
    val revisionId: Long,
    val status: String,
    val slug: String,
    val title: String,
) {
    companion object {
        fun from(result: PublishedPresetWriteResult): UpdatePublishedPresetResponse =
            UpdatePublishedPresetResponse(
                id = result.id,
                revisionId = result.revisionId,
                status = result.status,
                slug = result.slug,
                title = result.title,
            )
    }
}

/** import 응답. */
data class ImportPresetResponse(
    val id: Long,
    val importedPresetId: Long?,
    val sourceRevisionId: Long?,
    val createdChannelAiId: Long?,
    val createdBehaviorVersionId: Long?,
    val status: String,
) {
    companion object {
        fun from(result: PresetImportResult): ImportPresetResponse =
            ImportPresetResponse(
                id = result.id,
                importedPresetId = result.importedPresetId,
                sourceRevisionId = result.sourceRevisionId,
                createdChannelAiId = result.createdChannelAiId,
                createdBehaviorVersionId = result.createdBehaviorVersionId,
                status = result.status,
            )
    }
}

/** like/unlike 응답. */
data class LikePresetResponse(
    val id: Long,
    val likeCount: Int,
) {
    companion object {
        fun from(result: PublishedPresetWriteResult): LikePresetResponse = LikePresetResponse(id = result.id, likeCount = result.likeCount)
    }
}

/** report 응답. */
data class ReportPresetResponse(
    val id: Long,
    val status: String,
    val reasonCode: String,
) {
    companion object {
        fun from(result: PresetReportWriteResult): ReportPresetResponse =
            ReportPresetResponse(id = result.id, status = result.status, reasonCode = result.reasonCode)
    }
}

/** reviewReport 응답. */
data class ReviewPresetReportResponse(
    val id: Long,
    val status: String,
    val reviewedBy: Long?,
    val reviewedAt: String?,
) {
    companion object {
        fun from(result: PresetReportWriteResult): ReviewPresetReportResponse =
            ReviewPresetReportResponse(
                id = result.id,
                status = result.status,
                reviewedBy = result.reviewedBy,
                reviewedAt = result.reviewedAt,
            )
    }
}

/** catalog 검색 응답(presets + query/category/sort/limit echo, limit=effectiveLimit 클램프). */
data class PresetCatalogResponse(
    val presets: List<PublishedPresetSummary>,
    val query: String?,
    val category: String?,
    val sort: String,
    val limit: Int,
) {
    companion object {
        fun from(result: PresetCatalogResult): PresetCatalogResponse =
            PresetCatalogResponse(
                presets = result.presets,
                query = result.query,
                category = result.category,
                sort = result.sort,
                limit = result.effectiveLimit,
            )
    }
}

/** recommended 응답(recommendations + category/limit echo, limit=effectiveLimit 클램프). */
data class PresetRecommendationResponse(
    val recommendations: List<PresetRecommendation>,
    val category: String?,
    val limit: Int,
) {
    companion object {
        fun from(result: PresetRecommendationResult): PresetRecommendationResponse =
            PresetRecommendationResponse(
                recommendations = result.recommendations,
                category = result.category,
                limit = result.effectiveLimit,
            )
    }
}

/** importHistory 응답(guildId/channelId echo + imports). */
data class PresetImportHistoryResponse(
    val guildId: Long,
    val channelId: Long?,
    val imports: List<PresetImportSummary>,
) {
    companion object {
        fun from(result: PresetImportHistoryResult): PresetImportHistoryResponse =
            PresetImportHistoryResponse(guildId = result.guildId, channelId = result.channelId, imports = result.imports)
    }
}

/** web-readiness 응답(capability 매트릭스·admin 토큰 헤더·다음 행동). */
data class PresetWebReadinessResponse(
    val status: String,
    val capabilities: List<PresetWebCapability>,
    val adminTokenHeader: String,
    val nextAction: String,
) {
    companion object {
        fun from(result: PresetWebReadiness): PresetWebReadinessResponse =
            PresetWebReadinessResponse(
                status = result.status,
                capabilities = result.capabilities,
                adminTokenHeader = result.adminTokenHeader,
                nextAction = result.nextAction,
            )
    }
}
