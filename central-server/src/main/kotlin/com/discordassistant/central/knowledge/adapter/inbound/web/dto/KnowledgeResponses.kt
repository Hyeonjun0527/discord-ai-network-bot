package com.discordassistant.central.knowledge.adapter.inbound.web.dto

import com.discordassistant.central.knowledge.application.AddKnowledgeSourceResult
import com.discordassistant.central.knowledge.application.KnowledgeSourceMutationResult
import com.discordassistant.central.knowledge.application.KnowledgeSpaceMutationResult
import com.discordassistant.central.knowledge.application.RemoveKnowledgeSourceResult

// 응답 DTO (인바운드 웹 어댑터). 기존 JSON field 이름은 유지하되 Map 기반 응답 조립을 제거한다.

/** createSpace 응답. */
data class CreateKnowledgeSpaceResponse(
    val id: Long,
    val status: String,
    val displayName: String,
) {
    companion object {
        fun from(result: KnowledgeSpaceMutationResult): CreateKnowledgeSpaceResponse =
            CreateKnowledgeSpaceResponse(id = result.id, status = result.status, displayName = result.displayName)
    }
}

/** addSource(+인라인 색인) 응답. status 는 effectiveStatus. */
data class AddKnowledgeSourceResponse(
    val id: Long,
    val status: String,
    val riskLevel: String,
    val inlineIndexed: Boolean,
    val indexSkippedReason: String?,
    val documentId: Long?,
    val indexJobId: Long?,
    val chunkCount: Int,
) {
    companion object {
        fun from(result: AddKnowledgeSourceResult): AddKnowledgeSourceResponse =
            AddKnowledgeSourceResponse(
                id = result.id,
                status = result.effectiveStatus,
                riskLevel = result.riskLevel,
                inlineIndexed = result.inlineIndexed,
                indexSkippedReason = result.indexSkippedReason,
                documentId = result.documentId,
                indexJobId = result.indexJobId,
                chunkCount = result.chunkCount,
            )
    }
}

/** approveSource 응답. */
data class ApproveKnowledgeSourceResponse(
    val id: Long,
    val status: String,
    val riskLevel: String,
) {
    companion object {
        fun from(result: KnowledgeSourceMutationResult): ApproveKnowledgeSourceResponse =
            ApproveKnowledgeSourceResponse(id = result.id, status = result.status, riskLevel = result.riskLevel)
    }
}

/** markIndexed 응답. */
data class MarkKnowledgeSourceIndexedResponse(
    val id: Long,
    val status: String,
    val indexedAt: String?,
) {
    companion object {
        fun from(result: KnowledgeSourceMutationResult): MarkKnowledgeSourceIndexedResponse =
            MarkKnowledgeSourceIndexedResponse(id = result.id, status = result.status, indexedAt = result.indexedAt?.toString())
    }
}

/** removeSource(+삭제 색인 tombstone) 응답. */
data class RemoveKnowledgeSourceResponse(
    val id: Long,
    val status: String,
    val deletionIndexJobId: Long?,
    val tombstonedDocumentCount: Int,
    val tombstonedChunkCount: Int,
    val remainingReadyChunkCount: Int?,
) {
    companion object {
        fun from(result: RemoveKnowledgeSourceResult): RemoveKnowledgeSourceResponse =
            RemoveKnowledgeSourceResponse(
                id = result.id,
                status = result.status,
                deletionIndexJobId = result.deletionIndexJobId,
                tombstonedDocumentCount = result.tombstonedDocumentCount,
                tombstonedChunkCount = result.tombstonedChunkCount,
                remainingReadyChunkCount = result.remainingReadyChunkCount,
            )
    }
}

/** reject 응답. */
data class RejectKnowledgeSourceResponse(
    val id: Long,
    val status: String,
) {
    companion object {
        fun from(result: KnowledgeSourceMutationResult): RejectKnowledgeSourceResponse =
            RejectKnowledgeSourceResponse(id = result.id, status = result.status)
    }
}
