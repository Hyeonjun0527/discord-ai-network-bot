package com.discordassistant.central.participation.domain.model.rag

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import java.time.Instant

data class ConversationRagEntry(
    val id: Long?,
    val example: NiaFewShotExample,
    val searchText: String,
    val embedding: FloatArray?,
    val embeddingModel: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        id?.let { require(it > 0) { "conversation RAG id 는 양수여야 한다" } }
        require(searchText.isNotBlank()) { "conversation RAG searchText 는 비어 있을 수 없다" }
        embedding?.let { require(it.isNotEmpty()) { "conversation RAG embedding 은 비어 있을 수 없다" } }
        require((embedding == null) == (embeddingModel == null)) {
            "conversation RAG embedding 과 model 은 함께 있어야 한다"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ConversationRagEntry &&
            id == other.id &&
            example == other.example &&
            searchText == other.searchText &&
            embedding.contentEqualsNullable(other.embedding) &&
            embeddingModel == other.embeddingModel &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt

    override fun hashCode(): Int =
        listOf(id, example, searchText, embedding?.contentHashCode(), embeddingModel, createdAt, updatedAt).hashCode()
}

data class ConversationRagMatch(
    val entry: ConversationRagEntry,
    val score: Double,
    val scoringMethod: ConversationRagScoringMethod,
) {
    init {
        require(score in 0.0..1.0) { "conversation RAG score 는 [0,1] 범위여야 한다: $score" }
    }
}

enum class ConversationRagScoringMethod {
    EMBEDDING,
    TEXT_FALLBACK,
}

private fun FloatArray?.contentEqualsNullable(other: FloatArray?): Boolean =
    when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }
