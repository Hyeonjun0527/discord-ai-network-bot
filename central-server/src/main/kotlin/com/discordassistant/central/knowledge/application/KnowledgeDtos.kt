package com.discordassistant.central.knowledge.application

import java.time.Instant

// 응답/결과 DTO (행위 분해: 서비스 본체에서 분리, 같은 패키지·시그니처 불변).

/** 지식공간 변경(생성 등) 결과를 컨트롤러/봇에 노출하는 DTO. JPA 엔티티 누수를 막는다. */
data class KnowledgeSpaceMutationResult(
    val id: Long,
    val status: String,
    val displayName: String,
)

/** 지식 소스 변경(추가/승인/색인/삭제/거절) 결과를 컨트롤러/봇에 노출하는 DTO. JPA 엔티티 누수를 막는다. */
data class KnowledgeSourceMutationResult(
    val id: Long,
    val status: String,
    val riskLevel: String,
    val indexedAt: Instant?,
)

data class KnowledgeSourceSummary(
    val id: Long,
    val knowledgeSpaceId: Long,
    val guildId: Long,
    val sourceType: String,
    val title: String,
    val sourceUri: String?,
    val status: String,
    val contentHash: String?,
    val riskLevel: String,
    val addedBy: Long?,
    val addedAt: String,
    val indexedAt: String?,
)

data class KnowledgeSpaceStatusSummary(
    val guildId: Long,
    val knowledgeSpaceId: Long,
    val channelId: Long?,
    val channelAiId: Long?,
    val displayName: String,
    val status: String,
    val readiness: String,
    val sourceCount: Int,
    val indexedSourceCount: Int,
    val pendingSourceCount: Int,
    val blockedSourceCount: Int,
    val rejectedSourceCount: Int,
    val chunkCount: Int,
    val riskLevels: Map<String, Int>,
    val sourceStatuses: Map<String, Int>,
)

data class KnowledgeQualitySummary(
    val guildId: Long,
    val status: String,
    val qualityBand: String,
    val coverageScore: Int,
    val indexedRatio: Double,
    val spaceCount: Int,
    val sourceCount: Int,
    val indexedSourceCount: Int,
    val pendingSourceCount: Int,
    val blockedSourceCount: Int,
    val riskCodes: List<String>,
    val recommendations: List<String>,
)

data class KnowledgeIndexingOperationsSummary(
    val guildId: Long,
    val status: String,
    val force: Boolean,
    val spaceCount: Int,
    val readyPlanCount: Int,
    val indexableSourceCount: Int,
    val blockedSourceCount: Int,
    val warnings: List<String>,
    val nextActions: List<String>,
    val commands: List<String>,
    val plans: List<KnowledgeIndexingPlan>,
)

data class KnowledgeIndexingPlan(
    val guildId: Long,
    val knowledgeSpaceId: Long,
    val channelId: Long?,
    val collectionName: String,
    val embeddingModel: String,
    val runtime: String,
    val qdrantRequired: Boolean,
    val force: Boolean,
    val command: String,
    val indexableSources: List<KnowledgeIndexingSource>,
    val blockedSources: List<KnowledgeIndexingSource>,
    val ready: Boolean,
    val warnings: List<String>,
)

data class KnowledgeIndexingSource(
    val id: Long,
    val sourceType: String,
    val title: String,
    val sourceUri: String?,
    val status: String,
    val riskLevel: String,
    val contentHash: String?,
)

data class KnowledgeGuildReadiness(
    val guildId: Long,
    val status: String,
    val spaceCount: Int,
    val readySpaceCount: Int,
    val partialSpaceCount: Int,
    val sourceCount: Int,
    val indexedSourceCount: Int,
    val pendingSourceCount: Int,
    val blockedSourceCount: Int,
    val gates: List<KnowledgeReadinessGate>,
    val nextActions: List<String>,
    val spaces: List<KnowledgeSpaceStatusSummary>,
)

data class KnowledgeReadinessGate(
    val code: String,
    val passed: Boolean,
    val message: String,
)
