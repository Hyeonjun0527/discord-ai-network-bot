package com.discordassistant.central.knowledge.adapter.inbound.web

import com.discordassistant.central.global.error.PreconditionFailedException
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.AddKnowledgeSourceRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.AddKnowledgeSourceResponse
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.ApproveKnowledgeSourceRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.ApproveKnowledgeSourceResponse
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.CompleteKnowledgeIndexJobRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.CreateKnowledgeSpaceRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.CreateKnowledgeSpaceResponse
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.DeleteKnowledgeSourceRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.KnowledgeEvalRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.MarkKnowledgeSourceIndexedRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.MarkKnowledgeSourceIndexedResponse
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.QueueKnowledgeIndexJobRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.RejectKnowledgeSourceRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.RejectKnowledgeSourceResponse
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.RemoveKnowledgeSourceResponse
import com.discordassistant.central.knowledge.application.AddKnowledgeSourceCommand
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
        indexing
            ?: throw PreconditionFailedException(
                message = "knowledge indexing service is not configured",
                failedCondition = "knowledge_indexing_service_configured",
                blockedAction = "KNOWLEDGE_INDEXING_OPERATION",
                actionGuide = "KnowledgeIndexingService 를 등록한 뒤 index job API 를 호출해 주세요.",
            )

    @PostMapping("/{guildId}/spaces")
    fun createSpace(
        @PathVariable guildId: Long,
        @RequestBody request: CreateKnowledgeSpaceRequest,
    ): CreateKnowledgeSpaceResponse {
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
        return CreateKnowledgeSpaceResponse.from(space)
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
    ): AddKnowledgeSourceResponse =
        AddKnowledgeSourceResponse
            .from(
                ingestion.addSourceWithInlineIndexing(
                    command =
                        AddKnowledgeSourceCommand(
                            guildId = guildId,
                            spaceId = spaceId,
                            sourceType = request.sourceType,
                            title = request.title,
                            sourceUri = request.sourceUri,
                            contentPreview = request.contentPreview,
                            addedBy = request.actorUserId,
                        ),
                    indexing = indexing,
                ),
            )

    @PostMapping("/{guildId}/spaces/{spaceId}/sources/{sourceId}/approve")
    fun approveSource(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @PathVariable sourceId: Long,
        @RequestBody request: ApproveKnowledgeSourceRequest,
    ): ApproveKnowledgeSourceResponse {
        val source = ingestion.approveSourceForIndexing(guildId, spaceId, sourceId, request.reason)
        return ApproveKnowledgeSourceResponse.from(source)
    }

    @PostMapping("/{guildId}/spaces/{spaceId}/sources/{sourceId}/indexed")
    fun markIndexed(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @PathVariable sourceId: Long,
        @RequestBody request: MarkKnowledgeSourceIndexedRequest,
    ): MarkKnowledgeSourceIndexedResponse {
        val source = ingestion.markSourceIndexed(guildId, spaceId, sourceId, request.chunkCount)
        return MarkKnowledgeSourceIndexedResponse.from(source)
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
    ): RemoveKnowledgeSourceResponse =
        RemoveKnowledgeSourceResponse
            .from(
                ingestion.removeSourceAndTombstone(
                    guildId = guildId,
                    spaceId = spaceId,
                    sourceId = sourceId,
                    reason = request.reason,
                    actorUserId = request.actorUserId,
                    indexing = indexing,
                ),
            )

    @PostMapping("/{guildId}/spaces/{spaceId}/sources/{sourceId}/reject")
    fun reject(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @PathVariable sourceId: Long,
        @RequestBody request: RejectKnowledgeSourceRequest,
    ): RejectKnowledgeSourceResponse {
        val source = ingestion.rejectSource(guildId, spaceId, sourceId, request.reason)
        return RejectKnowledgeSourceResponse.from(source)
    }
}
