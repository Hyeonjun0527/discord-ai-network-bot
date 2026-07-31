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
