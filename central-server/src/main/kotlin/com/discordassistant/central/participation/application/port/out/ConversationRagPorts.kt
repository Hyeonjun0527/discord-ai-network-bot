package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.domain.model.rag.ConversationRagEntry
import java.time.Instant

interface ConversationRagStorePort {
    fun list(): List<ConversationRagEntry>

    fun replaceAll(entries: List<ConversationRagStoredEntry>): List<ConversationRagEntry>
}

data class ConversationRagStoredEntry(
    val example: com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample,
    val searchText: String,
    val embedding: FloatArray?,
    val embeddingModel: String?,
    val indexedAt: Instant,
)

interface ConversationEmbeddingPort {
    val model: String

    fun isConfigured(): Boolean

    fun embed(texts: List<String>): List<FloatArray>
}
