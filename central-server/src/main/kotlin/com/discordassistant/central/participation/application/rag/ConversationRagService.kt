package com.discordassistant.central.participation.application.rag

import com.discordassistant.central.participation.application.port.out.ConversationEmbeddingPort
import com.discordassistant.central.participation.application.port.out.ConversationRagStorePort
import com.discordassistant.central.participation.application.port.out.ConversationRagStoredEntry
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.rag.ConversationRagEntry
import com.discordassistant.central.participation.domain.model.rag.ConversationRagMatch
import com.discordassistant.central.participation.domain.model.rag.ConversationRagScoringMethod
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import kotlin.math.sqrt

@Service
class ConversationRagService(
    private val store: ConversationRagStorePort,
    private val embeddings: ConversationEmbeddingPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ConversationRagService::class.java)

    @Transactional(readOnly = true)
    fun library(): ConversationRagLibrary {
        val entries = store.list()
        return ConversationRagLibrary(
            entries = entries,
            embeddingModel = embeddings.model,
            indexedCount = entries.count { it.embedding != null },
            updatedAt = entries.maxOfOrNull { it.updatedAt },
        )
    }

    @Transactional
    fun replace(examples: List<NiaFewShotExample>): ConversationRagLibrary {
        require(examples.isNotEmpty()) { "conversation_rag_requires_examples" }
        require(examples.size <= MAX_ENTRIES) { "conversation_rag_too_many_examples" }
        val searchTexts = examples.map(::canonicalScene)
        val vectors = embedOrNull(searchTexts)
        val now = Instant.now(clock)
        store.replaceAll(
            examples.mapIndexed { index, example ->
                val vector = vectors?.getOrNull(index)
                ConversationRagStoredEntry(
                    example = example.copy(id = null),
                    searchText = searchTexts[index],
                    embedding = vector,
                    embeddingModel = vector?.let { embeddings.model },
                    indexedAt = now,
                )
            },
        )
        return library()
    }

    @Transactional(readOnly = true)
    fun search(
        sceneText: String,
        limit: Int = DEFAULT_MATCH_COUNT,
    ): List<ConversationRagMatch> {
        val query = sceneText.trim()
        if (query.isBlank()) return emptyList()
        val entries = store.list()
        if (entries.isEmpty()) return emptyList()
        val queryVector = embedOrNull(listOf(query))?.singleOrNull()
        return entries
            .map { entry -> score(entry, query, queryVector) }
            .sortedWith(compareByDescending<ConversationRagMatch> { it.score }.thenBy { it.entry.id ?: Long.MAX_VALUE })
            .take(limit.coerceIn(1, MAX_MATCH_COUNT))
    }

    private fun score(
        entry: ConversationRagEntry,
        query: String,
        queryVector: FloatArray?,
    ): ConversationRagMatch {
        val entryVector = entry.embedding
        return if (queryVector != null && entryVector != null && queryVector.size == entryVector.size) {
            ConversationRagMatch(entry, cosine(queryVector, entryVector).coerceIn(0.0, 1.0), ConversationRagScoringMethod.EMBEDDING)
        } else {
            ConversationRagMatch(entry, textSimilarity(query, entry.searchText), ConversationRagScoringMethod.TEXT_FALLBACK)
        }
    }

    private fun embedOrNull(texts: List<String>): List<FloatArray>? {
        if (!embeddings.isConfigured()) return null
        return runCatching { embeddings.embed(texts.map { it.take(MAX_EMBEDDING_TEXT_CHARS) }) }
            .onFailure { error -> log.warn("conversation RAG embedding 실패 — text fallback 사용: {}", error::class.simpleName) }
            .getOrNull()
            ?.takeIf { it.size == texts.size }
    }

    companion object {
        const val DEFAULT_MATCH_COUNT = 2
        const val MAX_MATCH_COUNT = 4
        const val MAX_ENTRIES = 500
        private const val MAX_EMBEDDING_TEXT_CHARS = 12_000

        fun canonicalScene(example: NiaFewShotExample): String =
            buildString {
                appendLine(example.title)
                example.currentState?.let(::appendLine)
                example.rawMessages.forEach { appendLine("${it.authorRole}: ${it.text}") }
            }.trim()

        internal fun cosine(
            left: FloatArray,
            right: FloatArray,
        ): Double {
            if (left.size != right.size || left.isEmpty()) return 0.0
            var dot = 0.0
            var leftNorm = 0.0
            var rightNorm = 0.0
            left.indices.forEach { index ->
                dot += left[index] * right[index]
                leftNorm += left[index] * left[index]
                rightNorm += right[index] * right[index]
            }
            if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
            return (dot / (sqrt(leftNorm) * sqrt(rightNorm)) + 1.0) / 2.0
        }

        internal fun textSimilarity(
            left: String,
            right: String,
        ): Double {
            val leftTokens = characterBigrams(left)
            val rightTokens = characterBigrams(right)
            if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
            return leftTokens.intersect(rightTokens).size.toDouble() / leftTokens.union(rightTokens).size
        }

        private fun characterBigrams(value: String): Set<String> {
            val normalized = value.lowercase().replace(Regex("\\s+"), " ").trim()
            if (normalized.length < 2) return setOf(normalized).filter(String::isNotBlank).toSet()
            return normalized.windowed(2).toSet()
        }
    }
}

data class ConversationRagLibrary(
    val entries: List<ConversationRagEntry>,
    val embeddingModel: String,
    val indexedCount: Int,
    val updatedAt: Instant?,
)
