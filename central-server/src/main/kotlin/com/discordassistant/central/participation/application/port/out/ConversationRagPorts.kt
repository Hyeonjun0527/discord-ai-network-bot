package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.domain.model.rag.ConversationRagEntry
import java.time.Instant

interface ConversationRagStorePort {
    fun list(): List<ConversationRagEntry>

    fun find(entryId: Long): ConversationRagEntry?

    fun save(entry: ConversationRagStoredEntry): ConversationRagEntry

    fun delete(entryId: Long): Boolean

    fun replaceAll(entries: List<ConversationRagStoredEntry>): List<ConversationRagEntry>
}

data class ConversationRagStoredEntry(
    val id: Long? = null,
    val example: com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample,
    val searchText: String,
    val embedding: FloatArray?,
    val embeddingModel: String?,
    val indexedAt: Instant,
    val createdAt: Instant? = null,
)

interface ConversationEmbeddingPort {
    val model: String

    fun isConfigured(): Boolean

    fun embed(texts: List<String>): List<FloatArray>
}

/** Conversation RAG embedding API가 보고한 실제 입력 토큰을 원문 없이 관측한다. */
fun interface ConversationEmbeddingUsageObserver {
    /** 실제 embedding HTTP 요청 직전에 호출한다. */
    fun recordAttempt(model: String) = Unit

    /** 원문 없이 직렬화된 embedding payload 크기까지 함께 기록한다. */
    fun recordAttempt(
        model: String,
        requestPayloadChars: Int,
    ) = recordAttempt(model)

    fun record(
        model: String,
        promptTokens: Int,
    )

    companion object {
        val NOOP = ConversationEmbeddingUsageObserver { _, _ -> }
    }
}
