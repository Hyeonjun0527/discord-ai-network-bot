package com.discordassistant.central.knowledge.adapter.outbound.persistence

import com.discordassistant.central.knowledge.domain.model.KnowledgeChunkStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.discordassistant.central.knowledge.domain.model.RetrievalPolicyStatus
import org.springframework.data.jpa.repository.JpaRepository

/** knowledge 도메인 Spring Data JPA 리포지토리(adapter/out). */

interface KnowledgeSpaceRepository : JpaRepository<KnowledgeSpaceEntity, Long> {
    fun findByGuildId(guildId: Long): List<KnowledgeSpaceEntity>

    fun findByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): List<KnowledgeSpaceEntity>

    fun findByGuildIdAndId(
        guildId: Long,
        id: Long,
    ): KnowledgeSpaceEntity?

    /** 온보딩 백필 지식공간 재사용용 — 같은 채널 AI + 같은 표시이름의 기존 space(가장 먼저 만든 것)를 찾는다. */
    fun findFirstByChannelAiIdAndDisplayNameOrderByIdAsc(
        channelAiId: Long,
        displayName: String,
    ): KnowledgeSpaceEntity?
}

interface KnowledgeSourceRepository : JpaRepository<KnowledgeSourceEntity, Long> {
    fun findByGuildId(guildId: Long): List<KnowledgeSourceEntity>

    fun findByGuildIdAndKnowledgeSpaceIdInAndStatusAndRiskLevelIn(
        guildId: Long,
        knowledgeSpaceIds: Collection<Long>,
        status: KnowledgeSourceStatus,
        riskLevels: Collection<String>,
    ): List<KnowledgeSourceEntity>

    fun findByKnowledgeSpaceId(knowledgeSpaceId: Long): List<KnowledgeSourceEntity>

    fun findByKnowledgeSpaceIdAndId(
        knowledgeSpaceId: Long,
        id: Long,
    ): KnowledgeSourceEntity?
}

interface KnowledgeDocumentRepository : JpaRepository<KnowledgeDocumentEntity, Long> {
    fun findByKnowledgeSpaceId(knowledgeSpaceId: Long): List<KnowledgeDocumentEntity>

    fun findByKnowledgeSourceId(knowledgeSourceId: Long): List<KnowledgeDocumentEntity>
}

interface KnowledgeChunkRepository : JpaRepository<KnowledgeChunkEntity, Long> {
    fun findByKnowledgeSpaceIdAndStatus(
        knowledgeSpaceId: Long,
        status: KnowledgeChunkStatus,
    ): List<KnowledgeChunkEntity>

    fun findByGuildIdAndKnowledgeSpaceIdInAndStatus(
        guildId: Long,
        knowledgeSpaceIds: Collection<Long>,
        status: KnowledgeChunkStatus,
    ): List<KnowledgeChunkEntity>

    fun findByKnowledgeDocumentIdOrderByChunkIndex(knowledgeDocumentId: Long): List<KnowledgeChunkEntity>
}

interface EmbeddingIndexJobRepository : JpaRepository<EmbeddingIndexJobEntity, Long> {
    fun findTop20ByGuildIdOrderByQueuedAtDesc(guildId: Long): List<EmbeddingIndexJobEntity>

    fun findTop10ByGuildIdAndKnowledgeSpaceIdOrderByQueuedAtDesc(
        guildId: Long,
        knowledgeSpaceId: Long,
    ): List<EmbeddingIndexJobEntity>
}

interface RetrievalPolicyRepository : JpaRepository<RetrievalPolicyEntity, Long> {
    fun findByGuildIdAndChannelIdAndKnowledgeSpaceIdAndStatus(
        guildId: Long,
        channelId: Long?,
        knowledgeSpaceId: Long?,
        status: RetrievalPolicyStatus,
    ): RetrievalPolicyEntity?

    fun findByGuildIdAndChannelIdAndStatus(
        guildId: Long,
        channelId: Long?,
        status: RetrievalPolicyStatus,
    ): List<RetrievalPolicyEntity>
}
