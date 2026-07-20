package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.ConversationRagStorePort
import com.discordassistant.central.participation.application.port.out.ConversationRagStoredEntry
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.rag.ConversationRagEntry
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class JpaConversationRagStore(
    private val repository: ConversationRagEntryRepository,
) : ConversationRagStorePort {
    private val mapper = jacksonObjectMapper()

    @Transactional(readOnly = true)
    override fun list(): List<ConversationRagEntry> = repository.findAllByOrderByIdAsc().map(::toDomain)

    @Transactional(readOnly = true)
    override fun find(entryId: Long): ConversationRagEntry? = repository.findById(entryId).orElse(null)?.let(::toDomain)

    @Transactional
    override fun save(entry: ConversationRagStoredEntry): ConversationRagEntry = toDomain(repository.save(toEntity(entry)))

    @Transactional
    override fun delete(entryId: Long): Boolean {
        if (!repository.existsById(entryId)) return false
        repository.deleteById(entryId)
        return true
    }

    @Transactional
    override fun replaceAll(entries: List<ConversationRagStoredEntry>): List<ConversationRagEntry> {
        repository.deleteAllInBatch()
        return repository
            .saveAll(entries.map(::toEntity))
            .map(::toDomain)
    }

    private fun toEntity(entry: ConversationRagStoredEntry): ConversationRagEntryEntity =
        ConversationRagEntryEntity(
            id = entry.id,
            exampleJson = mapper.writeValueAsString(entry.example.copy(id = null)),
            searchText = entry.searchText,
            embeddingJson = entry.embedding?.joinToString(separator = ","),
            embeddingModel = entry.embeddingModel,
            createdAt = entry.createdAt ?: entry.indexedAt,
            updatedAt = entry.indexedAt,
        )

    private fun toDomain(entity: ConversationRagEntryEntity): ConversationRagEntry =
        ConversationRagEntry(
            id = entity.id,
            example = mapper.readValue<NiaFewShotExample>(entity.exampleJson).copy(id = entity.id),
            searchText = entity.searchText,
            embedding =
                entity.embeddingJson
                    ?.split(',')
                    ?.map { it.toFloat() }
                    ?.toFloatArray(),
            embeddingModel = entity.embeddingModel,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
}

@Entity
@Table(name = "nia_conversation_rag_entry")
class ConversationRagEntryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "example_json", nullable = false, columnDefinition = "TEXT")
    var exampleJson: String = "",
    @Column(name = "search_text", nullable = false, columnDefinition = "TEXT")
    var searchText: String = "",
    @Column(name = "embedding_json", columnDefinition = "TEXT")
    var embeddingJson: String? = null,
    @Column(name = "embedding_model", length = 96)
    var embeddingModel: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

interface ConversationRagEntryRepository : JpaRepository<ConversationRagEntryEntity, Long> {
    fun findAllByOrderByIdAsc(): List<ConversationRagEntryEntity>
}
