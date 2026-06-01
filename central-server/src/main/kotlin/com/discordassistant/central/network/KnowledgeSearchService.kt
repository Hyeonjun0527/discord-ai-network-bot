package com.discordassistant.central.network

import com.discordassistant.central.persistence.KnowledgeSourceEntity
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import org.springframework.stereotype.Service

@Service
class KnowledgeSearchService(
    private val sources: KnowledgeSourceRepository,
    private val spaces: KnowledgeSpaceRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun search(
        guildId: Long,
        query: String,
        limit: Int = 5,
        channelId: Long? = null,
        knowledgeSpaceId: Long? = null,
    ): KnowledgeSearchResponse {
        featureGate.requireRagEnabled()
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            return KnowledgeSearchResponse(guildId = guildId, query = query, results = emptyList(), fallbackReason = "empty_query")
        }
        val allowedSpaceIds = allowedSpaceIds(guildId, channelId, knowledgeSpaceId)
        if (allowedSpaceIds.isEmpty()) {
            return KnowledgeSearchResponse(guildId = guildId, query = query, results = emptyList(), fallbackReason = "no_knowledge_space")
        }
        val candidates =
            sources
                .findByGuildId(guildId)
                .filter { it.knowledgeSpaceId in allowedSpaceIds }
                .filter { it.status == "indexed" }
                .filter { it.riskLevel in SEARCHABLE_RISK_LEVELS }
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

    fun evaluate(
        guildId: Long,
        cases: List<KnowledgeGoldenCase>,
        k: Int = 10,
    ): KnowledgeRetrievalEvaluation {
        featureGate.requireRagEnabled()
        require(cases.isNotEmpty()) { "RAG evaluation requires at least one golden case" }
        val topK = k.coerceIn(1, 20)
        val results =
            cases.map { golden ->
                require(golden.channelId != null || golden.knowledgeSpaceId != null) {
                    "golden case requires channelId or knowledgeSpaceId scope: ${golden.name}"
                }
                val expected = golden.expectedSourceIds.toSet()
                require(expected.isNotEmpty()) { "golden case requires expectedSourceIds: ${golden.name}" }
                val search =
                    search(
                        guildId = guildId,
                        query = golden.query,
                        limit = topK,
                        channelId = golden.channelId,
                        knowledgeSpaceId = golden.knowledgeSpaceId,
                    )
                val returned = search.results.map { it.sourceId }
                val firstHitRank = returned.indexOfFirst { it in expected }.takeIf { it >= 0 }?.plus(1)
                val hitCount = returned.count { it in expected }
                KnowledgeGoldenCaseResult(
                    name = golden.name,
                    query = golden.query,
                    expectedSourceIds = expected.sorted(),
                    returnedSourceIds = returned,
                    hit = firstHitRank != null,
                    firstHitRank = firstHitRank,
                    reciprocalRank = firstHitRank?.let { 1.0 / it } ?: 0.0,
                    recall = hitCount.toDouble() / expected.size.toDouble(),
                    fallbackReason = search.fallbackReason,
                )
            }
        val hitAtK = results.count { it.hit }.toDouble() / results.size.toDouble()
        val mrr = results.sumOf { it.reciprocalRank } / results.size.toDouble()
        val recallAtK = results.sumOf { it.recall } / results.size.toDouble()
        return KnowledgeRetrievalEvaluation(
            guildId = guildId,
            k = topK,
            caseCount = results.size,
            hitAtK = hitAtK,
            mrr = mrr,
            recallAtK = recallAtK,
            passed = hitAtK >= MIN_HIT_AT_K && mrr >= MIN_MRR && recallAtK >= MIN_RECALL_AT_K,
            cases = results,
        )
    }

    fun promptContext(
        guildId: Long,
        query: String,
        maxChars: Int = 1200,
        channelId: Long? = null,
        knowledgeSpaceId: Long? = null,
    ): KnowledgePromptContext {
        featureGate.requireRagEnabled()
        require(channelId != null || knowledgeSpaceId != null) {
            "RAG prompt context requires channelId or knowledgeSpaceId scope"
        }
        val search = search(guildId, query, limit = 10, channelId = channelId, knowledgeSpaceId = knowledgeSpaceId)
        val budget = maxChars.coerceIn(200, 8_000)
        val entries = mutableListOf<KnowledgePromptEntry>()
        var used = 0
        for (result in search.results) {
            val text = result.toPromptSnippet()
            if (used + text.length > budget) break
            entries +=
                KnowledgePromptEntry(
                    sourceId = result.sourceId,
                    knowledgeSpaceId = result.knowledgeSpaceId,
                    title = result.title,
                    sourceType = result.sourceType,
                    sourceUri = result.sourceUri,
                    snippet = text,
                )
            used += text.length
        }
        val contextText =
            entries.joinToString("\n") {
                "- [source:${it.sourceId}] ${it.snippet}"
            }
        return KnowledgePromptContext(
            guildId = guildId,
            channelId = channelId,
            knowledgeSpaceId = knowledgeSpaceId,
            query = query,
            maxChars = budget,
            usedChars = contextText.length,
            entries = entries,
            contextText = contextText,
            fallbackReason =
                when {
                    search.fallbackReason != null -> search.fallbackReason
                    entries.isEmpty() -> "context_budget_too_small"
                    else -> null
                },
        )
    }

    private fun allowedSpaceIds(
        guildId: Long,
        channelId: Long?,
        knowledgeSpaceId: Long?,
    ): Set<Long> {
        knowledgeSpaceId?.let { id ->
            val space = spaces.findByGuildIdAndId(guildId, id) ?: return emptySet()
            if (channelId != null && space.channelId != channelId) return emptySet()
            return setOf(space.id)
        }
        return if (channelId != null) {
            spaces.findByGuildIdAndChannelId(guildId, channelId).map { it.id }.toSet()
        } else {
            spaces.findByGuildId(guildId).map { it.id }.toSet()
        }
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

    private fun KnowledgeSearchResult.toPromptSnippet(): String =
        listOfNotNull(
            title.take(180),
            sourceUri?.take(240),
            "type=$sourceType",
        ).joinToString(" · ")

    private companion object {
        const val MIN_HIT_AT_K = 0.8
        const val MIN_MRR = 0.7
        const val MIN_RECALL_AT_K = 0.7
        val SEARCHABLE_RISK_LEVELS = setOf("normal", "review")
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

data class KnowledgePromptContext(
    val guildId: Long,
    val channelId: Long?,
    val knowledgeSpaceId: Long?,
    val query: String,
    val maxChars: Int,
    val usedChars: Int,
    val entries: List<KnowledgePromptEntry>,
    val contextText: String,
    val fallbackReason: String?,
)

data class KnowledgePromptEntry(
    val sourceId: Long,
    val knowledgeSpaceId: Long,
    val title: String,
    val sourceType: String,
    val sourceUri: String?,
    val snippet: String,
)

data class KnowledgeGoldenCase(
    val name: String,
    val query: String,
    val expectedSourceIds: List<Long>,
    val channelId: Long? = null,
    val knowledgeSpaceId: Long? = null,
)

data class KnowledgeGoldenCaseResult(
    val name: String,
    val query: String,
    val expectedSourceIds: List<Long>,
    val returnedSourceIds: List<Long>,
    val hit: Boolean,
    val firstHitRank: Int?,
    val reciprocalRank: Double,
    val recall: Double,
    val fallbackReason: String?,
)

data class KnowledgeRetrievalEvaluation(
    val guildId: Long,
    val k: Int,
    val caseCount: Int,
    val hitAtK: Double,
    val mrr: Double,
    val recallAtK: Double,
    val passed: Boolean,
    val cases: List<KnowledgeGoldenCaseResult>,
)
