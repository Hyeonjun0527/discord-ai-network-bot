package com.discordassistant.central.knowledge.application

import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeChunkEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.RetrievalPolicyEntity
import org.springframework.stereotype.Component

/**
 * RAG 후보 스코어링/랭킹 협력자 — 읽기 전용·순수 계산(@Transactional·write·repo 의존 없음).
 *
 * 소스/청크 raw 스코어(용어 빈도 + 제목 가중), 정책 기반 source-priority/admin 부스트,
 * match-signal 라벨링을 한곳에 모은다. 본문은 [KnowledgeSearchService] 에서 1바이트 불변으로
 * 이동했으며 스코어링 공식·가중치(BM25류 windowed count, +2 제목, SOURCE_PRIORITY_BOOST,
 * DEFAULT_ADMIN_SOURCE_BOOST)는 변경하지 않는다.
 */
@Component
class KnowledgeScorer {
    fun toResult(
        source: KnowledgeSourceEntity,
        query: String,
        policy: RetrievalPolicyEntity?,
    ): KnowledgeSearchResult? {
        val haystack = listOf(source.title, source.sourceUri.orEmpty(), source.sourceType).joinToString(" ").lowercase()
        val terms = query.split(Regex("\\s+")).filter { it.length >= 2 }
        val rawScore = terms.sumOf { term -> haystack.windowed(term.length).count { it == term } }
        if (rawScore <= 0) return null
        val matchSignals = matchSignals(query, terms, source.title, source.sourceUri, source.sourceType, content = null, chunk = false)
        val sourceWeight = sourceWeight(source, policy)
        return KnowledgeSearchResult(
            sourceId = source.id,
            knowledgeSpaceId = source.knowledgeSpaceId,
            title = source.title,
            sourceType = source.sourceType,
            sourceUri = source.sourceUri,
            riskLevel = source.riskLevel,
            score = rawScore + sourceWeight,
            sourceWeight = sourceWeight,
            matchSignals = matchSignals,
        )
    }

    fun toResult(
        chunk: KnowledgeChunkEntity,
        source: KnowledgeSourceEntity,
        query: String,
        policy: RetrievalPolicyEntity?,
    ): KnowledgeSearchResult? {
        val terms = query.split(Regex("\\s+")).filter { it.length >= 2 }
        val haystack =
            listOf(chunk.title, source.sourceUri.orEmpty(), source.sourceType, chunk.contentPreview).joinToString(" ").lowercase()
        val rawScore =
            terms.sumOf { term ->
                haystack.windowed(term.length).count { it == term }
            } + terms.count { chunk.title.lowercase().contains(it) } * 2
        if (rawScore <= 0) return null
        val sourceWeight = sourceWeight(source, policy)
        return KnowledgeSearchResult(
            sourceId = source.id,
            knowledgeSpaceId = chunk.knowledgeSpaceId,
            title = chunk.title,
            sourceType = source.sourceType,
            sourceUri = source.sourceUri,
            riskLevel = source.riskLevel,
            score = rawScore + sourceWeight,
            sourceWeight = sourceWeight,
            matchSignals = matchSignals(query, terms, chunk.title, source.sourceUri, source.sourceType, chunk.contentPreview, chunk = true),
            chunkId = chunk.id,
            chunkIndex = chunk.chunkIndex,
            contentPreview = chunk.contentPreview,
        )
    }

    fun sourceWeight(
        source: KnowledgeSourceEntity,
        policy: RetrievalPolicyEntity?,
    ): Int {
        val rawLabels =
            buildList {
                add(source.sourceType.trim().lowercase())
                if (source.addedBy != null) add("admin")
                val publicText = "${source.title} ${source.sourceUri.orEmpty()}".lowercase()
                if ("help" in publicText || "faq" in publicText || "도움말" in publicText) add("help")
                if ("summary" in publicText || "요약" in publicText) add("summary")
            }
        val labels = rawLabels.filter { it.isNotBlank() }.distinct()
        val priorities =
            policy
                ?.sourcePriority
                ?.split(",")
                .orEmpty()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
        val priorityIndex = priorities.indexOfFirst { it in labels }
        val policyBoost =
            priorityIndex
                .takeIf { index -> index >= 0 }
                ?.let { (priorities.size - it) * SOURCE_PRIORITY_BOOST }
                ?: 0
        val defaultBoost = if ("admin" in labels) DEFAULT_ADMIN_SOURCE_BOOST else 0
        return policyBoost + defaultBoost
    }

    private fun matchSignals(
        query: String,
        terms: List<String>,
        title: String,
        sourceUri: String?,
        sourceType: String,
        content: String?,
        chunk: Boolean,
    ): List<String> {
        val normalizedQuery = query.trim().lowercase()
        val normalizedTitle = title.lowercase()
        val normalizedUri = sourceUri.orEmpty().lowercase()
        val normalizedContent = content.orEmpty().lowercase()
        return buildList {
            if (chunk) add("chunk")
            if (normalizedQuery.isNotBlank() && normalizedTitle.contains(normalizedQuery)) add("exact_title")
            if (normalizedQuery.isNotBlank() && normalizedUri.contains(normalizedQuery)) add("exact_uri")
            if (normalizedQuery.isNotBlank() && normalizedContent.contains(normalizedQuery)) add("exact_content")
            if (terms.any { normalizedTitle.contains(it) }) add("term_title")
            if (terms.any { normalizedUri.contains(it) }) add("term_uri")
            if (terms.any { normalizedContent.contains(it) }) add("term_content")
            add("source_type:${sourceType.trim().lowercase().ifBlank { "unknown" }}")
        }.distinct()
    }

    private companion object {
        const val SOURCE_PRIORITY_BOOST = 6
        const val DEFAULT_ADMIN_SOURCE_BOOST = 2
    }
}
