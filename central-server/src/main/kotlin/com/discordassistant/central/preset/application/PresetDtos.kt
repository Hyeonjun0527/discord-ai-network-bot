package com.discordassistant.central.preset.application

// 응답/결과 DTO (행위 분해: 서비스 본체에서 분리, 같은 패키지·시그니처 불변).

/**
 * write 결과 DTO. 컨트롤러/CommandService 가 JPA 엔티티 대신 이 값을 읽어 응답을 만든다
 * (web↛entity 누수 제거, 감사 2026-06-03 C). 필드는 컨트롤러가 기존에 엔티티에서 직접 읽던
 * 원시값(raw)을 그대로 담아 HTTP 응답 JSON 을 불변으로 유지한다(공개 마스킹 미적용).
 */
data class PresetWriteResult(
    val id: Long,
    val currentRevisionId: Long?,
    val status: String,
)

data class PublishedPresetWriteResult(
    val id: Long,
    val revisionId: Long,
    val status: String,
    val slug: String,
    val title: String,
    val description: String?,
    val likeCount: Int,
)

data class PresetImportResult(
    val id: Long,
    val importedPresetId: Long?,
    val sourceRevisionId: Long?,
    val createdChannelAiId: Long?,
    val createdBehaviorVersionId: Long?,
    val status: String,
)

data class PresetReportWriteResult(
    val id: Long,
    val status: String,
    val reasonCode: String,
    val reviewedBy: Long?,
    val reviewedAt: String?,
)

data class PresetBehaviorInput(
    val purpose: String = "general_assistant",
    val tone: String = "friendly",
    val answerLength: String = "balanced",
    val constitution: String? = null,
    val safetyLevel: String = "standard",
    val responseMode: String = "balanced",
    val preferredModel: String? = null,
    val minQualityTier: String = "standard",
    val maxCandidates: Int = 1,
    val providerTagFilter: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val costGuard: String = "provider_safe",
    val knowledgeSlotNames: List<String> = emptyList(),
    val knowledgeGuide: String? = null,
    val exampleQuestions: List<String> = emptyList(),
    val changeSummary: String? = null,
)

data class PresetSummary(
    val id: Long,
    val guildId: Long,
    val ownerUserId: Long?,
    val name: String,
    val summary: String?,
    val category: String,
    val visibility: String,
    val status: String,
    val currentRevisionId: Long?,
    val updatedAt: String,
)

data class PresetRevisionSummary(
    val id: Long,
    val revision: Int,
    val name: String,
    val purpose: String,
    val tone: String,
    val answerLength: String,
    val safetyLevel: String,
    val responseMode: String,
    val preferredModel: String?,
    val minQualityTier: String,
    val maxCandidates: Int,
    val providerTagFilter: List<String>,
    val tags: List<String>,
    val costGuard: String,
    val knowledgeSlotNames: List<String>,
    val knowledgeGuide: String?,
    val exampleQuestions: List<String>,
    val changeSummary: String?,
    val createdAt: String,
)

data class PresetDetail(
    val preset: PresetSummary,
    val revisions: List<PresetRevisionSummary>,
)

data class PublishedPresetSummary(
    val id: Long,
    val presetId: Long,
    val revisionId: Long,
    val publisherGuildId: Long?,
    val publisherUserId: Long?,
    val publisherLabel: String,
    val slug: String,
    val title: String,
    val description: String?,
    val status: String,
    val category: String?,
    val purpose: String?,
    val tone: String?,
    val safetyLevel: String?,
    val responseMode: String?,
    val preferredModel: String?,
    val minQualityTier: String?,
    val tags: List<String>,
    val likeCount: Int,
    val importCount: Int,
    val reportCount: Int,
    val publishedAt: String,
)

data class PresetRecommendation(
    val preset: PublishedPresetSummary,
    val score: Int,
    val reasons: List<String>,
)

data class PresetCatalogFacet(
    val value: String,
    val count: Int,
)

data class PresetCatalogFacets(
    val totalPublished: Int,
    val totalLikes: Int,
    val totalImports: Int,
    val categories: List<PresetCatalogFacet>,
    val tags: List<PresetCatalogFacet>,
    val safetyLevels: List<PresetCatalogFacet>,
    val responseModes: List<PresetCatalogFacet>,
    val qualityTiers: List<PresetCatalogFacet>,
    val topPresets: List<PublishedPresetSummary>,
)

