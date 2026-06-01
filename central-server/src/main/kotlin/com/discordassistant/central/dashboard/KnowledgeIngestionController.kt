package com.discordassistant.central.dashboard

import com.discordassistant.central.network.KnowledgeIngestionService
import com.discordassistant.central.network.KnowledgeSearchService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 채널별 RAG 지식공간 관리 API. 실제 chunk/embed 작업자는 후속 worker가 이 metadata를 소비한다. */
@RestController
@RequestMapping("/api/ai-network/knowledge")
class KnowledgeIngestionController(
    private val ingestion: KnowledgeIngestionService,
    private val search: KnowledgeSearchService,
) {
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

    @PostMapping("/{guildId}/spaces/{spaceId}/sources/{sourceId}/delete")
    fun removeSource(
        @PathVariable guildId: Long,
        @PathVariable spaceId: Long,
        @PathVariable sourceId: Long,
        @RequestBody request: DeleteKnowledgeSourceRequest,
    ): Map<String, Any?> {
        val source = ingestion.removeSource(guildId, spaceId, sourceId, request.reason)
        return mapOf("id" to source.id, "status" to source.status)
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

data class MarkKnowledgeSourceIndexedRequest(
    val chunkCount: Int,
)

data class RejectKnowledgeSourceRequest(
    val reason: String,
)

data class DeleteKnowledgeSourceRequest(
    val reason: String = "deleted",
)
