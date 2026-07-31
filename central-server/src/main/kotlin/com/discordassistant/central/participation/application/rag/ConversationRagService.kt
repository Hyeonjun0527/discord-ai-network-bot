package com.discordassistant.central.participation.application.rag

import com.discordassistant.central.global.error.NotFoundException
import com.discordassistant.central.participation.application.port.out.ConversationRagStorePort
import com.discordassistant.central.participation.application.port.out.ConversationRagStoredEntry
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.rag.ConversationRagEntry
import com.discordassistant.central.participation.domain.model.rag.ConversationRagMatch
import com.discordassistant.central.participation.domain.model.rag.ConversationRagScoringMethod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class ConversationRagService(
    private val store: ConversationRagStorePort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun library(): ConversationRagLibrary {
        val entries = store.list()
        return ConversationRagLibrary(
            entries = entries,
            embeddingModel = LOCAL_TEXT_SCORING_MODEL,
            indexedCount = entries.count { it.searchText.isNotBlank() },
            updatedAt = entries.maxOfOrNull { it.updatedAt },
        )
    }

    @Transactional(readOnly = true)
    fun entry(entryId: Long): ConversationRagEntry = store.find(entryId) ?: missingEntry(entryId)

    @Transactional(readOnly = true)
    fun page(
        query: String?,
        offset: Int,
        limit: Int,
    ): ConversationRagPage {
        require(offset >= 0) { "offset은 0 이상이어야 합니다" }
        require(limit in 1..MAX_PAGE_SIZE) { "limit은 1 이상 $MAX_PAGE_SIZE 이하여야 합니다" }
        val normalizedQuery = query?.trim()?.lowercase().orEmpty()
        val filtered =
            store
                .list()
                .filter { entry ->
                    val title = entry.example.title.lowercase()
                    val messagesContainQuery = entry.example.rawMessages.any { normalizedQuery in it.text.lowercase() }
                    normalizedQuery.isBlank() ||
                        normalizedQuery in title ||
                        messagesContainQuery
                }
        return ConversationRagPage(
            entries = filtered.drop(offset).take(limit),
            total = filtered.size,
            offset = offset,
            limit = limit,
        )
    }

    @Transactional
    fun create(example: NiaFewShotExample): ConversationRagEntry {
        require(store.list().size < MAX_ENTRIES) { "conversation_rag_too_many_examples" }
        return store.save(index(example.copy(id = null), id = null, createdAt = null))
    }

    @Transactional
    fun createAll(examples: List<NiaFewShotExample>): List<ConversationRagEntry> {
        require(examples.isNotEmpty()) { "conversation_rag_requires_examples" }
        require(store.list().size + examples.size <= MAX_ENTRIES) { "conversation_rag_too_many_examples" }
        val searchTexts = examples.map(::canonicalScene)
        val now = Instant.now(clock)
        return examples.mapIndexed { index, example ->
            store.save(
                ConversationRagStoredEntry(
                    example = example.copy(id = null),
                    searchText = searchTexts[index],
                    embedding = null,
                    embeddingModel = null,
                    indexedAt = now,
                ),
            )
        }
    }

    @Transactional
    fun update(
        entryId: Long,
        example: NiaFewShotExample,
    ): ConversationRagEntry {
        val existing = entry(entryId)
        return store.save(index(example.copy(id = null), id = entryId, createdAt = existing.createdAt))
    }

    @Transactional
    fun delete(entryId: Long) {
        if (!store.delete(entryId)) throw NotFoundException("대화 RAG 항목을 찾을 수 없습니다: $entryId")
    }

    @Transactional
    fun replace(examples: List<NiaFewShotExample>): ConversationRagLibrary {
        require(examples.isNotEmpty()) { "conversation_rag_requires_examples" }
        require(examples.size <= MAX_ENTRIES) { "conversation_rag_too_many_examples" }
        val searchTexts = examples.map(::canonicalScene)
        val now = Instant.now(clock)
        store.replaceAll(
            examples.mapIndexed { index, example ->
                ConversationRagStoredEntry(
                    example = example.copy(id = null),
                    searchText = searchTexts[index],
                    embedding = null,
                    embeddingModel = null,
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
        excludedCanonicalScenes: Set<String> = emptySet(),
    ): List<ConversationRagMatch> {
        val query = sceneText.trim()
        if (query.isBlank()) return emptyList()
        val entries = store.list().filterNot { canonicalScene(it.example) in excludedCanonicalScenes }
        if (entries.isEmpty()) return emptyList()
        return entries
            .map { entry ->
                ConversationRagMatch(
                    entry = entry,
                    score = textSimilarity(query, entry.searchText),
                    scoringMethod = ConversationRagScoringMethod.LOCAL_TEXT,
                )
            }.filter { match -> match.score >= MIN_LOCAL_TEXT_SCORE }
            .sortedWith(compareByDescending<ConversationRagMatch> { it.score }.thenBy { it.entry.id ?: Long.MAX_VALUE })
            .take(limit.coerceIn(1, MAX_MATCH_COUNT))
    }

    private fun index(
        example: NiaFewShotExample,
        id: Long?,
        createdAt: Instant?,
    ): ConversationRagStoredEntry {
        val searchText = canonicalScene(example)
        return ConversationRagStoredEntry(
            id = id,
            example = example,
            searchText = searchText,
            embedding = null,
            embeddingModel = null,
            indexedAt = Instant.now(clock),
            createdAt = createdAt,
        )
    }

    private fun missingEntry(entryId: Long): Nothing = throw NotFoundException("대화 RAG 항목을 찾을 수 없습니다: $entryId")

    companion object {
        const val DEFAULT_MATCH_COUNT = 2
        const val MAX_MATCH_COUNT = 4
        const val MAX_ENTRIES = 500
        const val MAX_PAGE_SIZE = 500
        const val LOCAL_TEXT_SCORING_MODEL = "local-text-hybrid-v1"
        private const val MIN_LOCAL_TEXT_SCORE = 0.08

        fun canonicalScene(example: NiaFewShotExample): String =
            buildString {
                appendLine(example.title)
                example.currentState?.let(::appendLine)
                example.rawMessages.forEach { appendLine("${it.authorRole}: ${it.text}") }
            }.trim()

        internal fun textSimilarity(
            left: String,
            right: String,
        ): Double {
            val characterScore = jaccard(characterBigrams(left), characterBigrams(right))
            val wordScore = jaccard(wordTokens(left), wordTokens(right))
            return (characterScore * 0.7 + wordScore * 0.3).coerceIn(0.0, 1.0)
        }

        private fun jaccard(
            left: Set<String>,
            right: Set<String>,
        ): Double =
            if (left.isEmpty() || right.isEmpty()) {
                0.0
            } else {
                left.intersect(right).size.toDouble() / left.union(right).size
            }

        private fun characterBigrams(value: String): Set<String> {
            val normalized = value.lowercase().replace(Regex("\\s+"), " ").trim()
            if (normalized.length < 2) return setOf(normalized).filter(String::isNotBlank).toSet()
            return normalized.windowed(2).toSet()
        }

        private fun wordTokens(value: String): Set<String> =
            value
                .lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 2 }
                .toSet()
    }
}

data class ConversationRagLibrary(
    val entries: List<ConversationRagEntry>,
    val embeddingModel: String,
    val indexedCount: Int,
    val updatedAt: Instant?,
)

data class ConversationRagPage(
    val entries: List<ConversationRagEntry>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)
