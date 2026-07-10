package com.discordassistant.central.knowledge.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.knowledge.adapter.outbound.persistence.EmbeddingIndexJobEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.EmbeddingIndexJobRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeChunkEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeChunkRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeDocumentEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeDocumentRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.RetrievalPolicyEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.RetrievalPolicyRepository
import com.discordassistant.central.knowledge.domain.model.EmbeddingJobStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeChunkStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeDocumentStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSpaceStatus
import com.discordassistant.central.knowledge.domain.model.RetrievalPolicyStatus
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
        val space = spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId) ?: error("knowledge source not found: space=$spaceId source=$sourceId")
        require(source.guildId == guildId) { "cross-guild knowledge source is not allowed" }
        require(!source.status.isDeleted) { "deleted knowledge source cannot be parsed" }
        val clean = documentText.trim()
        require(clean.isNotBlank()) { "document text is required" }
        if (KnowledgeSafety.containsSensitiveMaterial(clean)) {
            source.transitionTo(KnowledgeSourceStatus.BLOCKED_SENSITIVE)
            source.riskLevel = "sensitive"
            source.contentHash = sha256(clean)
            sources.save(source)
            throw IllegalArgumentException("sensitive knowledge document cannot be indexed")
        }
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
                    status = KnowledgeDocumentStatus.PARSED,
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
                    status = KnowledgeChunkStatus.READY,
                    createdAt = now,
                )
            }
        chunks.saveAll(chunkEntities)
        space.chunkCount = chunks.findByKnowledgeSpaceIdAndStatus(space.id, KnowledgeChunkStatus.READY).size
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
        jobType: String = "rebuild",
    ): EmbeddingIndexJobEntity {
        featureGate.requireRagEnabled()
        val space = spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found: guild=$guildId space=$spaceId")
        val sourceCount = sources.findByKnowledgeSpaceId(space.id).count { !it.status.isDeleted }
        val chunkCount = chunks.findByKnowledgeSpaceIdAndStatus(space.id, KnowledgeChunkStatus.READY).size
        return jobs.save(
            EmbeddingIndexJobEntity(
                guildId = guildId,
                knowledgeSpaceId = space.id,
                triggeredBy = triggeredBy,
                jobType = jobType.trim().ifBlank { "rebuild" },
                status = EmbeddingJobStatus.QUEUED,
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
        status: EmbeddingJobStatus,
        failureReason: String? = null,
    ): EmbeddingIndexJobEntity {
        featureGate.requireRagEnabled()
        val job = jobs.findById(jobId).orElseThrow { IllegalArgumentException("index job not found: jobId=$jobId guild=$guildId") }
        require(job.guildId == guildId) { "cross-guild index job update is not allowed" }
        val now = Instant.now(clock)
        if (job.startedAt == null) job.startedAt = job.queuedAt
        job.status = status
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
        knowledgeSpaceId?.let { spaces.findByGuildIdAndId(guildId, it) ?: error("knowledge space not found: guild=$guildId space=$it") }
        val now = Instant.now(clock)
        val existing =
            retrievalPolicies.findByGuildIdAndChannelIdAndKnowledgeSpaceIdAndStatus(
                guildId = guildId,
                channelId = channelId,
                knowledgeSpaceId = knowledgeSpaceId,
                status = RetrievalPolicyStatus.ACTIVE,
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
        entity.status = RetrievalPolicyStatus.ACTIVE
        entity.updatedAt = now
        return retrievalPolicies.save(entity)
    }

    fun readyChunks(
        guildId: Long,
        spaceId: Long,
    ): List<KnowledgeChunkEntity> {
        featureGate.requireRagEnabled()
        spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found: guild=$guildId space=$spaceId")
        return chunks.findByKnowledgeSpaceIdAndStatus(spaceId, KnowledgeChunkStatus.READY).filter { it.guildId == guildId }
    }

    fun listIndexJobs(
        guildId: Long,
        spaceId: Long? = null,
        limit: Int = 10,
    ): List<KnowledgeIndexJobSummary> {
        featureGate.requireRagEnabled()
        val rows =
            if (spaceId == null) {
                jobs.findTop20ByGuildIdOrderByQueuedAtDesc(guildId)
            } else {
                spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found: guild=$guildId space=$spaceId")
                jobs.findTop10ByGuildIdAndKnowledgeSpaceIdOrderByQueuedAtDesc(guildId, spaceId)
            }
        return rows
            .take(limit.coerceIn(1, 20))
            .map { it.toSummary() }
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
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        require(source.guildId == guildId) { "cross-guild knowledge source is not allowed" }
        if (source.sourceType !in INLINE_INDEXABLE_SOURCE_TYPES) {
            return InlineKnowledgeIndexingResult(sourceId = source.id, indexed = false, skippedReason = "source_type_not_inline")
        }
        if (!source.status.isPending) {
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
        indexedSource.transitionTo(KnowledgeSourceStatus.INDEXED)
        indexedSource.indexedAt = now
        sources.save(indexedSource)
        val space = spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found: guild=$guildId space=$spaceId")
        val chunkCount = chunks.findByKnowledgeSpaceIdAndStatus(space.id, KnowledgeChunkStatus.READY).size
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).count { !it.status.isDeleted }
        space.chunkCount = chunkCount
        space.transitionTo(KnowledgeSpaceStatus.READY)
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

    @Transactional
    fun queueRebuildJob(
        guildId: Long,
        spaceId: Long,
        triggeredBy: Long?,
    ): KnowledgeIndexJobSummary {
        featureGate.requireRagEnabled()
        val space = spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found: guild=$guildId space=$spaceId")
        val collection = space.indexName?.trim()?.ifBlank { null } ?: "discord_ai__guild_${guildId}__space_${space.id}"
        val embeddingModel = space.embeddingModel?.trim()?.ifBlank { null } ?: "text-embedding-3-large"
        return queueIndexJob(
            guildId = guildId,
            spaceId = space.id,
            triggeredBy = triggeredBy,
            collectionName = collection,
            embeddingModel = embeddingModel,
        ).toSummary()
    }

    @Transactional
    fun tombstoneDeletedSourceIndex(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        triggeredBy: Long?,
    ): KnowledgeSourceDeletionIndexResult {
        featureGate.requireRagEnabled()
        val space = spaces.findByGuildIdAndId(guildId, spaceId) ?: error("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(space.id, sourceId)
                ?: error("knowledge source not found: space=${space.id} source=$sourceId")
        require(source.guildId == guildId) { "cross-guild knowledge source is not allowed" }
        require(source.status.isDeleted) { "source must be deleted before index tombstone: ${source.status.wire}" }
        val docs = documents.findByKnowledgeSourceId(source.id)
        val sourceChunks =
            docs.flatMap { doc ->
                chunks.findByKnowledgeDocumentIdOrderByChunkIndex(doc.id)
            }
        val tombstonedDocs = docs.count { it.status != KnowledgeDocumentStatus.DELETED }
        val tombstonedChunks = sourceChunks.count { it.status != KnowledgeChunkStatus.DELETED }
        docs.filter { it.status != KnowledgeDocumentStatus.DELETED }.forEach { it.transitionTo(KnowledgeDocumentStatus.DELETED) }
        sourceChunks.filter { it.status != KnowledgeChunkStatus.DELETED }.forEach { it.transitionTo(KnowledgeChunkStatus.DELETED) }
        if (sourceChunks.isNotEmpty()) chunks.saveAll(sourceChunks)
        if (docs.isNotEmpty()) documents.saveAll(docs)
        val activeSources = sources.findByKnowledgeSpaceId(space.id).filter { !it.status.isDeleted }
        space.sourceCount = activeSources.size
        space.chunkCount = chunks.findByKnowledgeSpaceIdAndStatus(space.id, KnowledgeChunkStatus.READY).size
        space.status = statusAfterDeletion(activeSources)
        space.updatedAt = Instant.now(clock)
        spaces.save(space)
        val job =
            queueIndexJob(
                guildId = guildId,
                spaceId = space.id,
                triggeredBy = triggeredBy,
                collectionName = space.indexName?.trim()?.ifBlank { null } ?: "discord_ai__guild_${guildId}__space_${space.id}",
                embeddingModel = space.embeddingModel?.trim()?.ifBlank { null } ?: "text-embedding-3-large",
                jobType = "delete_source",
            )
        return KnowledgeSourceDeletionIndexResult(
            sourceId = source.id,
            jobId = job.id,
            tombstonedDocumentCount = tombstonedDocs,
            tombstonedChunkCount = tombstonedChunks,
            remainingReadyChunkCount = space.chunkCount,
        )
    }

    @Transactional
    fun completeIndexJobSafely(
        guildId: Long,
        jobId: Long,
        status: String,
        failureReason: String? = null,
    ): KnowledgeIndexJobSummary {
        val normalizedStatus =
            when (status.trim().lowercase()) {
                "", "done", "success", "completed", "complete" -> EmbeddingJobStatus.COMPLETED
                "failed", "failure", "error" -> EmbeddingJobStatus.FAILED
                "cancelled", "canceled", "cancel" -> EmbeddingJobStatus.CANCELLED
                else -> EmbeddingJobStatus.FAILED
            }
        return completeIndexJob(
            guildId = guildId,
            jobId = jobId,
            status = normalizedStatus,
            failureReason = failureReason,
        ).toSummary()
    }

    private fun supersedeExistingSourceIndex(sourceId: Long) {
        val existingDocs = documents.findByKnowledgeSourceId(sourceId)
        if (existingDocs.isEmpty()) return
        val existingChunks =
            existingDocs.flatMap { doc ->
                chunks.findByKnowledgeDocumentIdOrderByChunkIndex(doc.id)
            }
        existingChunks
            .filter { it.status == KnowledgeChunkStatus.READY }
            .forEach { it.transitionTo(KnowledgeChunkStatus.SUPERSEDED) }
        existingDocs
            .filter { it.status == KnowledgeDocumentStatus.PARSED }
            .forEach { it.transitionTo(KnowledgeDocumentStatus.SUPERSEDED) }
        chunks.saveAll(existingChunks)
        documents.saveAll(existingDocs)
    }

    private fun statusAfterDeletion(activeSources: List<KnowledgeSourceEntity>): KnowledgeSpaceStatus =
        when {
            activeSources.isEmpty() -> KnowledgeSpaceStatus.DRAFT
            activeSources.any { it.status.isBlocked || it.riskLevel in BLOCKING_RISK_LEVELS } -> KnowledgeSpaceStatus.NEEDS_REVIEW
            activeSources.any { it.status.isPending } -> KnowledgeSpaceStatus.PENDING_INDEX
            activeSources.any { it.status.isIndexed } -> KnowledgeSpaceStatus.READY
            else -> KnowledgeSpaceStatus.NEEDS_REVIEW
        }

    private fun splitChunks(text: String): List<String> =
        text
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(text.trim()) }
            .flatMap { enforceMaxLength(it) }

    /**
     * 미리보기/검색 한도([CHUNK_MAX_CHARS])를 넘는 청크를 더 잘게 나눈다. 빈 줄 문단 경계가 없어 하나의
     * 큰 청크가 되면 [contentPreview] 가 잘려 나머지가 영구히 검색 불가가 되므로, 문장→공백→하드캡 순으로
     * 잘라 모든 내용이 색인되게 한다. 한도 이하 청크는 그대로 반환해 기존 청크 해싱/개수 의미를 보존한다.
     */
    private fun enforceMaxLength(chunk: String): List<String> {
        if (chunk.length <= CHUNK_MAX_CHARS) return listOf(chunk)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < chunk.length) {
            val end = (start + CHUNK_MAX_CHARS).coerceAtMost(chunk.length)
            val window = chunk.substring(start, end)
            val splitLen =
                if (end < chunk.length) {
                    (window.lastIndexOfAny(SENTENCE_BOUNDARY) + 1).takeIf { it > CHUNK_MIN_SPLIT }
                        ?: (window.lastIndexOf(' ') + 1).takeIf { it > CHUNK_MIN_SPLIT }
                        ?: window.length
                } else {
                    window.length
                }
            chunk
                .substring(start, start + splitLen)
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { parts += it }
            start += splitLen
        }
        return parts.ifEmpty { listOf(chunk.take(CHUNK_MAX_CHARS)) }
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun EmbeddingIndexJobEntity.toSummary(): KnowledgeIndexJobSummary =
        KnowledgeIndexJobSummary(
            id = id,
            guildId = guildId,
            knowledgeSpaceId = knowledgeSpaceId,
            triggeredBy = triggeredBy,
            jobType = jobType,
            status = status.wire,
            collectionName = collectionName,
            embeddingModel = embeddingModel,
            sourceCount = sourceCount,
            chunkCount = chunkCount,
            failureReason = failureReason,
            queuedAt = queuedAt.toString(),
            startedAt = startedAt?.toString(),
            finishedAt = finishedAt?.toString(),
        )

    private companion object {
        val INLINE_INDEXABLE_SOURCE_TYPES = setOf("text", "faq", "constitution", "preset")
        val BLOCKING_RISK_LEVELS = setOf("sensitive", "ssrf")

        /** 청크 미리보기/검색 한도(contentPreview take 값과 일치) — 이 길이를 넘으면 더 잘게 나눈다. */
        const val CHUNK_MAX_CHARS = 2000

        /** 이 위치 미만에서 발견된 문장/공백 경계는 무시(너무 작은 조각 방지, 진행성 보장은 하드캡). */
        const val CHUNK_MIN_SPLIT = 200
        val SENTENCE_BOUNDARY = charArrayOf('.', '!', '?', '\n', '。', '！', '？')
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

data class KnowledgeIndexJobSummary(
    val id: Long,
    val guildId: Long,
    val knowledgeSpaceId: Long,
    val triggeredBy: Long?,
    val jobType: String,
    val status: String,
    val collectionName: String,
    val embeddingModel: String,
    val sourceCount: Int,
    val chunkCount: Int,
    val failureReason: String?,
    val queuedAt: String,
    val startedAt: String?,
    val finishedAt: String?,
)

data class KnowledgeSourceDeletionIndexResult(
    val sourceId: Long,
    val jobId: Long,
    val tombstonedDocumentCount: Int,
    val tombstonedChunkCount: Int,
    val remainingReadyChunkCount: Int,
)
