package com.discordassistant.central.preset.application

import com.discordassistant.central.domain.ContentSafety.HIGH_RISK_SAFETY_LEVELS
import com.discordassistant.central.knowledge.application.KnowledgeSafety
import com.discordassistant.central.network.AiNetworkFeatureGate
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

    // --- read-only 매핑 헬퍼 (write 가 쓰지 않으므로 이동) ---

    private fun AiPresetEntity.toSummary(): PresetSummary =
        PresetSummary(
            id = id,
            guildId = guildId,
            ownerUserId = ownerUserId,
            name = name,
            summary = summary,
            category = category,
            visibility = visibility,
            status = status.wire,
            currentRevisionId = currentRevisionId,
            updatedAt = updatedAt.toString(),
        )

    private fun PresetRevisionEntity.toSummary(): PresetRevisionSummary =
        PresetRevisionSummary(
            id = id,
            revision = revision,
            name = name,
            purpose = purpose,
            tone = tone,
            answerLength = answerLength,
            safetyLevel = safetyLevel,
            responseMode = responseMode,
            preferredModel = preferredModel,
            minQualityTier = minQualityTier,
            maxCandidates = maxCandidates,
            providerTagFilter = splitCsv(providerTagFilter),
            tags = splitCsv(tags),
            costGuard = costGuard,
            knowledgeSlotNames = splitCsv(knowledgeSlotNames),
            knowledgeGuide = knowledgeGuide,
            exampleQuestions = splitLines(exampleQuestions),
            changeSummary = changeSummary,
            createdAt = createdAt.toString(),
        )

    private fun PresetRevisionEntity.toBehaviorSnapshot(): PresetBehaviorSnapshot =
        PresetBehaviorSnapshot(
            purpose = purpose.publicRequired(maxLength = 1000, fallback = REDACTED_PUBLIC_TEXT),
            tone = tone.publicRequired(maxLength = 160, fallback = "friendly"),
            answerLength = answerLength.publicRequired(maxLength = 80, fallback = "balanced"),
            constitution = constitution.publicOptional(maxLength = 3000),
            safetyLevel = safetyLevel.publicRequired(maxLength = 80, fallback = "standard"),
            responseMode = responseMode.publicRequired(maxLength = 80, fallback = "balanced"),
            preferredModel = preferredModel.publicOptional(maxLength = 160),
            minQualityTier = minQualityTier.publicRequired(maxLength = 80, fallback = "standard"),
            maxCandidates = maxCandidates,
            providerTagFilter = splitCsv(providerTagFilter).filterNot { it.hasSensitiveMaterial() },
            tags = splitCsv(tags).filterNot { it.hasSensitiveMaterial() },
            costGuard = costGuard.publicRequired(maxLength = 80, fallback = "provider_safe"),
            knowledgeSlotNames = splitCsv(knowledgeSlotNames).filterNot { it.hasSensitiveMaterial() },
            knowledgeGuide = knowledgeGuide.publicOptional(maxLength = 1000),
            exampleQuestions = splitLines(exampleQuestions).filterNot { it.hasSensitiveMaterial() },
        )

    private fun PresetImportEntity.toSummary(): PresetImportSummary =
        PresetImportSummary(
            id = id,
            publishedPresetId = publishedPresetId,
            sourceRevisionId = sourceRevisionId,
            targetGuildId = targetGuildId,
            targetChannelId = targetChannelId,
            importedBy = importedBy,
            importedPresetId = importedPresetId,
            createdChannelAiId = createdChannelAiId,
            createdBehaviorVersionId = createdBehaviorVersionId,
            status = status.wire,
            importedAt = importedAt.toString(),
            detachedCopy = importedPresetId != null,
        )

    private fun PresetReportEntity.toSummary(): PresetReportSummary =
        PresetReportSummary(
            id = id,
            publishedPresetId = publishedPresetId,
            reporterUserId = reporterUserId,
            reason = reason,
            reasonCode = reasonCode,
            details = details,
            status = status.wire,
            createdAt = createdAt.toString(),
            reviewedBy = reviewedBy,
            reviewedAt = reviewedAt?.toString(),
        )

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
    ): List<String> =
        buildList {
            if ((reportStatusCounts["open"] ?: 0) > 0) add("open 신고를 검토해 dismiss/suspend/remove 처리하세요.")
            if (queue.any { "popular_reported" in it.riskCodes }) add("추천/인기 프리셋 중 신고된 항목을 먼저 검토하세요.")
            if (queue.any { "high_safety_level" in it.riskCodes }) add("high/restricted safety 프리셋은 승인 없이 자동 추천하지 마세요.")
            if (queue.any { it.status == "suspended" }) add("suspended 프리셋은 수정 요청 또는 제거로 상태를 확정하세요.")
            if (isEmpty()) add("프리셋 검수 큐가 비어 있습니다.")
        }.distinct()

    private fun publishedSummary(published: PublishedPresetEntity): PublishedPresetSummary {
        val revision = revisions.findById(published.revisionId).orElse(null)
        val preset = presets.findById(published.presetId).orElse(null)
        return published.toSummary(revision, preset)
    }

    private fun PublishedPresetEntity.toSummary(
        revision: PresetRevisionEntity?,
        preset: AiPresetEntity?,
    ): PublishedPresetSummary =
        PublishedPresetSummary(
            id = id,
            presetId = presetId,
            revisionId = revisionId,
            publisherGuildId = null,
            publisherUserId = null,
            publisherLabel = "공개 프리셋 작성자",
            slug = slug.publicSlug(id),
            title = title.publicRequired(maxLength = 120, fallback = REDACTED_PUBLIC_TITLE),
            description = description.publicOptional(maxLength = 500),
            status = status.wire,
            category = (preset?.category).publicOptional(maxLength = 80),
            purpose = (revision?.purpose).publicOptional(maxLength = 1000),
            tone = (revision?.tone).publicOptional(maxLength = 160),
            safetyLevel = (revision?.safetyLevel).publicOptional(maxLength = 80),
            responseMode = (revision?.responseMode).publicOptional(maxLength = 80),
            preferredModel = (revision?.preferredModel).publicOptional(maxLength = 160),
            minQualityTier = (revision?.minQualityTier).publicOptional(maxLength = 80),
            tags = splitCsv(revision?.tags).filterNot { it.hasSensitiveMaterial() },
            likeCount = likeCount,
            importCount = importCount,
            reportCount = reportCount,
            publishedAt = publishedAt.toString(),
        )

    private fun PublishedPresetSummary.searchHaystack(): String =
        listOf(
            slug,
            title,
            description.orEmpty(),
            category.orEmpty(),
            purpose.orEmpty(),
            tone.orEmpty(),
            tags.joinToString(" "),
            safetyLevel.orEmpty(),
            responseMode.orEmpty(),
            preferredModel.orEmpty(),
        ).joinToString("\n") { it.lowercase() }

    private fun String?.normalizedSearchToken(): String? =
        this
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    private fun List<PublishedPresetSummary>.facetBy(selector: (PublishedPresetSummary) -> String): List<PresetCatalogFacet> =
        groupingBy { selector(it).trim().lowercase().ifBlank { "unknown" } }
            .eachCount()
            .map { (value, count) -> PresetCatalogFacet(value = value, count = count) }
            .sortedWith(compareByDescending<PresetCatalogFacet> { it.count }.thenBy { it.value })

    private fun List<PublishedPresetSummary>.facetValues(selector: (PublishedPresetSummary) -> List<String>): List<PresetCatalogFacet> =
        flatMap(selector)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .map { (value, count) -> PresetCatalogFacet(value = value, count = count) }
            .sortedWith(compareByDescending<PresetCatalogFacet> { it.count }.thenBy { it.value })

    private fun PublishedPresetSummary.toRecommendation(): PresetRecommendation {
        val safetyPenalty =
            when (safetyLevel.orEmpty().lowercase()) {
                "high", "restricted", "dangerous" -> 50
                "elevated", "medium" -> 12
                else -> 0
            }
        val reportPenalty = reportCount * 15
        val score = (likeCount * 3) + (importCount * 2) - safetyPenalty - reportPenalty
        val reasons =
            buildList {
                add("likes=$likeCount")
                add("imports=$importCount")
                if (safetyPenalty > 0) add("safetyPenalty=$safetyPenalty")
                if (reportPenalty > 0) add("reportPenalty=$reportPenalty")
            }
        return PresetRecommendation(preset = this, score = score, reasons = reasons)
    }

    // --- 텍스트 유틸/상수 (write 와 공유 — PresetRegistryService 에도 동일 복제 유지) ---

    private fun String?.publicOptional(maxLength: Int): String? {
        val trimmed = this?.trim()?.ifBlank { null } ?: return null
        if (trimmed.hasSensitiveMaterial()) return REDACTED_PUBLIC_TEXT
        return trimmed.take(maxLength)
    }

    private fun String.publicRequired(
        maxLength: Int,
        fallback: String,
    ): String {
        val trimmed = trim().ifBlank { return fallback }
        if (trimmed.hasSensitiveMaterial()) return fallback
        return trimmed.take(maxLength)
    }

    private fun String.publicSlug(id: Long): String {
        val trimmed = trim()
        if (trimmed.hasSensitiveMaterial() || SENSITIVE_SLUG_PATTERN.containsMatchIn(trimmed)) return "preset-$id"
        return trimmed.take(120).ifBlank { "preset-$id" }
    }

    private fun String.hasSensitiveMaterial(): Boolean =
        KnowledgeSafety.containsSensitiveMaterial(this) || SECRET_PATTERN.containsMatchIn(this)

    private fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun splitLines(value: String?): List<String> =
        value
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

    private companion object {
        const val REDACTED_PUBLIC_TITLE = "비공개 프리셋"
        const val REDACTED_PUBLIC_TEXT = "[비공개 처리됨]"
        val SECRET_PATTERN = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
        val SENSITIVE_SLUG_PATTERN = Regex("""(?i)(password|passwd|token|api[-_]?key|secret|authorization|bearer)""")
    }
}
