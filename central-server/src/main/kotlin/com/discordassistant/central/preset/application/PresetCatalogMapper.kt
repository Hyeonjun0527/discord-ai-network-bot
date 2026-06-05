package com.discordassistant.central.preset.application

import com.discordassistant.central.knowledge.application.KnowledgeSafety
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetReportEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetEntity
import com.discordassistant.central.shared.ContentSafety
import org.springframework.stereotype.Component

/**
 * 순수 카탈로그 매핑/포매팅/정렬/마스킹/필터 협력자(god-class 분해): [PresetCatalogQueryService] 에서
 * **저장소·@Transactional 이 없는 순수 함수만** 추출한다. repo fetch 가 필요한 @Transactional(readOnly)
 * 쿼리 메서드는 파사드([PresetCatalogQueryService])의 TX 안에 그대로 잔존한다.
 *
 * entity→DTO 매핑·검색 haystack·정렬/필터·공개 마스킹/redaction·정규식/상수는 추출 전과 1바이트도
 * 다르지 않다(동작보존). 보안 마스킹/해시/정규식/검색 필터/카탈로그 정렬은 원본과 동일하다.
 */
@Component
class PresetCatalogMapper {
    fun AiPresetEntity.toSummary(): PresetSummary =
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

    fun PresetRevisionEntity.toSummary(): PresetRevisionSummary =
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

    fun PresetRevisionEntity.toBehaviorSnapshot(): PresetBehaviorSnapshot =
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

    fun PresetImportEntity.toSummary(): PresetImportSummary =
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

    fun PresetReportEntity.toSummary(): PresetReportSummary =
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

    fun PublishedPresetEntity.toSummary(
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

    fun PublishedPresetSummary.searchHaystack(): String =
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

    fun String?.normalizedSearchToken(): String? =
        this
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    fun List<PublishedPresetSummary>.facetBy(selector: (PublishedPresetSummary) -> String): List<PresetCatalogFacet> =
        groupingBy { selector(it).trim().lowercase().ifBlank { "unknown" } }
            .eachCount()
            .map { (value, count) -> PresetCatalogFacet(value = value, count = count) }
            .sortedWith(compareByDescending<PresetCatalogFacet> { it.count }.thenBy { it.value })

    fun List<PublishedPresetSummary>.facetValues(selector: (PublishedPresetSummary) -> List<String>): List<PresetCatalogFacet> =
        flatMap(selector)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .map { (value, count) -> PresetCatalogFacet(value = value, count = count) }
            .sortedWith(compareByDescending<PresetCatalogFacet> { it.count }.thenBy { it.value })

    fun PublishedPresetSummary.toRecommendation(): PresetRecommendation {
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

    fun presetModerationNextActions(
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

    // --- 텍스트 유틸/상수 (write 쪽 PresetContentSafety 와 동일 동작 — 카탈로그 매퍼 자기완결 복제 유지) ---

    fun String?.publicOptional(maxLength: Int): String? {
        val trimmed = this?.trim()?.ifBlank { null } ?: return null
        if (trimmed.hasSensitiveMaterial()) return REDACTED_PUBLIC_TEXT
        return trimmed.take(maxLength)
    }

    fun String.publicRequired(
        maxLength: Int,
        fallback: String,
    ): String {
        val trimmed = trim().ifBlank { return fallback }
        if (trimmed.hasSensitiveMaterial()) return fallback
        return trimmed.take(maxLength)
    }

    fun String.publicSlug(id: Long): String {
        val trimmed = trim()
        if (trimmed.hasSensitiveMaterial() || SENSITIVE_SLUG_PATTERN.containsMatchIn(trimmed)) return "preset-$id"
        return trimmed.take(120).ifBlank { "preset-$id" }
    }

    fun String.hasSensitiveMaterial(): Boolean =
        KnowledgeSafety.containsSensitiveMaterial(this) || ContentSafety.SECRET_PATTERN.containsMatchIn(this)

    fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun splitLines(value: String?): List<String> =
        value
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

    internal companion object {
        const val REDACTED_PUBLIC_TITLE = "비공개 프리셋"
        const val REDACTED_PUBLIC_TEXT = "[비공개 처리됨]"
        val SENSITIVE_SLUG_PATTERN = Regex("""(?i)(password|passwd|token|api[-_]?key|secret|authorization|bearer)""")
    }
}
