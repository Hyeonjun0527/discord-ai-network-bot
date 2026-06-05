package com.discordassistant.central.knowledge.adapter.inbound.web.dto

import com.discordassistant.central.knowledge.application.KnowledgeGoldenCase

// 요청 DTO (인바운드 웹 어댑터). 입력은 application 의 값 타입만 참조한다(엔티티/리포지토리 의존 금지).
// 필드/기본값은 컨트롤러에 인라인이던 원본과 1:1 동일 — JSON 바인딩 계약 불변.

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
