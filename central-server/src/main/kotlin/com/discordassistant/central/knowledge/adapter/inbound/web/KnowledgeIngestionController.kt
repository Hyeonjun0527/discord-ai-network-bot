package com.discordassistant.central.knowledge.adapter.inbound.web

import com.discordassistant.central.knowledge.application.KnowledgeGoldenCase
import com.discordassistant.central.knowledge.application.KnowledgeIndexingService
import com.discordassistant.central.knowledge.application.KnowledgeIngestionService
import com.discordassistant.central.knowledge.application.KnowledgeSearchService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 채널별 RAG 지식공간 관리 API. 텍스트/FAQ/헌법/프리셋 본문은 즉시 chunk 색인하고 embedding 재빌드 작업을 큐잉한다. */
@RestController
@RequestMapping("/api/ai-network/knowledge")
class KnowledgeIngestionController(
    private val ingestion: KnowledgeIngestionService,
    private val search: KnowledgeSearchService,
    private val indexing: KnowledgeIndexingService? = null,
) {
    @GetMapping("/{guildId}/readiness")
    fun guildReadiness(
        @PathVariable guildId: Long,
    ) = ingestion.guildReadiness(guildId)

    @GetMapping("/{guildId}/quality-summary")
    fun qualitySummary(
        @PathVariable guildId: Long,
    ) = ingestion.qualitySummary(guildId)

    @GetMapping("/{guildId}/indexing-operations")
    fun indexingOperations(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "false") force: Boolean = false,
    ) = ingestion.indexingOperations(guildId, force)

    @GetMapping("/{guildId}/index-jobs")
    fun indexJobs(
        @PathVariable guildId: Long,
        @RequestParam(required = false) spaceId: Long? = null,
        @RequestParam(defaultValue = "10") limit: Int = 10,
    ) = indexingService().listIndexJobs(guildId, spaceId, limit)

    @PostMapping("/{guildId}/spaces/{spaceId}/index-jobs")
    fun queueIndexJob(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @RequestBody request: QueueKnowledgeIndexJobRequest,
    ) = indexingService().queueRebuildJob(guildId, spaceId, request.actorUserId)

    @PostMapping("/{guildId}/index-jobs/{jobId}/complete")
    fun completeIndexJob(
        @PathVariable guildId: Long,
        @PathVariable jobId: Long,
        @RequestBody request: CompleteKnowledgeIndexJobRequest,
    ) = indexingService().completeIndexJobSafely(guildId, jobId, request.status, request.reason)

    private fun indexingService(): KnowledgeIndexingService =
        indexing ?: throw IllegalStateException("knowledge indexing service is not configured")

    @PostMapping("/{guildId}/spaces")
    fun createSpace(
        @PathVariable guildId: Long,
        @RequestBody request: CreateKnowledgeSpaceRequest,
    ): Map<String, Any?> {
        val space =
            ingestion.createSpace(
                guildId = guildId,
                channelId = request.channelId,
                channelAiId = request.channelAiId,
                displayName = request.displayName,
                createdBy = request.actorUserId,
                embeddingModel = request.embeddingModel,
                indexName = request.indexName,
            )
        return mapOf("id" to space.id, "status" to space.status, "displayName" to space.displayName)
    }

    @GetMapping("/{guildId}/spaces/{spaceId}/sources")
    fun listSources(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
    ) = ingestion.listSources(guildId, spaceId)

    @GetMapping("/{guildId}/spaces/{spaceId}/status")
    fun spaceStatus(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
    ) = ingestion.spaceStatus(guildId, spaceId)

    @GetMapping("/{guildId}/spaces/{spaceId}/indexing-plan")
    fun indexingPlan(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @RequestParam(defaultValue = "false") force: Boolean = false,
    ) = ingestion.indexingPlan(guildId, spaceId, force)

    @PostMapping("/{guildId}/spaces/{spaceId}/sources")
    fun addSource(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @RequestBody request: AddKnowledgeSourceRequest,
    ): Map<String, Any?> {
        val source =
            ingestion.addSource(
                guildId = guildId,
                spaceId = spaceId,
                sourceType = request.sourceType,
                title = request.title,
                sourceUri = request.sourceUri,
                contentPreview = request.contentPreview,
                addedBy = request.actorUserId,
            )
        val inlineIndexing =
            indexing?.indexInlineSourceIfPossible(
                guildId = guildId,
                spaceId = spaceId,
                sourceId = source.id,
                documentText = request.contentPreview,
                triggeredBy = request.actorUserId,
            )
        val effectiveStatus = if (inlineIndexing?.indexed == true) "indexed" else source.status
        return mapOf(
            "id" to source.id,
            "status" to effectiveStatus,
            "riskLevel" to source.riskLevel,
            "inlineIndexed" to (inlineIndexing?.indexed ?: false),
            "indexSkippedReason" to inlineIndexing?.skippedReason,
            "documentId" to inlineIndexing?.documentId,
            "indexJobId" to inlineIndexing?.jobId,
            "chunkCount" to (inlineIndexing?.chunkCount ?: 0),
        )
    }

    @PostMapping("/{guildId}/spaces/{spaceId}/sources/{sourceId}/approve")
    fun approveSource(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @PathVariable sourceId: Long,
        @RequestBody request: ApproveKnowledgeSourceRequest,
    ): Map<String, Any?> {
        val source = ingestion.approveSourceForIndexing(guildId, spaceId, sourceId, request.reason)
        return mapOf("id" to source.id, "status" to source.status, "riskLevel" to source.riskLevel)
    }

    @PostMapping("/{guildId}/spaces/{spaceId}/sources/{sourceId}/indexed")
    fun markIndexed(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @PathVariable sourceId: Long,
        @RequestBody request: MarkKnowledgeSourceIndexedRequest,
    ): Map<String, Any?> {
        val source = ingestion.markSourceIndexed(guildId, spaceId, sourceId, request.chunkCount)
        return mapOf("id" to source.id, "status" to source.status, "indexedAt" to source.indexedAt?.toString())
    }

    @GetMapping("/{guildId}/search")
    fun search(
        @PathVariable guildId: Long,
        @RequestParam query: String,
        @RequestParam(defaultValue = "5") limit: Int,
        @RequestParam(required = false) channelId: Long? = null,
        @RequestParam(required = false) knowledgeSpaceId: Long? = null,
    ) = search.search(guildId, query, limit, channelId, knowledgeSpaceId)

    @GetMapping("/{guildId}/context")
    fun promptContext(
        @PathVariable guildId: Long,
        @RequestParam query: String,
        @RequestParam(defaultValue = "1200") maxChars: Int,
        @RequestParam(required = false) channelId: Long? = null,
        @RequestParam(required = false) knowledgeSpaceId: Long? = null,
    ) = search.promptContext(guildId, query, maxChars, channelId, knowledgeSpaceId)

    @GetMapping("/{guildId}/context-plan")
    fun contextPlan(
        @PathVariable guildId: Long,
        @RequestParam query: String,
        @RequestParam(defaultValue = "balanced") responseMode: String,
        @RequestParam(required = false) maxChars: Int? = null,
        @RequestParam(required = false) channelId: Long? = null,
        @RequestParam(required = false) knowledgeSpaceId: Long? = null,
    ) = search.contextPlan(guildId, query, responseMode, maxChars, channelId, knowledgeSpaceId)

    @PostMapping("/{guildId}/eval")
    fun evaluateRetrieval(
        @PathVariable guildId: Long,
        @RequestBody request: KnowledgeEvalRequest,
    ) = search.evaluate(guildId, request.cases, request.k)

    @PostMapping("/{guildId}/spaces/{spaceId}/sources/{sourceId}/delete")
    fun removeSource(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @PathVariable sourceId: Long,
        @RequestBody request: DeleteKnowledgeSourceRequest,
    ): Map<String, Any?> {
        val source = ingestion.removeSource(guildId, spaceId, sourceId, request.reason)
        val deletionIndex =
            indexing?.tombstoneDeletedSourceIndex(
                guildId = guildId,
                spaceId = spaceId,
                sourceId = source.id,
                triggeredBy = request.actorUserId,
            )
        return mapOf(
            "id" to source.id,
            "status" to source.status,
            "deletionIndexJobId" to deletionIndex?.jobId,
            "tombstonedDocumentCount" to (deletionIndex?.tombstonedDocumentCount ?: 0),
            "tombstonedChunkCount" to (deletionIndex?.tombstonedChunkCount ?: 0),
            "remainingReadyChunkCount" to deletionIndex?.remainingReadyChunkCount,
        )
    }

    @PostMapping("/{guildId}/spaces/{spaceId}/sources/{sourceId}/reject")
    fun reject(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @PathVariable sourceId: Long,
        @RequestBody request: RejectKnowledgeSourceRequest,
    ): Map<String, Any?> {
        val source = ingestion.rejectSource(guildId, spaceId, sourceId, request.reason)
        return mapOf("id" to source.id, "status" to source.status)
    }
}

data class CreateKnowledgeSpaceRequest(
    val channelId: Long? = null,
    val channelAiId: Long? = null,
    val displayName: String,
    val actorUserId: Long? = null,
    val embeddingModel: String? = null,
    val indexName: String? = null,
)

data class AddKnowledgeSourceRequest(
    val sourceType: String,
    val title: String,
    val sourceUri: String? = null,
    val contentPreview: String? = null,
    val actorUserId: Long? = null,
)

data class ApproveKnowledgeSourceRequest(
    val reason: String = "manual review approved",
)

data class MarkKnowledgeSourceIndexedRequest(
    val chunkCount: Int,
)

data class QueueKnowledgeIndexJobRequest(
    val actorUserId: Long? = null,
)

data class CompleteKnowledgeIndexJobRequest(
    val status: String = "completed",
    val reason: String? = null,
)

data class RejectKnowledgeSourceRequest(
    val reason: String,
)

data class DeleteKnowledgeSourceRequest(
    val reason: String = "deleted",
    val actorUserId: Long? = null,
)

data class KnowledgeEvalRequest(
    val k: Int = 10,
    val cases: List<KnowledgeGoldenCase>,
)
