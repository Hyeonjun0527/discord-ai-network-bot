package com.discordassistant.central.preset.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetReportEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetReportRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetRepository
import com.discordassistant.central.preset.domain.model.PresetModerationRules
import com.discordassistant.central.preset.domain.model.PresetReportStatus
import com.discordassistant.central.preset.domain.model.PresetStatus
import com.discordassistant.central.preset.domain.model.PublishedPresetStatus
import com.discordassistant.central.shared.ContentSafety.HIGH_RISK_SAFETY_LEVELS
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * read-only CQRS 분리: 프리셋 카탈로그/검색/조회/moderation-summary 표면을
 * [PresetRegistryService] 에서 추출한 query 서비스. 동작은 원본과 동일하며
 * [PresetRegistryService] 가 동일 시그니처로 위임한다.
 */
@Service
class PresetCatalogQueryService(
    private val presets: AiPresetRepository,
    private val revisions: PresetRevisionRepository,
    private val publishedPresets: PublishedPresetRepository,
    private val imports: PresetImportRepository,
    private val reports: PresetReportRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
    // 순수 매핑/포매팅/정렬/마스킹/필터 협력자(god-class 분해). 기본값으로 직접 구성해 수동 생성(테스트)
    // 호환을 유지하고, Spring 컨텍스트에서는 동일 @Component 빈이 주입된다. repo fetch 가 필요한
    // @Transactional(readOnly) 쿼리 메서드는 이 파사드의 TX 안에 그대로 잔존한다.
    private val catalogMapper: PresetCatalogMapper = PresetCatalogMapper(),
) {
    @Transactional(readOnly = true)
    fun listGuildPresets(guildId: Long): List<PresetSummary> {
        featureGate.requirePresetEnabled()
        return presets
            .findByGuildId(guildId)
            .filter { it.status != PresetStatus.REMOVED }
            .sortedWith(compareByDescending<AiPresetEntity> { it.updatedAt }.thenBy { it.id })
            .map { it.toSummary() }
    }

    @Transactional(readOnly = true)
    fun listPublishedPresets(): List<PublishedPresetSummary> = searchPublishedPresets()

    @Transactional(readOnly = true)
    fun searchPublishedPresets(
        query: String? = null,
        category: String? = null,
        sort: String = "popular",
        limit: Int = 20,
    ): List<PublishedPresetSummary> {
        featureGate.requirePresetEnabled()
        val normalizedQuery = query.normalizedSearchToken()
        val normalizedCategory = category.normalizedSearchToken()
        val cappedLimit = limit.coerceIn(1, 100)
        val summaries =
            publishedPresets
                .findByStatusOrderByLikeCountDescPublishedAtDesc(PublishedPresetStatus.PUBLISHED)
                .map { publishedSummary(it) }
                .filter { summary ->
                    normalizedQuery == null || summary.searchHaystack().contains(normalizedQuery)
                }.filter { summary ->
                    normalizedCategory == null || summary.category.orEmpty().lowercase() == normalizedCategory
                }
        return when (sort.trim().lowercase()) {
            "new", "latest", "recent" -> summaries.sortedByDescending { Instant.parse(it.publishedAt) }
            "imports", "import" ->
                summaries.sortedWith(
                    compareByDescending<PublishedPresetSummary> { it.importCount }
                        .thenByDescending { it.likeCount }
                        .thenByDescending { Instant.parse(it.publishedAt) },
                )
            "reports", "reported" ->
                summaries.sortedWith(
                    compareByDescending<PublishedPresetSummary> { it.reportCount }
                        .thenByDescending { it.likeCount }
                        .thenByDescending { Instant.parse(it.publishedAt) },
                )
            else ->
                summaries.sortedWith(
                    compareByDescending<PublishedPresetSummary> { it.likeCount }
                        .thenByDescending { it.importCount }
                        .thenByDescending { Instant.parse(it.publishedAt) },
                )
        }.take(cappedLimit)
    }

    /**
     * 컨트롤러 catalog echo 와 클램프 책임을 application 으로 이관한 결과형. 서비스가 실제로 쓰는
     * `effectiveLimit`(coerceIn(1,100))을 함께 돌려줘 컨트롤러는 위임만 한다(클램프 중복 제거).
     */
    @Transactional(readOnly = true)
    fun searchPublishedPresetsResult(
        query: String? = null,
        category: String? = null,
        sort: String = "popular",
        limit: Int = 20,
    ): PresetCatalogResult =
        PresetCatalogResult(
            presets = searchPublishedPresets(query = query, category = category, sort = sort, limit = limit),
            query = query,
            category = category,
            sort = sort,
            effectiveLimit = limit.coerceIn(1, 100),
        )

    @Transactional(readOnly = true)
    fun recommendedPublishedPresets(
        category: String? = null,
        limit: Int = 10,
    ): List<PresetRecommendation> {
        featureGate.requirePresetEnabled()
        val normalizedCategory = category.normalizedSearchToken()
        return publishedPresets
            .findByStatusOrderByLikeCountDescPublishedAtDesc(PublishedPresetStatus.PUBLISHED)
            .map { publishedSummary(it) }
            .filter { it.reportCount == 0 }
            .filter { normalizedCategory == null || it.category.orEmpty().lowercase() == normalizedCategory }
            .map { it.toRecommendation() }
            .sortedWith(
                compareByDescending<PresetRecommendation> { it.score }
                    .thenByDescending { it.preset.likeCount }
                    .thenByDescending { it.preset.importCount }
                    .thenByDescending { Instant.parse(it.preset.publishedAt) },
            ).take(limit.coerceIn(1, 50))
    }

    /** 추천 catalog echo + 클램프(coerceIn(1,50))를 application 으로 이관한 결과형. */
    @Transactional(readOnly = true)
    fun recommendedPublishedPresetsResult(
        category: String? = null,
        limit: Int = 10,
    ): PresetRecommendationResult =
        PresetRecommendationResult(
            recommendations = recommendedPublishedPresets(category = category, limit = limit),
            category = category,
            effectiveLimit = limit.coerceIn(1, 50),
        )

    @Transactional(readOnly = true)
    fun catalogFacets(): PresetCatalogFacets {
        featureGate.requirePresetEnabled()
        val summaries =
            publishedPresets
                .findByStatusOrderByLikeCountDescPublishedAtDesc(PublishedPresetStatus.PUBLISHED)
                .map { publishedSummary(it) }
        return PresetCatalogFacets(
            totalPublished = summaries.size,
            totalLikes = summaries.sumOf { it.likeCount },
            totalImports = summaries.sumOf { it.importCount },
            categories = summaries.facetBy { it.category ?: "uncategorized" },
            tags = summaries.facetValues { it.tags },
            safetyLevels = summaries.facetBy { it.safetyLevel ?: "unknown" },
            responseModes = summaries.facetBy { it.responseMode ?: "balanced" },
            qualityTiers = summaries.facetBy { it.minQualityTier ?: "standard" },
            topPresets =
                summaries
                    .filter { it.reportCount == 0 }
                    .sortedWith(
                        compareByDescending<PublishedPresetSummary> { it.likeCount }
                            .thenByDescending { it.importCount }
                            .thenByDescending { Instant.parse(it.publishedAt) },
                    ).take(5),
        )
    }

    @Transactional(readOnly = true)
    fun presetDetail(presetId: Long): PresetDetail {
        featureGate.requirePresetEnabled()
        val preset = presets.findById(presetId).orElseThrow { IllegalArgumentException("preset not found: $presetId") }
        requireActivePreset(preset)
        return PresetDetail(
            preset = preset.toSummary(),
            revisions = revisions.findByPresetIdOrderByRevisionDesc(preset.id).map { it.toSummary() },
        )
    }

    @Transactional(readOnly = true)
    fun importHistory(
        targetGuildId: Long,
        targetChannelId: Long? = null,
    ): List<PresetImportSummary> {
        featureGate.requirePresetEnabled()
        return imports
            .findByTargetGuildId(targetGuildId)
            .filter { targetChannelId == null || it.targetChannelId == targetChannelId }
            .sortedWith(compareByDescending<PresetImportEntity> { it.importedAt }.thenByDescending { it.id })
            .map { it.toSummary() }
    }

    /** 가져오기 이력 + guildId/channelId echo 를 한 결과형으로(컨트롤러 조립 제거). */
    @Transactional(readOnly = true)
    fun importHistoryResult(
        targetGuildId: Long,
        targetChannelId: Long? = null,
    ): PresetImportHistoryResult =
        PresetImportHistoryResult(
            guildId = targetGuildId,
            channelId = targetChannelId,
            imports = importHistory(targetGuildId = targetGuildId, targetChannelId = targetChannelId),
        )

    /**
     * 프리셋 웹 대시보드 준비 상태(capability 매트릭스·admin 토큰 헤더·다음 행동). 값/문구는 컨트롤러
     * 인라인이던 원본과 1바이트도 다르지 않다 — 어떤 기능이 admin 토큰을 요구하는지의 정책을 application 이 소유한다.
     */
    fun webReadiness(): PresetWebReadiness =
        PresetWebReadiness(
            status = "ready",
            capabilities =
                listOf(
                    PresetWebCapability("browse", "공개 프리셋 목록 확인", requiresAdminToken = false),
                    PresetWebCapability("detail", "프리셋 상세/공유 링크 확인", requiresAdminToken = false),
                    PresetWebCapability("recommend", "추천 프리셋과 빠른 탐색", requiresAdminToken = false),
                    PresetWebCapability("preview_import", "서버·채널 충돌 미리보기", requiresAdminToken = true),
                    PresetWebCapability("import", "현재 채널 AI로 가져오기", requiresAdminToken = true),
                    PresetWebCapability("like", "따봉 추천/추천 취소", requiresAdminToken = false),
                    PresetWebCapability("report", "부적절한 프리셋 신고", requiresAdminToken = false),
                ),
            adminTokenHeader = "X-Dashboard-Admin-Token",
            nextAction = "목록은 바로 볼 수 있고, 가져오기는 관리자 토큰을 입력한 뒤 미리보기부터 진행하세요.",
        )

    @Transactional(readOnly = true)
    fun listReports(status: String = "open"): List<PresetReportSummary> {
        featureGate.requirePresetEnabled()
        // 빈 값은 기존과 동일하게 open 으로 본다. 캐노니컬이 아닌 status 필터는 매칭 행이 없으므로
        // 빈 결과를 돌려준다(기존 String findByStatus("foobar")=빈 리스트 동작 보존).
        val normalized = status.trim().lowercase().ifBlank { PresetReportStatus.OPEN.wire }
        val target = PresetReportStatus.entries.firstOrNull { it.wire == normalized } ?: return emptyList()
        return reports
            .findByStatus(target)
            .sortedWith(compareByDescending<PresetReportEntity> { it.createdAt }.thenBy { it.id })
            .map { it.toSummary() }
    }

    @Transactional(readOnly = true)
    fun moderationSummary(): PresetModerationSummary {
        featureGate.requirePresetEnabled()
        val published = publishedPresets.findAll()
        val reportRows = reports.findAll()
        val reportStatusCounts = reportRows.groupingBy { it.status.wire }.eachCount()
        val openReportsByPreset = reportRows.filter { it.status == PresetReportStatus.OPEN }.groupBy { it.publishedPresetId }
        val queue =
            published
                .map { preset -> moderationItem(preset, openReportsByPreset[preset.id].orEmpty()) }
                .filter { it.riskCodes.isNotEmpty() }
                .sortedWith(
                    compareBy<PresetModerationQueueItem> { PresetModerationRules.severityRank(it.status, it.riskCodes) }
                        .thenByDescending { it.reportCount }
                        .thenByDescending { it.likeCount }
                        .thenBy { it.publishedPresetId },
                )
        val statusCounts = published.groupingBy { it.status.wire }.eachCount()
        return PresetModerationSummary(
            totalPublishedRows = published.size,
            activePublishedCount = statusCounts["published"] ?: 0,
            underReviewCount = statusCounts["under_review"] ?: 0,
            suspendedCount = statusCounts["suspended"] ?: 0,
            removedCount = statusCounts["removed"] ?: 0,
            openReportCount = reportStatusCounts["open"] ?: 0,
            reviewedReportCount = reportRows.count { it.status != PresetReportStatus.OPEN },
            statusCounts = statusCounts,
            reportStatusCounts = reportStatusCounts,
            queue = queue.take(50),
            nextActions = presetModerationNextActions(queue, reportStatusCounts),
        )
    }

    @Transactional(readOnly = true)
    fun publishedPresetDetail(publishedPresetId: Long): PublishedPresetDetail {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        requirePublishedPreset(published)
        val revision =
            revisions.findById(published.revisionId).orElseThrow {
                IllegalArgumentException("published revision not found: ${published.revisionId}")
            }
        val preset = presets.findById(published.presetId).orElse(null)
        return PublishedPresetDetail(
            published = published.toSummary(revision, preset),
            behavior = revision.toBehaviorSnapshot(),
        )
    }

    @Transactional(readOnly = true)
    fun publishedPresetDetailBySlug(slug: String): PublishedPresetDetail {
        featureGate.requirePresetEnabled()
        val normalizedSlug = slug.trim().lowercase().take(160)
        require(normalizedSlug.isNotBlank()) { "published preset slug is required" }
        val published =
            publishedPresets.findBySlug(normalizedSlug)
                ?: throw IllegalArgumentException("published preset not found: $normalizedSlug")
        requirePublishedPreset(published)
        val revision =
            revisions.findById(published.revisionId).orElseThrow {
                IllegalArgumentException("published revision not found: ${published.revisionId}")
            }
        val preset = presets.findById(published.presetId).orElse(null)
        return PublishedPresetDetail(
            published = published.toSummary(revision, preset),
            behavior = revision.toBehaviorSnapshot(),
        )
    }

    // --- 가드 (write 와 공유하므로 PresetRegistryService 에도 동일하게 유지) ---

    private fun requireActivePreset(preset: AiPresetEntity) {
        require(preset.status != PresetStatus.REMOVED) { "removed preset cannot be changed" }
    }

    private fun requirePublishedPreset(published: PublishedPresetEntity) {
        require(published.status == PublishedPresetStatus.PUBLISHED) {
            "published preset is not importable or likable: ${published.status.wire}"
        }
    }

    // --- read-only 매핑 헬퍼: 순수 변환은 PresetCatalogMapper(@Component)로 위임(동작 불변, 시그니처 보존) ---

    private fun AiPresetEntity.toSummary(): PresetSummary = with(catalogMapper) { toSummary() }

    private fun PresetRevisionEntity.toSummary(): PresetRevisionSummary = with(catalogMapper) { toSummary() }

    private fun PresetRevisionEntity.toBehaviorSnapshot(): PresetBehaviorSnapshot = with(catalogMapper) { toBehaviorSnapshot() }

    private fun PresetImportEntity.toSummary(): PresetImportSummary = with(catalogMapper) { toSummary() }

    private fun PresetReportEntity.toSummary(): PresetReportSummary = with(catalogMapper) { toSummary() }

    private fun moderationItem(
        published: PublishedPresetEntity,
        openReports: List<PresetReportEntity>,
    ): PresetModerationQueueItem {
        val summary = publishedSummary(published)
        val reportReasonCodes =
            openReports
                .groupingBy { it.reasonCode.ifBlank { "other" } }
                .eachCount()
                .toSortedMap()
        val riskCodes =
            buildList {
                if (summary.status == "under_review") add("under_review")
                if (summary.status == "suspended") add("suspended")
                if (summary.status == "removed") add("removed")
                if (summary.reportCount > 0) add("reported")
                if (summary.reportCount > 0 && summary.likeCount + summary.importCount >= 5) add("popular_reported")
                if (summary.safetyLevel.orEmpty().lowercase() in HIGH_RISK_SAFETY_LEVELS) add("high_safety_level")
            }.distinct()
        return PresetModerationQueueItem(
            publishedPresetId = summary.id,
            title = summary.title,
            status = summary.status,
            reportCount = summary.reportCount,
            likeCount = summary.likeCount,
            importCount = summary.importCount,
            safetyLevel = summary.safetyLevel,
            riskCodes = riskCodes,
            reportReasonCodes = reportReasonCodes,
            recommendedAction = PresetModerationRules.recommendedAction(summary.status, riskCodes),
        )
    }

    private fun presetModerationNextActions(
        queue: List<PresetModerationQueueItem>,
        reportStatusCounts: Map<String, Int>,
    ): List<String> = catalogMapper.presetModerationNextActions(queue, reportStatusCounts)

    // repo fetch 가 필요하므로 파사드 TX 안에 잔존(순수 매핑만 PresetCatalogMapper 로 위임).
    private fun publishedSummary(published: PublishedPresetEntity): PublishedPresetSummary {
        val revision = revisions.findById(published.revisionId).orElse(null)
        val preset = presets.findById(published.presetId).orElse(null)
        return published.toSummary(revision, preset)
    }

    // --- 순수 매핑/포매팅/정렬/필터/마스킹은 PresetCatalogMapper(@Component)로 위임(동작 불변, 시그니처 보존) ---

    private fun PublishedPresetEntity.toSummary(
        revision: PresetRevisionEntity?,
        preset: AiPresetEntity?,
    ): PublishedPresetSummary = with(catalogMapper) { toSummary(revision, preset) }

    private fun PublishedPresetSummary.searchHaystack(): String = with(catalogMapper) { searchHaystack() }

    private fun String?.normalizedSearchToken(): String? = with(catalogMapper) { normalizedSearchToken() }

    private fun List<PublishedPresetSummary>.facetBy(selector: (PublishedPresetSummary) -> String): List<PresetCatalogFacet> =
        with(catalogMapper) { facetBy(selector) }

    private fun List<PublishedPresetSummary>.facetValues(selector: (PublishedPresetSummary) -> List<String>): List<PresetCatalogFacet> =
        with(catalogMapper) { facetValues(selector) }

    private fun PublishedPresetSummary.toRecommendation(): PresetRecommendation = with(catalogMapper) { toRecommendation() }
}