data class PresetModerationSummary(
    val totalPublishedRows: Int,
    val activePublishedCount: Int,
    val underReviewCount: Int,
    val suspendedCount: Int,
    val removedCount: Int,
    val openReportCount: Int,
    val reviewedReportCount: Int,
    val statusCounts: Map<String, Int>,
    val reportStatusCounts: Map<String, Int>,
    val queue: List<PresetModerationQueueItem>,
    val nextActions: List<String>,
)

data class PresetModerationQueueItem(
    val publishedPresetId: Long,
    val title: String,
    val status: String,
    val reportCount: Int,
    val likeCount: Int,
    val importCount: Int,
    val safetyLevel: String?,
    val riskCodes: List<String>,
    val reportReasonCodes: Map<String, Int>,
    val recommendedAction: String,
)

data class PresetBehaviorSnapshot(
    val purpose: String,
    val tone: String,
    val answerLength: String,
    val constitution: String?,
    val safetyLevel: String,
    val responseMode: String,
    val preferredModel: String?,
    val minQualityTier: String,
    val maxCandidates: Int,
    val providerTagFilter: List<String>,
    val tags: List<String>,
    val costGuard: String,
    val knowledgeSlotNames: List<String>,
    val knowledgeGuide: String?,
    val exampleQuestions: List<String>,
)

data class PresetImportSummary(
    val id: Long,
    val publishedPresetId: Long,
    val sourceRevisionId: Long?,
    val targetGuildId: Long,
    val targetChannelId: Long?,
    val importedBy: Long?,
    val importedPresetId: Long?,
    val createdChannelAiId: Long?,
    val createdBehaviorVersionId: Long?,
    val status: String,
    val importedAt: String,
    val detachedCopy: Boolean,
)

data class PresetReportSummary(
    val id: Long,
    val publishedPresetId: Long,
    val reporterUserId: Long?,
    val reason: String,
    val reasonCode: String,
    val details: String?,
    val status: String,
    val createdAt: String,
    val reviewedBy: Long?,
    val reviewedAt: String?,
)

data class PublishedPresetDetail(
    val published: PublishedPresetSummary,
    val behavior: PresetBehaviorSnapshot,
)

/**
 * 카탈로그 검색 결과. `effectiveLimit` 은 서비스가 실제로 적용한 클램프 값(coerceIn(1,100))이라
 * 컨트롤러 echo 와 서비스 내부 limit 이 항상 일치한다(컨트롤러 클램프 중복 제거).
 */
data class PresetCatalogResult(
    val presets: List<PublishedPresetSummary>,
    val query: String?,
    val category: String?,
    val sort: String,
    val effectiveLimit: Int,
)

/** 추천 결과. `effectiveLimit` 은 서비스가 실제로 적용한 클램프 값(coerceIn(1,50)). */
data class PresetRecommendationResult(
    val recommendations: List<PresetRecommendation>,
    val category: String?,
    val effectiveLimit: Int,
)

/** 가져오기 이력 결과. guildId/channelId echo 를 서비스가 그대로 담아 돌려준다. */
data class PresetImportHistoryResult(
    val guildId: Long,
    val channelId: Long?,
    val imports: List<PresetImportSummary>,
)

/** 프리셋 웹 대시보드 기능 게이트 항목(어떤 기능이 admin 토큰을 요구하는지). */
data class PresetWebCapability(
    val key: String,
    val label: String,
    val requiresAdminToken: Boolean,
)

/** web-readiness 응답. capability 매트릭스·admin 토큰 헤더·다음 행동 안내를 application 이 소유한다. */
data class PresetWebReadiness(
    val status: String,
    val capabilities: List<PresetWebCapability>,
    val adminTokenHeader: String,
    val nextAction: String,
)

data class PresetImportConflict(
    val code: String,
    val severity: String,
    val message: String,
)

data class PresetImportPreview(
    val publishedPresetId: Long,
    val revisionId: Long,
    val targetGuildId: Long,
    val targetChannelId: Long?,
    val action: String,
    val conflicts: List<PresetImportConflict>,
    val willImportPresetCopy: Boolean,
    val willApplyToChannel: Boolean,
    val willOverwriteChannelAi: Boolean,
    val willOverwriteRoutingPolicy: Boolean,
    val willCreateApprovalProposal: Boolean,
    val title: String,
    val description: String?,
    val purpose: String,
    val tone: String,
    val answerLength: String,
    val safetyLevel: String,
    val responseMode: String,
    val preferredModel: String?,
    val minQualityTier: String,
    val maxCandidates: Int,
    val providerTagFilter: List<String>,
    val tags: List<String>,
    val costGuard: String,
    val knowledgeSlotNames: List<String>,
    val knowledgeGuide: String?,
    val exampleQuestions: List<String>,
)
