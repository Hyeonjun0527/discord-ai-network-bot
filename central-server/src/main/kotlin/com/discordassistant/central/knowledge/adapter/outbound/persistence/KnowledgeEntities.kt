package com.discordassistant.central.knowledge.adapter.outbound.persistence

import com.discordassistant.central.knowledge.domain.model.EmbeddingJobStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeChunkStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeDocumentStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSpaceStatus
import com.discordassistant.central.knowledge.domain.model.RetrievalPolicyStatus
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** knowledge 도메인 JPA(adapter/out): RAG 지식공간/소스/문서/청크/색인잡/검색정책. 전이 가드는 status enum. */

@Entity
@Table(name = "knowledge_space")
class KnowledgeSpaceEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var channelAiId: Long? = null,
    var displayName: String = "",
    @Convert(converter = KnowledgeSpaceStatusConverter::class)
    var status: KnowledgeSpaceStatus = KnowledgeSpaceStatus.DRAFT,
    var sourceCount: Int = 0,
    var chunkCount: Int = 0,
    var embeddingModel: String? = null,
    var indexName: String? = null,
    var createdBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
) {
    /** 도메인 전이 가드: 허용되지 않은 status 전이는 거부([KnowledgeSpaceStatus] ALLOWED 맵 기준). */
    fun transitionTo(next: KnowledgeSpaceStatus) {
        require(status.canTransitionTo(next)) { "illegal knowledge space status transition: ${status.wire} -> ${next.wire}" }
        status = next
    }
}

@Entity
@Table(name = "knowledge_source")
class KnowledgeSourceEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var knowledgeSpaceId: Long = 0,
    var guildId: Long = 0,
    var sourceType: String = "",
    var sourceUri: String? = null,
    var title: String = "",
    @Convert(converter = KnowledgeSourceStatusConverter::class)
    var status: KnowledgeSourceStatus = KnowledgeSourceStatus.PENDING,
    var contentHash: String? = null,
    var riskLevel: String = "normal",
    var addedBy: Long? = null,
    var addedAt: Instant = Instant.EPOCH,
    var indexedAt: Instant? = null,
) {
    /** 도메인 전이 가드: 허용되지 않은 status 전이는 거부([KnowledgeSourceStatus] 의 kind 기준 전이 규칙). */
    fun transitionTo(next: KnowledgeSourceStatus) {
        require(status.canTransitionTo(next)) { "illegal knowledge source status transition: ${status.wire} -> ${next.wire}" }
        status = next
    }
}

@Entity
@Table(name = "knowledge_document")
class KnowledgeDocumentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var knowledgeSpaceId: Long = 0,
    var knowledgeSourceId: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var title: String = "",
    var documentType: String = "markdown",
    var contentHash: String = "",
    var tokenEstimate: Int = 0,
    @Convert(converter = KnowledgeDocumentStatusConverter::class)
    var status: KnowledgeDocumentStatus = KnowledgeDocumentStatus.PARSED,
    var parsedAt: Instant = Instant.EPOCH,
) {
    /** 도메인 전이 가드: 허용되지 않은 status 전이는 거부([KnowledgeDocumentStatus] ALLOWED 맵 기준). */
    fun transitionTo(next: KnowledgeDocumentStatus) {
        require(status.canTransitionTo(next)) { "illegal knowledge document status transition: ${status.wire} -> ${next.wire}" }
        status = next
    }
}

@Entity
@Table(name = "knowledge_chunk")
class KnowledgeChunkEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var knowledgeSpaceId: Long = 0,
    var knowledgeDocumentId: Long = 0,
    var knowledgeSourceId: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var chunkIndex: Int = 0,
    var title: String = "",
    var contentPreview: String = "",
    var embeddingTextHash: String = "",
    var tokenEstimate: Int = 0,
    var qdrantPointId: String? = null,
    @Convert(converter = KnowledgeChunkStatusConverter::class)
    var status: KnowledgeChunkStatus = KnowledgeChunkStatus.READY,
    var createdAt: Instant = Instant.EPOCH,
) {
    /** 도메인 전이 가드: 허용되지 않은 status 전이는 거부([KnowledgeChunkStatus] ALLOWED 맵 기준). */
    fun transitionTo(next: KnowledgeChunkStatus) {
        require(status.canTransitionTo(next)) { "illegal knowledge chunk status transition: ${status.wire} -> ${next.wire}" }
        status = next
    }
}

@Entity
@Table(name = "embedding_index_job")
class EmbeddingIndexJobEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var knowledgeSpaceId: Long = 0,
    var triggeredBy: Long? = null,
    var jobType: String = "rebuild",
    @Convert(converter = EmbeddingJobStatusConverter::class)
    var status: EmbeddingJobStatus = EmbeddingJobStatus.QUEUED,
    var collectionName: String = "discord_ai_network",
    var embeddingModel: String = "text-embedding-3-large",
    var sourceCount: Int = 0,
    var chunkCount: Int = 0,
    var failureReason: String? = null,
    var queuedAt: Instant = Instant.EPOCH,
    var startedAt: Instant? = null,
    var finishedAt: Instant? = null,
)

@Entity
@Table(name = "retrieval_policy")
class RetrievalPolicyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var knowledgeSpaceId: Long? = null,
    @Column(name = "top_k") var topK: Int = 6,
    var tokenBudget: Int = 1800,
    var rerankEnabled: Boolean = true,
    var sourcePriority: String? = null,
    @Convert(converter = RetrievalPolicyStatusConverter::class)
    var status: RetrievalPolicyStatus = RetrievalPolicyStatus.ACTIVE,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)
