package com.discordassistant.central.knowledge.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import org.springframework.stereotype.Component
import com.discordassistant.central.shared.ContentSafety.USABLE_KNOWLEDGE_RISK_LEVELS as INDEXABLE_RISK_LEVELS

/**
 * 색인 계획 수립 — 읽기 전용 협력자(@Transactional·write 없음).
 *
 * 조회·분류·계획 합성만 한다. 같은 빈 self-call(indexingOperations→indexingPlan)도 @Transactional 이
 * 없어 TX 영향이 없다. 차단 risk 분기·command·문구는 추출 전과 1바이트 불변.
 */
@Component
class KnowledgeIndexingPlanner(
    private val spaces: KnowledgeSpaceRepository,
    private val sources: KnowledgeSourceRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun indexingPlan(
        guildId: Long,
        spaceId: Long,
        force: Boolean = false,
    ): KnowledgeIndexingPlan {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val sourceList = sources.findByKnowledgeSpaceId(space.id).filter { !it.status.isDeleted }
        val indexable =
            sourceList
                .filter { it.riskLevel in INDEXABLE_RISK_LEVELS }
                .filter { force || it.status.isPending || it.status.isIndexed }
                .sortedWith(compareBy<KnowledgeSourceEntity> { it.status.wire }.thenBy { it.id })
        val blocked = sourceList.filter { it.riskLevel !in INDEXABLE_RISK_LEVELS || it.status.isBlocked }
        val collection = space.indexName?.trim()?.ifBlank { null } ?: defaultCollectionName(guildId, space.channelId, space.id)
        val embeddingModel = space.embeddingModel?.trim()?.ifBlank { null } ?: DEFAULT_EMBEDDING_MODEL
        val command =
            listOf(
                "scripts/rag.sh",
                "rebuild",
                "--guild",
                guildId.toString(),
                "--space",
                space.id.toString(),
                "--collection",
                collection,
                "--embedding-model",
                embeddingModel,
            ) + if (force) listOf("--force") else emptyList()
        return KnowledgeIndexingPlan(
            guildId = guildId,
            knowledgeSpaceId = space.id,
            channelId = space.channelId,
            collectionName = collection,
            embeddingModel = embeddingModel,
            runtime = "python3.12-qdrant-llamaindex-bm25-rrf-reranker",
            qdrantRequired = true,
            force = force,
            command = command.joinToString(" "),
            indexableSources = indexable.map { it.toIndexingSource() },
            blockedSources = blocked.map { it.toIndexingSource() },
            ready = indexable.isNotEmpty() && blocked.none { it.riskLevel in KnowledgeReadinessReporter.BLOCKING_RISK_LEVELS },
            warnings = indexingWarnings(indexable, blocked),
        )
    }

    fun indexingOperations(
        guildId: Long,
        force: Boolean = false,
    ): KnowledgeIndexingOperationsSummary {
        featureGate.requireRagEnabled()
        val plans = spaces.findByGuildId(guildId).map { indexingPlan(guildId, it.id, force) }
        val indexableCount = plans.sumOf { it.indexableSources.size }
        val blockedCount = plans.sumOf { it.blockedSources.size }
        val readyPlans = plans.count { it.ready }
        val warnings = plans.flatMap { it.warnings }.distinct().sorted()
        val status =
            when {
                plans.isEmpty() -> "empty"
                blockedCount > 0 -> "blocked"
                indexableCount > 0 -> "ready"
                else -> "nothing_to_index"
            }
        val nextActions =
            buildList {
                if (plans.isEmpty()) add("먼저 지식공간과 지식 소스를 추가하세요.")
                if (blockedCount > 0) add("blocked/review 소스를 승인·거절·삭제한 뒤 색인을 다시 실행하세요.")
                if (indexableCount > 0) add("ready=true인 indexingPlans의 command를 실행하세요.")
                if (status == "nothing_to_index") add("색인할 pending 소스가 없습니다. force=true로 재색인 계획을 확인할 수 있습니다.")
            }.distinct()
        return KnowledgeIndexingOperationsSummary(
            guildId = guildId,
            status = status,
            force = force,
            spaceCount = plans.size,
            readyPlanCount = readyPlans,
            indexableSourceCount = indexableCount,
            blockedSourceCount = blockedCount,
            warnings = warnings,
            nextActions = nextActions,
            commands = plans.filter { it.indexableSources.isNotEmpty() }.map { it.command },
            plans = plans,
        )
    }

    private fun KnowledgeSourceEntity.toIndexingSource(): KnowledgeIndexingSource =
        KnowledgeIndexingSource(
            id = id,
            sourceType = sourceType,
            title = title,
            sourceUri = sourceUri,
            status = status.wire,
            riskLevel = riskLevel,
            contentHash = contentHash,
        )

    private fun indexingWarnings(
        indexable: List<KnowledgeSourceEntity>,
        blocked: List<KnowledgeSourceEntity>,
    ): List<String> =
        buildList {
            if (indexable.isEmpty()) add("indexable_source_empty")
            if (blocked.any { it.riskLevel == "sensitive" }) add("sensitive_source_blocked")
            if (blocked.any { it.riskLevel == "ssrf" }) add("ssrf_source_blocked")
            if (blocked.any { it.riskLevel == "review" }) add("manual_review_required")
        }

    private fun defaultCollectionName(
        guildId: Long,
        channelId: Long?,
        spaceId: Long,
    ): String =
        listOfNotNull(
            "discord_ai",
            "guild_$guildId",
            channelId?.let { "channel_$it" },
            "space_$spaceId",
        ).joinToString("__")

    internal companion object {
        const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-large"
    }
}
