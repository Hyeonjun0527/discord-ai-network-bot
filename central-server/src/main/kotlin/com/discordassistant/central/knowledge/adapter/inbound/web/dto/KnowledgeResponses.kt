package com.discordassistant.central.knowledge.adapter.inbound.web.dto

import com.discordassistant.central.knowledge.application.AddKnowledgeSourceResult
import com.discordassistant.central.knowledge.application.KnowledgeSourceMutationResult
import com.discordassistant.central.knowledge.application.KnowledgeSpaceMutationResult
import com.discordassistant.central.knowledge.application.RemoveKnowledgeSourceResult

// 응답 DTO (인바운드 웹 어댑터). 조립 책임만 컨트롤러 인라인 mapOf 에서 흡수했다.
// 각 toMap() 은 원본 mapOf 의 키 이름·값·순서·null·조건부키를 1바이트도 바꾸지 않고 그대로 재현한다
// (OpenApiContractTest·클라이언트 계약). 입력은 application 의 *Result DTO 만 참조한다(엔티티/리포지토리 의존 금지).

/** createSpace 응답. */
data class CreateKnowledgeSpaceResponse(
    val id: Long,
    val status: String,
    val displayName: String,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "status" to status, "displayName" to displayName)

    companion object {
        fun from(result: KnowledgeSpaceMutationResult): CreateKnowledgeSpaceResponse =
            CreateKnowledgeSpaceResponse(id = result.id, status = result.status, displayName = result.displayName)
    }
}

/** addSource(+인라인 색인) 응답. status 는 effectiveStatus. */
data class AddKnowledgeSourceResponse(
    val result: AddKnowledgeSourceResult,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to result.id,
            "status" to result.effectiveStatus,
            "riskLevel" to result.riskLevel,
            "inlineIndexed" to result.inlineIndexed,
            "indexSkippedReason" to result.indexSkippedReason,
            "documentId" to result.documentId,
            "indexJobId" to result.indexJobId,
            "chunkCount" to result.chunkCount,
        )

    companion object {
        fun from(result: AddKnowledgeSourceResult): AddKnowledgeSourceResponse = AddKnowledgeSourceResponse(result)
    }
}

/** approveSource 응답. */
data class ApproveKnowledgeSourceResponse(
    val id: Long,
    val status: String,
    val riskLevel: String,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "status" to status, "riskLevel" to riskLevel)

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
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "status" to status, "indexedAt" to indexedAt)

    companion object {
        fun from(result: KnowledgeSourceMutationResult): MarkKnowledgeSourceIndexedResponse =
            MarkKnowledgeSourceIndexedResponse(id = result.id, status = result.status, indexedAt = result.indexedAt?.toString())
    }
}

/** removeSource(+삭제 색인 tombstone) 응답. */
data class RemoveKnowledgeSourceResponse(
    val result: RemoveKnowledgeSourceResult,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to result.id,
            "status" to result.status,
            "deletionIndexJobId" to result.deletionIndexJobId,
            "tombstonedDocumentCount" to result.tombstonedDocumentCount,
            "tombstonedChunkCount" to result.tombstonedChunkCount,
            "remainingReadyChunkCount" to result.remainingReadyChunkCount,
        )

    companion object {
        fun from(result: RemoveKnowledgeSourceResult): RemoveKnowledgeSourceResponse = RemoveKnowledgeSourceResponse(result)
    }
}

/** reject 응답. */
data class RejectKnowledgeSourceResponse(
    val id: Long,
    val status: String,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "status" to status)

    companion object {
        fun from(result: KnowledgeSourceMutationResult): RejectKnowledgeSourceResponse =
            RejectKnowledgeSourceResponse(id = result.id, status = result.status)
    }
}
