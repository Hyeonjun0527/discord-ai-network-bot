package com.discordassistant.central.network

import com.discordassistant.central.persistence.KnowledgeSourceEntity
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import org.springframework.stereotype.Service

@Service
class KnowledgeSearchService(
    private val sources: KnowledgeSourceRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun search(
        guildId: Long,
        query: String,
        limit: Int = 5,
    ): KnowledgeSearchResponse {
        featureGate.requireRagEnabled()
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            return KnowledgeSearchResponse(guildId = guildId, query = query, results = emptyList(), fallbackReason = "empty_query")
        }
        val candidates =
            sources
                .findByGuildId(guildId)
                .filter { it.status == "indexed" }
                .filter { it.riskLevel != "sensitive" }
                .mapNotNull { it.toResult(normalizedQuery) }
                .sortedWith(
                    compareByDescending<KnowledgeSearchResult> { it.score }
                        .thenBy { it.title },
                ).take(limit.coerceIn(1, 20))
        return KnowledgeSearchResponse(
            guildId = guildId,
            query = query,
            results = candidates,
            fallbackReason = if (candidates.isEmpty()) "no_indexed_knowledge_match" else null,
        )
    }

    private fun KnowledgeSourceEntity.toResult(query: String): KnowledgeSearchResult? {
        val haystack = listOf(title, sourceUri.orEmpty(), sourceType).joinToString(" ").lowercase()
        val terms = query.split(Regex("\\s+")).filter { it.length >= 2 }
        val score = terms.sumOf { term -> haystack.windowed(term.length).count { it == term } }
        if (score <= 0) return null
        return KnowledgeSearchResult(
            sourceId = id,
            knowledgeSpaceId = knowledgeSpaceId,
            title = title,
            sourceType = sourceType,
            sourceUri = sourceUri,
            riskLevel = riskLevel,
            score = score,
        )
    }
}

data class KnowledgeSearchResponse(
    val guildId: Long,
    val query: String,
    val results: List<KnowledgeSearchResult>,
    val fallbackReason: String?,
)

data class KnowledgeSearchResult(
    val sourceId: Long,
    val knowledgeSpaceId: Long,
    val title: String,
    val sourceType: String,
    val sourceUri: String?,
    val riskLevel: String,
    val score: Int,
)
