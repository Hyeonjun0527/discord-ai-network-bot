package com.discordassistant.central.preset.adapter.inbound.web.dto

import com.discordassistant.central.preset.application.PresetCatalogResult
import com.discordassistant.central.preset.application.PresetImportHistoryResult
import com.discordassistant.central.preset.application.PresetImportResult
import com.discordassistant.central.preset.application.PresetRecommendationResult
import com.discordassistant.central.preset.application.PresetReportWriteResult
import com.discordassistant.central.preset.application.PresetWebReadiness
import com.discordassistant.central.preset.application.PresetWriteResult
import com.discordassistant.central.preset.application.PublishedPresetWriteResult

// 응답 DTO (인바운드 웹 어댑터). 조립 책임만 컨트롤러 인라인 mapOf 에서 흡수했다.
// 각 toMap() 은 원본 mapOf 의 키 이름·값·순서·null·조건부키를 1바이트도 바꾸지 않고 그대로 재현한다
// (OpenApiContractTest·클라이언트 계약). 입력은 application 의 *Result DTO 만 참조한다(엔티티/리포지토리 의존 금지).

/** preset write 응답: create/saveFromChannel/update 가 공유하던 직렬화(기존 top-level presetWriteResult). */
data class PresetWriteResponse(
    val id: Long,
    val currentRevisionId: Long?,
    val status: String,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "currentRevisionId" to currentRevisionId, "status" to status)

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
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "status" to status, "slug" to slug, "title" to title)

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
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "revisionId" to revisionId,
            "status" to status,
            "slug" to slug,
            "title" to title,
        )

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
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "importedPresetId" to importedPresetId,
            "sourceRevisionId" to sourceRevisionId,
            "createdChannelAiId" to createdChannelAiId,
            "createdBehaviorVersionId" to createdBehaviorVersionId,
            "status" to status,
        )

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

/** like/unlike 응답(기존 top-level likeWriteResult). */
data class LikePresetResponse(
    val id: Long,
    val likeCount: Int,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "likeCount" to likeCount)

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
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "status" to status, "reasonCode" to reasonCode)

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
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "status" to status,
            "reviewedBy" to reviewedBy,
            "reviewedAt" to reviewedAt,
        )

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
    val result: PresetCatalogResult,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "presets" to result.presets,
            "query" to result.query,
            "category" to result.category,
            "sort" to result.sort,
            "limit" to result.effectiveLimit,
        )

    companion object {
        fun from(result: PresetCatalogResult): PresetCatalogResponse = PresetCatalogResponse(result)
    }
}

/** recommended 응답(recommendations + category/limit echo, limit=effectiveLimit 클램프). */
data class PresetRecommendationResponse(
    val result: PresetRecommendationResult,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "recommendations" to result.recommendations,
            "category" to result.category,
            "limit" to result.effectiveLimit,
        )

    companion object {
        fun from(result: PresetRecommendationResult): PresetRecommendationResponse = PresetRecommendationResponse(result)
    }
}

/** importHistory 응답(guildId/channelId echo + imports). */
data class PresetImportHistoryResponse(
    val result: PresetImportHistoryResult,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "guildId" to result.guildId,
            "channelId" to result.channelId,
            "imports" to result.imports,
        )

    companion object {
        fun from(result: PresetImportHistoryResult): PresetImportHistoryResponse = PresetImportHistoryResponse(result)
    }
}

/** web-readiness 응답(capability 매트릭스·admin 토큰 헤더·다음 행동). */
data class PresetWebReadinessResponse(
    val result: PresetWebReadiness,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "status" to result.status,
            "capabilities" to result.capabilities,
            "adminTokenHeader" to result.adminTokenHeader,
            "nextAction" to result.nextAction,
        )

    companion object {
        fun from(result: PresetWebReadiness): PresetWebReadinessResponse = PresetWebReadinessResponse(result)
    }
}
