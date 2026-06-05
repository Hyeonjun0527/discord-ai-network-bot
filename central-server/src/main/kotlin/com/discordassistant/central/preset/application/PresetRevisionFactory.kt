package com.discordassistant.central.preset.application

import com.discordassistant.central.ainetwork.domain.model.AI_NETWORK_MAX_CANDIDATES
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionRepository
import com.discordassistant.central.shared.ContentSafety
import com.discordassistant.central.shared.ResponseMode
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * [PresetBehaviorInput] → [PresetRevisionEntity] 정규화/생성 협력자.
 *
 * @Transactional 이 없으므로 `revisions.save(...)` 를 호출해도 호출자(파사드)의 활성 트랜잭션에
 * 그대로 합류한다 — 빈 경계와 무관하게 원자성이 보존되며 새 TX 를 열지 않는다. 정규화 규칙·보안
 * 마스킹은 [PresetContentSafety] 와 [ResponseMode] 를 공유해 원본과 동일하다.
 */
@Service
class PresetRevisionFactory(
    private val revisions: PresetRevisionRepository,
    private val safety: PresetContentSafety = PresetContentSafety(),
) {
    fun createRevision(
        preset: AiPresetEntity,
        revision: Int,
        behavior: PresetBehaviorInput,
        createdBy: Long?,
        now: Instant,
    ): PresetRevisionEntity {
        val preferredModel =
            behavior.preferredModel
                ?.trim()
                ?.ifBlank { null }
                ?.take(160)
        val minQualityTier =
            behavior.minQualityTier
                .trim()
                .ifBlank { "standard" }
                .take(40)
        val costGuard =
            behavior.costGuard
                .trim()
                .ifBlank { "provider_safe" }
                .take(80)
        return revisions.save(
            PresetRevisionEntity(
                presetId = preset.id,
                revision = revision,
                name = preset.name,
                purpose = behavior.purpose.trim().ifBlank { "general_assistant" },
                tone = behavior.tone.trim().ifBlank { "friendly" },
                answerLength = behavior.answerLength.trim().ifBlank { "balanced" },
                constitution = behavior.constitution?.trim()?.ifBlank { null },
                safetyLevel = behavior.safetyLevel.trim().ifBlank { "standard" },
                responseMode = normalizeResponseMode(behavior.responseMode),
                preferredModel = preferredModel,
                minQualityTier = minQualityTier,
                maxCandidates = behavior.maxCandidates.coerceIn(1, AI_NETWORK_MAX_CANDIDATES),
                providerTagFilter = behavior.providerTagFilter.normalizedCsv(),
                tags = behavior.tags.normalizedPresetTags(),
                costGuard = costGuard,
                knowledgeSlotNames = behavior.knowledgeSlotNames.normalizedKnowledgeSlots(),
                knowledgeGuide = behavior.knowledgeGuide.sanitizedKnowledgeGuide(),
                exampleQuestions = behavior.exampleQuestions.normalizedExampleQuestions(),
                changeSummary = behavior.changeSummary?.trim()?.ifBlank { null },
                createdBy = createdBy,
                createdAt = now,
            ),
        )
    }

    fun normalizeResponseMode(value: String): String = ResponseMode.normalize(value).wire

    private fun List<String>.normalizedCsv(): String? =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
            .ifBlank { null }

    private fun List<String>.normalizedPresetTags(): String? =
        map { it.normalizedPresetTag() }
            .filter { it.isNotBlank() && !it.hasSensitiveMaterial() }
            .distinct()
            .take(12)
            .joinToString(",")
            .ifBlank { null }

    private fun String.normalizedPresetTag(): String =
        trim()
            .lowercase()
            .replace(Regex("\\s+"), "-")
            .take(40)

    private fun List<String>.normalizedKnowledgeSlots(): String? =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("\\s+"), " ").take(80) }
            .distinct()
            .take(10)
            .joinToString(",")
            .ifBlank { null }

    private fun String?.sanitizedKnowledgeGuide(): String? =
        this
            ?.trim()
            ?.replace(ContentSafety.SECRET_PATTERN, "[redacted]")
            ?.take(1000)
            ?.ifBlank { null }

    private fun List<String>.normalizedExampleQuestions(): String? =
        map { it.trim().replace(Regex("\\s+"), " ").take(160) }
            .filter { it.isNotBlank() && !it.hasSensitiveMaterial() }
            .distinct()
            .take(5)
            .joinToString("\n")
            .ifBlank { null }

    private fun String.hasSensitiveMaterial(): Boolean = with(safety) { hasSensitiveMaterial() }
}
