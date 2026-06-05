package com.discordassistant.central.knowledge.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeChunkRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.RetrievalPolicyEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.RetrievalPolicyRepository
import com.discordassistant.central.knowledge.domain.model.KnowledgeChunkStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.discordassistant.central.knowledge.domain.model.RetrievalPolicyStatus
import org.springframework.stereotype.Service
import com.discordassistant.central.shared.ContentSafety.USABLE_KNOWLEDGE_RISK_LEVELS as SEARCHABLE_RISK_LEVELS

@Service
class KnowledgeSearchService(
    private val sources: KnowledgeSourceRepository,
    private val spaces: KnowledgeSpaceRepository,
    private val chunks: KnowledgeChunkRepository? = null,
    private val retrievalPolicies: RetrievalPolicyRepository? = null,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
    private val scorer: KnowledgeScorer = KnowledgeScorer(),
    private val promptComposer: KnowledgePromptComposer = KnowledgePromptComposer(),
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
        if (KnowledgeSafety.looksSensitiveQuery(query)) {
            return KnowledgeSearchResponse(
                guildId = guildId,
                query = query,
                results = emptyList(),
                fallbackReason = "blocked_sensitive_query",
            )
        }
        if (channelId == null && knowledgeSpaceId == null) {
            return KnowledgeSearchResponse(
                guildId = guildId,
                query = query,
                results = emptyList(),
                fallbackReason = "rag_scope_required",
            )
        }
        val allowedSpaceIds = allowedSpaceIds(guildId, channelId, knowledgeSpaceId)
        if (allowedSpaceIds.isEmpty()) {
            return KnowledgeSearchResponse(guildId = guildId, query = query, results = emptyList(), fallbackReason = "no_knowledge_space")
        }
        val policy = activePolicy(guildId, channelId, knowledgeSpaceId, allowedSpaceIds)
        return searchWithScope(guildId, query, normalizedQuery, limit, allowedSpaceIds, policy)
    }

    /**
     * Search core that runs once `allowedSpaceIds` and `policy` are already resolved, so callers
     * (e.g. [promptContext]) that need those scope/policy lookups can reuse them instead of
     * re-querying. Behavior is identical to the public [search] entry point.
     */
    private fun searchWithScope(
        guildId: Long,
        query: String,
        normalizedQuery: String,
        limit: Int,
        allowedSpaceIds: Set<Long>,
        policy: RetrievalPolicyEntity?,
    ): KnowledgeSearchResponse {
        val topK = minOf(limit.coerceIn(1, 20), policy?.topK ?: 20)
        // Narrow the candidate set in the DB (guild + allowed spaces + indexed + searchable risk)
        // and load it once; reused by both the chunk and source scoring passes.
        val searchableSources = searchableSources(guildId, allowedSpaceIds)
        val candidates =
            chunkCandidates(guildId, allowedSpaceIds, searchableSources, normalizedQuery, policy)
                .ifEmpty {
                    sourceCandidates(searchableSources, normalizedQuery, policy)
                }
        return KnowledgeSearchResponse(
            guildId = guildId,
            query = query,
            results =
                candidates
                    .sortedWith(
                        compareByDescending<KnowledgeSearchResult> { it.score }
                            .thenBy { it.title }
                            .thenBy { it.chunkIndex ?: 0 },
                    ).take(topK),
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
        if (KnowledgeSafety.looksSensitiveQuery(query)) {
            val budget = maxChars.coerceIn(200, 8_000)
            return KnowledgePromptContext(
                guildId = guildId,
                channelId = channelId,
                knowledgeSpaceId = knowledgeSpaceId,
                query = query,
                maxChars = budget,
                usedChars = 0,
                entries = emptyList(),
                contextText = "",
                fallbackReason = "blocked_sensitive_query",
                sourceRefs = emptyList(),
            )
        }
        val allowedSpaceIds = allowedSpaceIds(guildId, channelId, knowledgeSpaceId)
        val policy = activePolicy(guildId, channelId, knowledgeSpaceId, allowedSpaceIds)
        // Reuse the scope/policy lookups already resolved above instead of re-querying inside search().
        val normalizedQuery = query.trim().lowercase()
        val search =
            when {
                normalizedQuery.isBlank() ->
                    KnowledgeSearchResponse(guildId = guildId, query = query, results = emptyList(), fallbackReason = "empty_query")
                allowedSpaceIds.isEmpty() ->
                    KnowledgeSearchResponse(guildId = guildId, query = query, results = emptyList(), fallbackReason = "no_knowledge_space")
                else ->
                    searchWithScope(guildId, query, normalizedQuery, policy?.topK ?: 10, allowedSpaceIds, policy)
            }
        val budget = minOf(maxChars.coerceIn(200, 8_000), policy?.tokenBudget ?: 8_000)
        val composition = promptComposer.assemble(search.results, budget)
        return KnowledgePromptContext(
            guildId = guildId,
            channelId = channelId,
            knowledgeSpaceId = knowledgeSpaceId,
            query = query,
            maxChars = budget,
            usedChars = composition.usedChars,
            entries = composition.entries,
            contextText = composition.contextText,
            sourceRefs = composition.sourceRefs,
            fallbackReason =
                when {
                    search.fallbackReason != null -> search.fallbackReason
                    composition.entries.isEmpty() -> "context_budget_too_small"
                    else -> null
                },
        )
    }

    fun contextPlan(
        guildId: Long,
        query: String,
        responseMode: String = "balanced",
        requestedMaxChars: Int? = null,
        channelId: Long? = null,
        knowledgeSpaceId: Long? = null,
    ): KnowledgeContextPlan {
        featureGate.requireRagEnabled()
        val normalizedMode = promptComposer.normalizeResponseMode(responseMode)
        val modeBudget = promptComposer.ragBudgetFor(normalizedMode)
        if (modeBudget == 0) {
            return KnowledgeContextPlan.disabled(
                guildId = guildId,
                channelId = channelId,
                knowledgeSpaceId = knowledgeSpaceId,
                query = query,
                responseMode = normalizedMode,
                fallbackReason = "rag_disabled_by_response_mode",
            )
        }
        if (channelId == null && knowledgeSpaceId == null) {
            return KnowledgeContextPlan.disabled(
                guildId = guildId,
                channelId = channelId,
                knowledgeSpaceId = knowledgeSpaceId,
                query = query,
                responseMode = normalizedMode,
                fallbackReason = "rag_scope_required",
            )
        }
        if (KnowledgeSafety.looksSensitiveQuery(query)) {
            return KnowledgeContextPlan.disabled(
                guildId = guildId,
                channelId = channelId,
                knowledgeSpaceId = knowledgeSpaceId,
                query = query,
                responseMode = normalizedMode,
                fallbackReason = "blocked_sensitive_query",
            )
        }
        val requestedBudget = requestedMaxChars?.coerceIn(200, 8_000) ?: modeBudget
        val budget = minOf(requestedBudget, modeBudget)
        val context =
            promptContext(
                guildId = guildId,
                query = query,
                maxChars = budget,
                channelId = channelId,
                knowledgeSpaceId = knowledgeSpaceId,
            )
        val warnings =
            buildList {
                if (requestedMaxChars != null && requestedMaxChars > modeBudget) add("requested_budget_capped_by_response_mode")
                context.fallbackReason?.let { add(it) }
                if (context.usedChars >= budget) add("context_budget_exhausted")
            }.distinct()
        return KnowledgeContextPlan(
            guildId = guildId,
            channelId = channelId,
            knowledgeSpaceId = knowledgeSpaceId,
            query = query,
            responseMode = normalizedMode,
            enabled = context.entries.isNotEmpty(),
            maxChars = budget,
            usedChars = context.usedChars,
            entries = context.entries,
            contextText = context.contextText,
            fallbackReason = context.fallbackReason,
            warnings = warnings,
            sourceRefs = context.sourceRefs,
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

    private fun searchableSources(
        guildId: Long,
        allowedSpaceIds: Set<Long>,
    ): List<KnowledgeSourceEntity> {
        if (allowedSpaceIds.isEmpty()) return emptyList()
        return sources.findByGuildIdAndKnowledgeSpaceIdInAndStatusAndRiskLevelIn(
            guildId,
            allowedSpaceIds,
            KnowledgeSourceStatus.INDEXED,
            SEARCHABLE_RISK_LEVELS,
        )
    }

    private fun sourceCandidates(
        searchableSources: List<KnowledgeSourceEntity>,
        query: String,
        policy: RetrievalPolicyEntity?,
    ): List<KnowledgeSearchResult> = searchableSources.mapNotNull { scorer.toResult(it, query, policy) }

    private fun chunkCandidates(
        guildId: Long,
        allowedSpaceIds: Set<Long>,
        searchableSources: List<KnowledgeSourceEntity>,
        query: String,
        policy: RetrievalPolicyEntity?,
    ): List<KnowledgeSearchResult> {
        val chunkRepo = chunks ?: return emptyList()
        if (allowedSpaceIds.isEmpty()) return emptyList()
        val sourceById = searchableSources.associateBy { it.id }
        if (sourceById.isEmpty()) return emptyList()
        return chunkRepo
            .findByGuildIdAndKnowledgeSpaceIdInAndStatus(guildId, allowedSpaceIds, KnowledgeChunkStatus.READY)
            .mapNotNull { chunk ->
                val source = sourceById[chunk.knowledgeSourceId] ?: return@mapNotNull null
                scorer.toResult(chunk, source, query, policy)
            }
    }

    private fun activePolicy(
        guildId: Long,
        channelId: Long?,
        knowledgeSpaceId: Long?,
        allowedSpaceIds: Set<Long>,
    ): RetrievalPolicyEntity? {
        val repo = retrievalPolicies ?: return null
        knowledgeSpaceId?.let { spaceId ->
            return repo.findByGuildIdAndChannelIdAndKnowledgeSpaceIdAndStatus(guildId, channelId, spaceId, RetrievalPolicyStatus.ACTIVE)
                ?: repo.findByGuildIdAndChannelIdAndKnowledgeSpaceIdAndStatus(guildId, null, spaceId, RetrievalPolicyStatus.ACTIVE)
        }
        val channelPolicies =
            channelId?.let { repo.findByGuildIdAndChannelIdAndStatus(guildId, it, RetrievalPolicyStatus.ACTIVE) }.orEmpty()
        return channelPolicies.firstOrNull { it.knowledgeSpaceId in allowedSpaceIds }
            ?: channelPolicies.firstOrNull { it.knowledgeSpaceId == null }
    }

    private companion object {
        const val MIN_HIT_AT_K = 0.8
        const val MIN_MRR = 0.7
        const val MIN_RECALL_AT_K = 0.7
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
    val sourceWeight: Int = 0,
    val matchSignals: List<String> = emptyList(),
    val chunkId: Long? = null,
    val chunkIndex: Int? = null,
    val contentPreview: String? = null,
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
    val sourceRefs: List<KnowledgeSourceRef> = emptyList(),
)

data class KnowledgePromptEntry(
    val sourceId: Long,
    val knowledgeSpaceId: Long,
    val title: String,
    val sourceType: String,
    val sourceUri: String?,
    val snippet: String,
)

data class KnowledgeSourceRef(
    val ref: String,
    val sourceId: Long,
    val knowledgeSpaceId: Long,
    val title: String,
    val sourceType: String,
    val sourceUri: String?,
    val visibility: String,
)

data class KnowledgeContextPlan(
    val guildId: Long,
    val channelId: Long?,
    val knowledgeSpaceId: Long?,
    val query: String,
    val responseMode: String,
    val enabled: Boolean,
    val maxChars: Int,
    val usedChars: Int,
    val entries: List<KnowledgePromptEntry>,
    val contextText: String,
    val fallbackReason: String?,
    val warnings: List<String>,
    val sourceRefs: List<KnowledgeSourceRef> = emptyList(),
) {
    companion object {
        fun disabled(
            guildId: Long,
            channelId: Long?,
            knowledgeSpaceId: Long?,
            query: String,
            responseMode: String,
            fallbackReason: String,
        ) = KnowledgeContextPlan(
            guildId = guildId,
            channelId = channelId,
            knowledgeSpaceId = knowledgeSpaceId,
            query = query,
            responseMode = responseMode,
            enabled = false,
            maxChars = 0,
            usedChars = 0,
            entries = emptyList(),
            contextText = "",
            fallbackReason = fallbackReason,
            warnings = listOf(fallbackReason),
            sourceRefs = emptyList(),
        )
    }
}

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
