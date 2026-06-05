package com.discordassistant.central.knowledge.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import org.springframework.stereotype.Component

/**
 * 지식 소스/공간 준비도·품질 리포트 — 읽기 전용 협력자(@Transactional·write 없음).
 *
 * 모든 메서드는 조회·집계만 한다. 같은 빈 내부 self-call(spaceStatus/guildReadiness/qualitySummary)도
 * @Transactional 이 없어 TX 영향이 없다. 집계 분기·문구는 추출 전과 1바이트 불변.
 */
@Component
class KnowledgeReadinessReporter(
    private val spaces: KnowledgeSpaceRepository,
    private val sources: KnowledgeSourceRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun listSources(
        guildId: Long,
        spaceId: Long,
    ): List<KnowledgeSourceSummary> {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        return sources
            .findByKnowledgeSpaceId(space.id)
            .filter { !it.status.isDeleted }
            .sortedWith(compareByDescending<KnowledgeSourceEntity> { it.addedAt }.thenBy { it.id })
            .map { it.toSummary() }
    }

    fun spaceStatus(
        guildId: Long,
        spaceId: Long,
    ): KnowledgeSpaceStatusSummary {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val sourceList = sources.findByKnowledgeSpaceId(space.id).filter { !it.status.isDeleted }
        val indexed = sourceList.count { it.status.isIndexed }
        val blocked = sourceList.count { it.status.isBlocked || it.riskLevel in BLOCKING_RISK_LEVELS }
        val pending = sourceList.count { it.status.isPending }
        val rejected = sourceList.count { it.status.isRejected }
        val readiness =
            when {
                indexed > 0 && blocked == 0 && pending == 0 -> "ready"
                indexed > 0 -> "partial"
                pending > 0 -> "indexing_needed"
                blocked > 0 -> "needs_review"
                rejected > 0 -> "rejected"
                else -> "empty"
            }
        return KnowledgeSpaceStatusSummary(
            guildId = guildId,
            knowledgeSpaceId = space.id,
            channelId = space.channelId,
            channelAiId = space.channelAiId,
            displayName = space.displayName,
            status = space.status.wire,
            readiness = readiness,
            sourceCount = sourceList.size,
            indexedSourceCount = indexed,
            pendingSourceCount = pending,
            blockedSourceCount = blocked,
            rejectedSourceCount = rejected,
            chunkCount = space.chunkCount,
            riskLevels = sourceList.groupingBy { it.riskLevel }.eachCount(),
            sourceStatuses = sourceList.groupingBy { it.status.wire }.eachCount(),
        )
    }

    fun guildReadiness(guildId: Long): KnowledgeGuildReadiness {
        featureGate.requireRagEnabled()
        val summaries = spaces.findByGuildId(guildId).map { spaceStatus(guildId, it.id) }
        val readySpaces = summaries.count { it.readiness == "ready" }
        val partialSpaces = summaries.count { it.readiness == "partial" }
        val blockedSources = summaries.sumOf { it.blockedSourceCount }
        val pendingSources = summaries.sumOf { it.pendingSourceCount }
        val indexedSources = summaries.sumOf { it.indexedSourceCount }
        val totalSources = summaries.sumOf { it.sourceCount }
        val status =
            when {
                summaries.isEmpty() -> "empty"
                blockedSources > 0 -> "needs_review"
                readySpaces > 0 && pendingSources == 0 -> "ready"
                readySpaces > 0 || partialSpaces > 0 -> "partial"
                pendingSources > 0 -> "indexing_needed"
                else -> "empty"
            }
        val gates =
            listOf(
                KnowledgeReadinessGate(
                    code = "has_knowledge_space",
                    passed = summaries.isNotEmpty(),
                    message = if (summaries.isNotEmpty()) "지식공간이 있습니다." else "먼저 채널 지식공간을 만드세요.",
                ),
                KnowledgeReadinessGate(
                    code = "has_indexed_source",
                    passed = indexedSources > 0,
                    message = if (indexedSources > 0) "색인된 지식 소스가 있습니다." else "최소 1개 이상의 지식 소스를 색인하세요.",
                ),
                KnowledgeReadinessGate(
                    code = "no_blocked_sources",
                    passed = blockedSources == 0,
                    message = if (blockedSources == 0) "차단된 지식 소스가 없습니다." else "민감정보/SSRF 위험 지식 소스를 검토하세요.",
                ),
                KnowledgeReadinessGate(
                    code = "no_pending_sources",
                    passed = pendingSources == 0,
                    message = if (pendingSources == 0) "대기 중인 색인 작업이 없습니다." else "대기 중인 지식 소스를 색인하세요.",
                ),
            )
        val nextActions =
            buildList {
                if (summaries.isEmpty()) add("/지식추가 또는 대시보드에서 지식공간을 먼저 만드세요.")
                if (indexedSources == 0 && totalSources > 0) add("indexing-plan을 확인하고 scripts/rag.sh rebuild를 실행하세요.")
                if (pendingSources > 0) add("pending 소스를 색인 완료 처리하거나 실패 원인을 확인하세요.")
                if (blockedSources > 0) add("blocked_sensitive/blocked_ssrf 소스를 삭제하거나 review 소스만 승인하세요.")
                if (status == "ready") add("RAG context-plan과 golden eval을 실행해 검색 품질을 확인하세요.")
            }
        return KnowledgeGuildReadiness(
            guildId = guildId,
            status = status,
            spaceCount = summaries.size,
            readySpaceCount = readySpaces,
            partialSpaceCount = partialSpaces,
            sourceCount = totalSources,
            indexedSourceCount = indexedSources,
            pendingSourceCount = pendingSources,
            blockedSourceCount = blockedSources,
            gates = gates,
            nextActions = nextActions,
            spaces = summaries,
        )
    }

    fun qualitySummary(guildId: Long): KnowledgeQualitySummary {
        featureGate.requireRagEnabled()
        val readiness = guildReadiness(guildId)
        val indexedRatio =
            if (readiness.sourceCount == 0) {
                0.0
            } else {
                readiness.indexedSourceCount.toDouble() / readiness.sourceCount.toDouble()
            }
        val riskPenalty = readiness.blockedSourceCount * 25 + readiness.pendingSourceCount * 10
        val coverageScore =
            when {
                readiness.spaceCount == 0 -> 0
                else -> ((indexedRatio * 100).toInt() - riskPenalty).coerceIn(0, 100)
            }
        val qualityBand =
            when {
                readiness.blockedSourceCount > 0 -> "blocked"
                coverageScore >= 85 -> "healthy"
                coverageScore >= 50 -> "partial"
                readiness.pendingSourceCount > 0 -> "indexing_needed"
                else -> "empty"
            }
        val risks =
            buildList {
                if (readiness.spaceCount == 0) add("no_knowledge_space")
                if (readiness.indexedSourceCount == 0) add("no_indexed_sources")
                if (readiness.pendingSourceCount > 0) add("pending_indexing")
                if (readiness.blockedSourceCount > 0) add("blocked_or_sensitive_sources")
                if (readiness.spaces.any { it.chunkCount == 0 && it.indexedSourceCount > 0 }) add("indexed_without_chunks")
            }
        val recommendations =
            buildList {
                addAll(readiness.nextActions)
                if (qualityBand == "healthy") add("golden eval을 정기적으로 실행하고 실패 케이스를 지식 소스로 보강하세요.")
                if (risks.contains("indexed_without_chunks")) add("색인 완료 소스의 chunkCount가 0입니다. RAG worker 결과를 확인하세요.")
            }.distinct()
        return KnowledgeQualitySummary(
            guildId = guildId,
            status = readiness.status,
            qualityBand = qualityBand,
            coverageScore = coverageScore,
            indexedRatio = indexedRatio,
            spaceCount = readiness.spaceCount,
            sourceCount = readiness.sourceCount,
            indexedSourceCount = readiness.indexedSourceCount,
            pendingSourceCount = readiness.pendingSourceCount,
            blockedSourceCount = readiness.blockedSourceCount,
            riskCodes = risks,
            recommendations = recommendations,
        )
    }

    private fun KnowledgeSourceEntity.toSummary(): KnowledgeSourceSummary =
        KnowledgeSourceSummary(
            id = id,
            knowledgeSpaceId = knowledgeSpaceId,
            guildId = guildId,
            sourceType = sourceType,
            title = title,
            sourceUri = sourceUri,
            status = status.wire,
            contentHash = contentHash,
            riskLevel = riskLevel,
            addedBy = addedBy,
            addedAt = addedAt.toString(),
            indexedAt = indexedAt?.toString(),
        )

    internal companion object {
        val BLOCKING_RISK_LEVELS = setOf("sensitive", "ssrf")
    }
}
