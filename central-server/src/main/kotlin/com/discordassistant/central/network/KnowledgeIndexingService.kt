package com.discordassistant.central.network

import com.discordassistant.central.persistence.EmbeddingIndexJobEntity
import com.discordassistant.central.persistence.EmbeddingIndexJobRepository
import com.discordassistant.central.persistence.KnowledgeChunkEntity
import com.discordassistant.central.persistence.KnowledgeChunkRepository
import com.discordassistant.central.persistence.KnowledgeDocumentEntity
import com.discordassistant.central.persistence.KnowledgeDocumentRepository
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.RetrievalPolicyEntity
import com.discordassistant.central.persistence.RetrievalPolicyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

@Service
class KnowledgeIndexingService(
    private val spaces: KnowledgeSpaceRepository,
    private val sources: KnowledgeSourceRepository,
    private val documents: KnowledgeDocumentRepository,
    private val chunks: KnowledgeChunkRepository,
    private val jobs: EmbeddingIndexJobRepository,
    private val retrievalPolicies: RetrievalPolicyRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    @Transactional
    fun parseSourceToDocument(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        documentText: String,
        title: String? = null,
        channelId: Long? = null,
    ): KnowledgeDocumentEntity {
        featureGate.requireRagEnabled()
        val now = Instant.now(clock)
        val space = spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found")
        val source = sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId) ?: error("knowledge source not found")
        require(source.guildId == guildId) { "cross-guild knowledge source is not allowed" }
        require(!source.status.startsWith("deleted")) { "deleted knowledge source cannot be parsed" }
        val clean = documentText.trim()
        require(clean.isNotBlank()) { "document text is required" }
        supersedeExistingSourceIndex(source.id)
        val doc =
            documents.save(
                KnowledgeDocumentEntity(
                    knowledgeSpaceId = space.id,
                    knowledgeSourceId = source.id,
                    guildId = guildId,
                    channelId = channelId ?: space.channelId,
                    title = title?.trim()?.ifBlank { null } ?: source.title,
                    documentType = source.sourceType.ifBlank { "text" },
                    contentHash = sha256(clean),
                    tokenEstimate = estimateTokens(clean),
                    status = "parsed",
                    parsedAt = now,
                ),
            )
        val chunkEntities =
            splitChunks(clean).mapIndexed { index, chunkText ->
                KnowledgeChunkEntity(
                    knowledgeSpaceId = space.id,
                    knowledgeDocumentId = doc.id,
                    knowledgeSourceId = source.id,
                    guildId = guildId,
                    channelId = doc.channelId,
                    chunkIndex = index + 1,
                    title = doc.title,
                    contentPreview = chunkText.take(2000),
                    embeddingTextHash = sha256("${doc.title}\n$chunkText"),
                    tokenEstimate = estimateTokens(chunkText),
                    qdrantPointId = "ks-${space.id}-doc-${doc.id}-chunk-${index + 1}",
                    status = "ready",
                    createdAt = now,
                )
            }
        chunks.saveAll(chunkEntities)
        space.chunkCount = chunks.findByKnowledgeSpaceIdAndStatus(space.id, "ready").size
        space.updatedAt = now
        spaces.save(space)
        source.contentHash = doc.contentHash
        sources.save(source)
        return doc
    }

    @Transactional
    fun queueIndexJob(
        guildId: Long,
        spaceId: Long,
        triggeredBy: Long?,
        collectionName: String = "discord_ai_network",
        embeddingModel: String = "text-embedding-3-large",
    ): EmbeddingIndexJobEntity {
        featureGate.requireRagEnabled()
        val space = spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found")
        val sourceCount = sources.findByKnowledgeSpaceId(space.id).count { !it.status.startsWith("deleted") }
        val chunkCount = chunks.findByKnowledgeSpaceIdAndStatus(space.id, "ready").size
        return jobs.save(
            EmbeddingIndexJobEntity(
                guildId = guildId,
                knowledgeSpaceId = space.id,
                triggeredBy = triggeredBy,
                jobType = "rebuild",
                status = "queued",
                collectionName = collectionName,
                embeddingModel = embeddingModel,
                sourceCount = sourceCount,
                chunkCount = chunkCount,
                queuedAt = Instant.now(clock),
            ),
        )
    }

    @Transactional
    fun completeIndexJob(
        guildId: Long,
        jobId: Long,
        status: String,
        failureReason: String? = null,
    ): EmbeddingIndexJobEntity {
        featureGate.requireRagEnabled()
        val job = jobs.findById(jobId).orElseThrow { IllegalArgumentException("index job not found") }
        require(job.guildId == guildId) { "cross-guild index job update is not allowed" }
        val now = Instant.now(clock)
        if (job.startedAt == null) job.startedAt = job.queuedAt
        job.status = status.trim().ifBlank { "completed" }
        job.failureReason = failureReason?.trim()?.take(500)?.ifBlank { null }
        job.finishedAt = now
        return jobs.save(job)
    }

    @Transactional
    fun saveRetrievalPolicy(
        guildId: Long,
        channelId: Long?,
        knowledgeSpaceId: Long?,
        topK: Int,
        tokenBudget: Int,
        rerankEnabled: Boolean,
        sourcePriority: List<String>,
    ): RetrievalPolicyEntity {
        featureGate.requireRagEnabled()
        knowledgeSpaceId?.let { spaces.findByGuildIdAndId(guildId, it) ?: error("knowledge space not found") }
        val now = Instant.now(clock)
        val existing =
            retrievalPolicies.findByGuildIdAndChannelIdAndKnowledgeSpaceIdAndStatus(
                guildId = guildId,
                channelId = channelId,
                knowledgeSpaceId = knowledgeSpaceId,
                status = "active",
            )
        val entity =
            existing
                ?: RetrievalPolicyEntity(
                    guildId = guildId,
                    channelId = channelId,
                    knowledgeSpaceId = knowledgeSpaceId,
                    createdAt = now,
                )
        entity.topK = topK.coerceIn(1, 20)
        entity.tokenBudget = tokenBudget.coerceIn(256, 8000)
        entity.rerankEnabled = rerankEnabled
        entity.sourcePriority = sourcePriority.joinToString(",") { it.trim() }.take(500).ifBlank { null }
        entity.status = "active"
        entity.updatedAt = now
        return retrievalPolicies.save(entity)
    }

    fun readyChunks(
        guildId: Long,
        spaceId: Long,
    ): List<KnowledgeChunkEntity> {
        featureGate.requireRagEnabled()
        spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found")
        return chunks.findByKnowledgeSpaceIdAndStatus(spaceId, "ready").filter { it.guildId == guildId }
    }

    @Transactional
    fun indexInlineSourceIfPossible(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        documentText: String?,
        triggeredBy: Long?,
    ): InlineKnowledgeIndexingResult {
        featureGate.requireRagEnabled()
        val text = documentText?.trim()
        if (text.isNullOrBlank()) {
            return InlineKnowledgeIndexingResult(sourceId = sourceId, indexed = false, skippedReason = "content_preview_required")
        }
        val source =
            sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found")
        require(source.guildId == guildId) { "cross-guild knowledge source is not allowed" }
        if (source.sourceType !in INLINE_INDEXABLE_SOURCE_TYPES) {
            return InlineKnowledgeIndexingResult(sourceId = source.id, indexed = false, skippedReason = "source_type_not_inline")
        }
        if (source.status != "pending") {
            return InlineKnowledgeIndexingResult(sourceId = source.id, indexed = false, skippedReason = "source_not_pending")
        }
        val document =
            parseSourceToDocument(
                guildId = guildId,
                spaceId = spaceId,
                sourceId = source.id,
                documentText = text,
                title = source.title,
            )
        val now = Instant.now(clock)
        val indexedSource = sources.findByKnowledgeSpaceIdAndId(spaceId, source.id) ?: source
        indexedSource.status = "indexed"
        indexedSource.indexedAt = now
        sources.save(indexedSource)
        val space = spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found")
        val chunkCount = chunks.findByKnowledgeSpaceIdAndStatus(space.id, "ready").size
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).count { !it.status.startsWith("deleted") }
        space.chunkCount = chunkCount
        space.status = "ready"
        space.updatedAt = now
        spaces.save(space)
        val job = queueIndexJob(guildId, space.id, triggeredBy = triggeredBy)
        return InlineKnowledgeIndexingResult(
            sourceId = indexedSource.id,
            indexed = true,
            skippedReason = null,
            documentId = document.id,
            jobId = job.id,
            chunkCount = chunkCount,
        )
    }

    private fun supersedeExistingSourceIndex(sourceId: Long) {
        val existingDocs = documents.findByKnowledgeSourceId(sourceId)
        if (existingDocs.isEmpty()) return
        val existingChunks =
            existingDocs.flatMap { doc ->
                chunks.findByKnowledgeDocumentIdOrderByChunkIndex(doc.id)
            }
        existingChunks
            .filter { it.status == "ready" }
            .forEach { it.status = "superseded" }
        existingDocs
            .filter { it.status == "parsed" }
            .forEach { it.status = "superseded" }
        chunks.saveAll(existingChunks)
        documents.saveAll(existingDocs)
    }

    private fun splitChunks(text: String): List<String> =
        text
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(text.take(2000)) }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val INLINE_INDEXABLE_SOURCE_TYPES = setOf("text", "faq", "constitution", "preset")
    }
}

data class InlineKnowledgeIndexingResult(
    val sourceId: Long,
    val indexed: Boolean,
    val skippedReason: String?,
    val documentId: Long? = null,
    val jobId: Long? = null,
    val chunkCount: Int = 0,
)
