package com.discordassistant.central.knowledge.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSpaceStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import com.discordassistant.central.domain.ContentSafety.USABLE_KNOWLEDGE_RISK_LEVELS as INDEXABLE_RISK_LEVELS

@Service
class KnowledgeIngestionService(
    private val spaces: KnowledgeSpaceRepository,
    private val sources: KnowledgeSourceRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
    private val audits: CustomizationAuditLogRepository? = null,
) {
    @Transactional
    fun createSpace(
        guildId: Long,
        channelId: Long?,
        channelAiId: Long?,
        displayName: String,
        createdBy: Long?,
        embeddingModel: String?,
        indexName: String?,
    ): KnowledgeSpaceMutationResult {
        featureGate.requireRagEnabled()
        val now = Instant.now(clock)
        val saved =
            spaces.save(
                KnowledgeSpaceEntity(
                    guildId = guildId,
                    channelId = channelId,
                    channelAiId = channelAiId,
                    displayName = displayName.trim().ifBlank { "채널 지식공간" },
                    status = KnowledgeSpaceStatus.DRAFT,
                    embeddingModel = embeddingModel?.trim()?.ifBlank { null },
                    indexName = indexName?.trim()?.ifBlank { null },
                    createdBy = createdBy,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        audit(saved.guildId, saved.channelId, createdBy, "knowledge_space_create", "knowledge_space", saved.id, saved.displayName)
        return saved.toMutationResult()
    }

    /**
     * 같은 채널 AI + 표시이름의 기존 지식공간이 있으면 그대로 재사용하고, 없으면 새로 만든다(B — 재실행 중복 space 방지).
     * 온보딩 백필이 `/ai-onboard` 재실행마다 같은 채널에 "서버 대화 요약" 지식공간을 무한 생성하던 문제를 막는다.
     * (기존 source 정리는 범위 밖 — 중복 **space** 만 막는다. 새 백필 텍스트는 기존 space 에 source 로 추가된다.)
     */
    fun findOrCreateSpace(
        guildId: Long,
        channelId: Long?,
        channelAiId: Long?,
        displayName: String,
        createdBy: Long?,
        embeddingModel: String?,
        indexName: String?,
    ): KnowledgeSpaceMutationResult {
        featureGate.requireRagEnabled()
        val normalizedName = displayName.trim().ifBlank { "채널 지식공간" }
        if (channelAiId != null) {
            val existing = spaces.findFirstByChannelAiIdAndDisplayNameOrderByIdAsc(channelAiId, normalizedName)
            if (existing != null && existing.guildId == guildId) {
                return existing.toMutationResult()
            }
        }
        return createSpace(
            guildId = guildId,
            channelId = channelId,
            channelAiId = channelAiId,
            displayName = normalizedName,
            createdBy = createdBy,
            embeddingModel = embeddingModel,
            indexName = indexName,
        )
    }

    fun listSources(
        guildId: Long,
        spaceId: Long,
    ): List<KnowledgeSourceSummary> {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        return sources
            .findByKnowledgeSpaceId(space.id)
            .filter { !it.status.isDeleted }
            .sortedWith(compareByDescending<KnowledgeSourceEntity> { it.addedAt }.thenBy { it.id })
            .map { it.toSummary() }
    }

    fun spaceStatus(
        guildId: Long,
        spaceId: Long,
    ): KnowledgeSpaceStatusSummary {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val sourceList = sources.findByKnowledgeSpaceId(space.id).filter { !it.status.isDeleted }
        val indexed = sourceList.count { it.status.isIndexed }
        val blocked = sourceList.count { it.status.isBlocked || it.riskLevel in BLOCKING_RISK_LEVELS }
        val pending = sourceList.count { it.status.isPending }
        val rejected = sourceList.count { it.status.isRejected }
        val readiness =
            when {
                indexed > 0 && blocked == 0 && pending == 0 -> "ready"
                indexed > 0 -> "partial"
                pending > 0 -> "indexing_needed"
                blocked > 0 -> "needs_review"
                rejected > 0 -> "rejected"
                else -> "empty"
            }
        return KnowledgeSpaceStatusSummary(
            guildId = guildId,
            knowledgeSpaceId = space.id,
            channelId = space.channelId,
            channelAiId = space.channelAiId,
            displayName = space.displayName,
            status = space.status.wire,
            readiness = readiness,
            sourceCount = sourceList.size,
            indexedSourceCount = indexed,
            pendingSourceCount = pending,
            blockedSourceCount = blocked,
            rejectedSourceCount = rejected,
            chunkCount = space.chunkCount,
            riskLevels = sourceList.groupingBy { it.riskLevel }.eachCount(),
            sourceStatuses = sourceList.groupingBy { it.status.wire }.eachCount(),
        )
    }

    fun guildReadiness(guildId: Long): KnowledgeGuildReadiness {
        featureGate.requireRagEnabled()
        val summaries = spaces.findByGuildId(guildId).map { spaceStatus(guildId, it.id) }
        val readySpaces = summaries.count { it.readiness == "ready" }
        val partialSpaces = summaries.count { it.readiness == "partial" }
        val blockedSources = summaries.sumOf { it.blockedSourceCount }
        val pendingSources = summaries.sumOf { it.pendingSourceCount }
        val indexedSources = summaries.sumOf { it.indexedSourceCount }
        val totalSources = summaries.sumOf { it.sourceCount }
        val status =
            when {
                summaries.isEmpty() -> "empty"
                blockedSources > 0 -> "needs_review"
                readySpaces > 0 && pendingSources == 0 -> "ready"
                readySpaces > 0 || partialSpaces > 0 -> "partial"
                pendingSources > 0 -> "indexing_needed"
                else -> "empty"
            }
        val gates =
            listOf(
                KnowledgeReadinessGate(
                    code = "has_knowledge_space",
                    passed = summaries.isNotEmpty(),
                    message = if (summaries.isNotEmpty()) "지식공간이 있습니다." else "먼저 채널 지식공간을 만드세요.",
                ),
                KnowledgeReadinessGate(
                    code = "has_indexed_source",
                    passed = indexedSources > 0,
                    message = if (indexedSources > 0) "색인된 지식 소스가 있습니다." else "최소 1개 이상의 지식 소스를 색인하세요.",
                ),
                KnowledgeReadinessGate(
                    code = "no_blocked_sources",
                    passed = blockedSources == 0,
                    message = if (blockedSources == 0) "차단된 지식 소스가 없습니다." else "민감정보/SSRF 위험 지식 소스를 검토하세요.",
                ),
                KnowledgeReadinessGate(
                    code = "no_pending_sources",
                    passed = pendingSources == 0,
                    message = if (pendingSources == 0) "대기 중인 색인 작업이 없습니다." else "대기 중인 지식 소스를 색인하세요.",
                ),
            )
        val nextActions =
            buildList {
                if (summaries.isEmpty()) add("/지식추가 또는 대시보드에서 지식공간을 먼저 만드세요.")
                if (indexedSources == 0 && totalSources > 0) add("indexing-plan을 확인하고 scripts/rag.sh rebuild를 실행하세요.")
                if (pendingSources > 0) add("pending 소스를 색인 완료 처리하거나 실패 원인을 확인하세요.")
                if (blockedSources > 0) add("blocked_sensitive/blocked_ssrf 소스를 삭제하거나 review 소스만 승인하세요.")
                if (status == "ready") add("RAG context-plan과 golden eval을 실행해 검색 품질을 확인하세요.")
            }
        return KnowledgeGuildReadiness(
            guildId = guildId,
            status = status,
            spaceCount = summaries.size,
            readySpaceCount = readySpaces,
            partialSpaceCount = partialSpaces,
            sourceCount = totalSources,
            indexedSourceCount = indexedSources,
            pendingSourceCount = pendingSources,
            blockedSourceCount = blockedSources,
            gates = gates,
            nextActions = nextActions,
            spaces = summaries,
        )
    }

    fun qualitySummary(guildId: Long): KnowledgeQualitySummary {
        featureGate.requireRagEnabled()
        val readiness = guildReadiness(guildId)
        val indexedRatio =
            if (readiness.sourceCount == 0) {
                0.0
            } else {
                readiness.indexedSourceCount.toDouble() / readiness.sourceCount.toDouble()
            }
        val riskPenalty = readiness.blockedSourceCount * 25 + readiness.pendingSourceCount * 10
        val coverageScore =
            when {
                readiness.spaceCount == 0 -> 0
                else -> ((indexedRatio * 100).toInt() - riskPenalty).coerceIn(0, 100)
            }
        val qualityBand =
            when {
                readiness.blockedSourceCount > 0 -> "blocked"
                coverageScore >= 85 -> "healthy"
                coverageScore >= 50 -> "partial"
                readiness.pendingSourceCount > 0 -> "indexing_needed"
                else -> "empty"
            }
        val risks =
            buildList {
                if (readiness.spaceCount == 0) add("no_knowledge_space")
                if (readiness.indexedSourceCount == 0) add("no_indexed_sources")
                if (readiness.pendingSourceCount > 0) add("pending_indexing")
                if (readiness.blockedSourceCount > 0) add("blocked_or_sensitive_sources")
                if (readiness.spaces.any { it.chunkCount == 0 && it.indexedSourceCount > 0 }) add("indexed_without_chunks")
            }
        val recommendations =
            buildList {
                addAll(readiness.nextActions)
                if (qualityBand == "healthy") add("golden eval을 정기적으로 실행하고 실패 케이스를 지식 소스로 보강하세요.")
                if (risks.contains("indexed_without_chunks")) add("색인 완료 소스의 chunkCount가 0입니다. RAG worker 결과를 확인하세요.")
            }.distinct()
        return KnowledgeQualitySummary(
            guildId = guildId,
            status = readiness.status,
            qualityBand = qualityBand,
            coverageScore = coverageScore,
            indexedRatio = indexedRatio,
            spaceCount = readiness.spaceCount,
            sourceCount = readiness.sourceCount,
            indexedSourceCount = readiness.indexedSourceCount,
            pendingSourceCount = readiness.pendingSourceCount,
            blockedSourceCount = readiness.blockedSourceCount,
            riskCodes = risks,
            recommendations = recommendations,
        )
    }

    fun indexingPlan(
        guildId: Long,
        spaceId: Long,
        force: Boolean = false,
    ): KnowledgeIndexingPlan {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val sourceList = sources.findByKnowledgeSpaceId(space.id).filter { !it.status.isDeleted }
        val indexable =
            sourceList
                .filter { it.riskLevel in INDEXABLE_RISK_LEVELS }
                .filter { force || it.status.isPending || it.status.isIndexed }
                .sortedWith(compareBy<KnowledgeSourceEntity> { it.status.wire }.thenBy { it.id })
        val blocked = sourceList.filter { it.riskLevel !in INDEXABLE_RISK_LEVELS || it.status.isBlocked }
        val collection = space.indexName?.trim()?.ifBlank { null } ?: defaultCollectionName(guildId, space.channelId, space.id)
        val embeddingModel = space.embeddingModel?.trim()?.ifBlank { null } ?: DEFAULT_EMBEDDING_MODEL
        val command =
            listOf(
                "scripts/rag.sh",
                "rebuild",
                "--guild",
                guildId.toString(),
                "--space",
                space.id.toString(),
                "--collection",
                collection,
                "--embedding-model",
                embeddingModel,
            ) + if (force) listOf("--force") else emptyList()
        return KnowledgeIndexingPlan(
            guildId = guildId,
            knowledgeSpaceId = space.id,
            channelId = space.channelId,
            collectionName = collection,
            embeddingModel = embeddingModel,
            runtime = "python3.12-qdrant-llamaindex-bm25-rrf-reranker",
            qdrantRequired = true,
            force = force,
            command = command.joinToString(" "),
            indexableSources = indexable.map { it.toIndexingSource() },
            blockedSources = blocked.map { it.toIndexingSource() },
            ready = indexable.isNotEmpty() && blocked.none { it.riskLevel in BLOCKING_RISK_LEVELS },
            warnings = indexingWarnings(indexable, blocked),
        )
    }

    fun indexingOperations(
        guildId: Long,
        force: Boolean = false,
    ): KnowledgeIndexingOperationsSummary {
        featureGate.requireRagEnabled()
        val plans = spaces.findByGuildId(guildId).map { indexingPlan(guildId, it.id, force) }
        val indexableCount = plans.sumOf { it.indexableSources.size }
        val blockedCount = plans.sumOf { it.blockedSources.size }
        val readyPlans = plans.count { it.ready }
        val warnings = plans.flatMap { it.warnings }.distinct().sorted()
        val status =
            when {
                plans.isEmpty() -> "empty"
                blockedCount > 0 -> "blocked"
                indexableCount > 0 -> "ready"
                else -> "nothing_to_index"
            }
        val nextActions =
            buildList {
                if (plans.isEmpty()) add("먼저 지식공간과 지식 소스를 추가하세요.")
                if (blockedCount > 0) add("blocked/review 소스를 승인·거절·삭제한 뒤 색인을 다시 실행하세요.")
                if (indexableCount > 0) add("ready=true인 indexingPlans의 command를 실행하세요.")
                if (status == "nothing_to_index") add("색인할 pending 소스가 없습니다. force=true로 재색인 계획을 확인할 수 있습니다.")
            }.distinct()
        return KnowledgeIndexingOperationsSummary(
            guildId = guildId,
            status = status,
            force = force,
            spaceCount = plans.size,
            readyPlanCount = readyPlans,
            indexableSourceCount = indexableCount,
            blockedSourceCount = blockedCount,
            warnings = warnings,
            nextActions = nextActions,
            commands = plans.filter { it.indexableSources.isNotEmpty() }.map { it.command },
            plans = plans,
        )
    }

    /**
     * 지식 소스를 추가한다.
     *
     * @param screenInjection 사용자 생성 콘텐츠(예: 온보딩 백필)일 때 `true`. 이 경우 본문에
     *  [KnowledgeSafety.looksRiskyInstruction] 매칭(프롬프트 인젝션/탈옥/권한탈취 의도)이 있으면
     *  risk 를 `review`(status REVIEW)로 상향해 **자동 인라인 색인을 막고 관리자 검토 큐로** 보낸다.
     *  관리자가 입력하는 지침/프리셋 경로(CommandService/대시보드)는 `false`(기본)로 기존 동작을 유지한다.
     */
    @Transactional
    fun addSource(
        guildId: Long,
        spaceId: Long,
        sourceType: String,
        title: String,
        sourceUri: String?,
        contentPreview: String?,
        addedBy: Long?,
        screenInjection: Boolean = false,
    ): KnowledgeSourceMutationResult {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val now = Instant.now(clock)
        val normalizedType = sourceType.trim().lowercase().ifBlank { "text" }
        val normalizedUri = sourceUri?.trim()?.ifBlank { null }
        val validation = validateSource(normalizedType, normalizedUri, contentPreview, screenInjection)
        val source =
            sources.save(
                KnowledgeSourceEntity(
                    knowledgeSpaceId = space.id,
                    guildId = guildId,
                    sourceType = normalizedType,
                    sourceUri = normalizedUri,
                    title = title.trim().ifBlank { "untitled" },
                    status = validation.initialStatus,
                    contentHash = stableHash(normalizedUri.orEmpty() + "\n" + contentPreview.orEmpty()),
                    riskLevel = validation.riskLevel,
                    addedBy = addedBy,
                    addedAt = now,
                ),
            )
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).size
        space.status = if (validation.initialStatus.isPending) KnowledgeSpaceStatus.PENDING_INDEX else KnowledgeSpaceStatus.NEEDS_REVIEW
        space.updatedAt = now
        spaces.save(space)
        audit(
            guildId = guildId,
            channelId = space.channelId,
            actorUserId = addedBy,
            action = "knowledge_source_add",
            targetType = "knowledge_source",
            targetId = source.id,
            summary = "${source.sourceType}:${source.riskLevel}",
        )
        return source.toMutationResult()
    }

    @Transactional
    fun approveSourceForIndexing(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        reason: String,
    ): KnowledgeSourceMutationResult {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(space.id, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        require(source.guildId == guildId) { "knowledge source belongs to another guild" }
        require(source.riskLevel == "review") { "only review-risk source can be manually approved: ${source.riskLevel}" }
        require(source.status.isBlocked || source.status.isReview) {
            "only blocked/review source can be manually approved: ${source.status.wire}"
        }
        source.transitionTo(KnowledgeSourceStatus.PENDING)
        val saved = sources.save(source)
        space.transitionTo(KnowledgeSpaceStatus.PENDING_INDEX)
        space.updatedAt = Instant.now(clock)
        spaces.save(space)
        audit(guildId, space.channelId, null, "knowledge_source_approve", "knowledge_source", saved.id, reason)
        return saved.toMutationResult()
    }

    @Transactional
    fun markSourceIndexed(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        chunkCount: Int,
    ): KnowledgeSourceMutationResult {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        require(source.guildId == guildId) { "knowledge source belongs to another guild" }
        require(source.status.isPending) { "only pending source can be indexed: ${source.status.wire}" }
        require(source.riskLevel == "normal" || source.riskLevel == "review") { "unsafe source cannot be indexed: ${source.riskLevel}" }
        val now = Instant.now(clock)
        source.transitionTo(KnowledgeSourceStatus.INDEXED)
        source.indexedAt = now
        val saved = sources.save(source)
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).size
        space.chunkCount = chunkCount.coerceAtLeast(0)
        space.transitionTo(KnowledgeSpaceStatus.READY)
        space.updatedAt = now
        spaces.save(space)
        audit(guildId, space.channelId, null, "knowledge_source_indexed", "knowledge_source", saved.id, "chunks=${space.chunkCount}")
        return saved.toMutationResult()
    }

    @Transactional
    fun rejectSource(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        reason: String,
    ): KnowledgeSourceMutationResult {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        require(source.guildId == guildId) { "knowledge source belongs to another guild" }
        source.transitionTo(KnowledgeSourceStatus.rejected(sanitizeReason(reason)))
        val saved = sources.save(source)
        audit(guildId, space.channelId, null, "knowledge_source_reject", "knowledge_source", saved.id, reason)
        return saved.toMutationResult()
    }

    @Transactional
    fun removeSource(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        reason: String,
    ): KnowledgeSourceMutationResult {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        require(source.guildId == guildId) { "knowledge source belongs to another guild" }
        source.transitionTo(KnowledgeSourceStatus.deleted(sanitizeReason(reason)))
        val saved = sources.save(source)
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).count { it.status.isDeleted.not() }
        space.updatedAt = Instant.now(clock)
        spaces.save(space)
        audit(guildId, space.channelId, null, "knowledge_source_delete", "knowledge_source", saved.id, reason)
        return saved.toMutationResult()
    }

    private fun audit(
        guildId: Long,
        channelId: Long?,
        actorUserId: Long?,
        action: String,
        targetType: String,
        targetId: Long?,
        summary: String,
    ) {
        audits?.save(
            CustomizationAuditLogEntity(
                guildId = guildId,
                channelId = channelId ?: 0,
                actorId = actorUserId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                summary = sanitizeReason(summary).take(1000),
                createdAt = Instant.now(clock),
            ),
        )
    }

    private fun KnowledgeSourceEntity.toIndexingSource(): KnowledgeIndexingSource =
        KnowledgeIndexingSource(
            id = id,
            sourceType = sourceType,
            title = title,
            sourceUri = sourceUri,
            status = status.wire,
            riskLevel = riskLevel,
            contentHash = contentHash,
        )

    private fun indexingWarnings(
        indexable: List<KnowledgeSourceEntity>,
        blocked: List<KnowledgeSourceEntity>,
    ): List<String> =
        buildList {
            if (indexable.isEmpty()) add("indexable_source_empty")
            if (blocked.any { it.riskLevel == "sensitive" }) add("sensitive_source_blocked")
            if (blocked.any { it.riskLevel == "ssrf" }) add("ssrf_source_blocked")
            if (blocked.any { it.riskLevel == "review" }) add("manual_review_required")
        }

    private fun defaultCollectionName(
        guildId: Long,
        channelId: Long?,
        spaceId: Long,
    ): String =
        listOfNotNull(
            "discord_ai",
            "guild_$guildId",
            channelId?.let { "channel_$it" },
            "space_$spaceId",
        ).joinToString("__")

    private fun KnowledgeSourceEntity.toSummary(): KnowledgeSourceSummary =
        KnowledgeSourceSummary(
            id = id,
            knowledgeSpaceId = knowledgeSpaceId,
            guildId = guildId,
            sourceType = sourceType,
            title = title,
            sourceUri = sourceUri,
            status = status.wire,
            contentHash = contentHash,
            riskLevel = riskLevel,
            addedBy = addedBy,
            addedAt = addedAt.toString(),
            indexedAt = indexedAt?.toString(),
        )

    private fun KnowledgeSpaceEntity.toMutationResult(): KnowledgeSpaceMutationResult =
        KnowledgeSpaceMutationResult(
            id = id,
            status = status.wire,
            displayName = displayName,
        )

    private fun KnowledgeSourceEntity.toMutationResult(): KnowledgeSourceMutationResult =
        KnowledgeSourceMutationResult(
            id = id,
            status = status.wire,
            riskLevel = riskLevel,
            indexedAt = indexedAt,
        )

    private fun sanitizeReason(reason: String): String = KnowledgeSafety.redactReason(reason)

    private fun validateSource(
        sourceType: String,
        sourceUri: String?,
        contentPreview: String?,
        screenInjection: Boolean = false,
    ): SourceValidation {
        if (sourceType !in ALLOWED_SOURCE_TYPES) {
            return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_TYPE))
        }
        val text = listOf(sourceType, sourceUri.orEmpty(), contentPreview.orEmpty()).joinToString(" ")
        if (contentPreview.orEmpty().length > MAX_CONTENT_PREVIEW_CHARS) {
            return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_TOO_LARGE))
        }
        if (KnowledgeSafety.containsSensitiveMaterial(text)) {
            return SourceValidation("sensitive", KnowledgeSourceStatus.BLOCKED_SENSITIVE)
        }
        if (sourceUri != null) {
            val uriRisk = validateUri(sourceUri)
            if (uriRisk != null) return uriRisk
        }
        // 사용자 생성 콘텐츠(백필)에 남은 프롬프트 인젝션 의심 문구는 자동 색인하지 않고 관리자 검토 큐로 보낸다.
        // (status REVIEW → indexInlineSourceIfPossible 가 isPending 이 아니라 스킵. 관리자는 approveSourceForIndexing 으로 승인 가능.)
        if (screenInjection && KnowledgeSafety.looksRiskyInstruction(contentPreview)) {
            return SourceValidation("review", KnowledgeSourceStatus(KnowledgeSourceStatus.Kind.REVIEW))
        }
        return SourceValidation("normal", KnowledgeSourceStatus.PENDING)
    }

    private fun blocked(kind: KnowledgeSourceStatus.Kind): KnowledgeSourceStatus = KnowledgeSourceStatus(kind)

    private fun validateUri(sourceUri: String): SourceValidation? {
        val uri =
            runCatching { URI(sourceUri) }.getOrNull()
                ?: return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_BAD_URI))
        if (uri.scheme != "https") return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_NON_HTTPS))
        if (uri.rawUserInfo != null) return SourceValidation("sensitive", KnowledgeSourceStatus.BLOCKED_SENSITIVE)
        val host =
            normalizedUriHost(uri)
                ?: return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_BAD_URI))
        return when {
            host == "localhost" || host.endsWith(".localhost") -> SourceValidation("ssrf", blocked(KnowledgeSourceStatus.Kind.BLOCKED_SSRF))
            host.endsWith(
                ".local",
            ) ||
                host.endsWith(".internal") -> SourceValidation("ssrf", blocked(KnowledgeSourceStatus.Kind.BLOCKED_SSRF))
            isBlockedAddressLiteral(host) -> SourceValidation("ssrf", blocked(KnowledgeSourceStatus.Kind.BLOCKED_SSRF))
            else -> null
        }
    }

    private fun normalizedUriHost(uri: URI): String? {
        val host =
            uri.host
                ?: uri.rawAuthority
                    ?.substringAfterLast("@")
                    ?.let { authority ->
                        if (authority.startsWith("[")) {
                            authority.substringBefore("]").removePrefix("[")
                        } else {
                            authority.substringBefore(":")
                        }
                    }
        return host
            ?.lowercase()
            ?.removeSurrounding("[", "]")
            ?.trimEnd('.')
            ?.ifBlank { null }
    }

    private fun isBlockedAddressLiteral(host: String): Boolean {
        parseNonCanonicalIpv4Octets(host)?.let { return true }
        parseIpv4Octets(host)?.let { octets ->
            return isBlockedIpv4Octets(octets)
        }
        if (":" !in host) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        val bytes = address.address
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                first == 0xfc ||
                first == 0xfd ||
                (first == 0xfe && (second and 0xc0) == 0x80)
        }
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
    }

    private fun isBlockedIpv4Octets(octets: List<Int>): Boolean {
        val first = octets[0]
        val second = octets[1]
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            first >= 224 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 0) ||
            (first == 192 && second == 168) ||
            (first == 198 && second in 18..19)
    }

    private fun parseNonCanonicalIpv4Octets(host: String): List<Int>? {
        val parts = host.split(".")
        if (parts.size !in 1..4) return null
        if (!parts.all { IPV4_NUMBER_PART.matches(it) }) return null
        if (parts.size == 4 && parts.none { it.hasNonCanonicalIpv4Part() }) return null
        val numbers = parts.map { parseIpv4NumberPart(it) ?: return null }
        val value =
            when (numbers.size) {
                1 -> numbers[0].takeIf { it <= 0xffff_ffffL }
                2 ->
                    numbers[0].takeIf { it <= 0xff }?.let { first ->
                        numbers[1].takeIf { it <= 0xff_ffff }?.let { rest -> (first shl 24) or rest }
                    }
                3 ->
                    numbers[0].takeIf { it <= 0xff }?.let { first ->
                        numbers[1].takeIf { it <= 0xff }?.let { second ->
                            numbers[2].takeIf { it <= 0xffff }?.let { rest -> (first shl 24) or (second shl 16) or rest }
                        }
                    }
                4 -> numbers.takeIf { values -> values.all { it <= 0xff } }?.fold(0L) { acc, part -> (acc shl 8) or part }
                else -> null
            } ?: return null
        return listOf(
            ((value ushr 24) and 0xff).toInt(),
            ((value ushr 16) and 0xff).toInt(),
            ((value ushr 8) and 0xff).toInt(),
            (value and 0xff).toInt(),
        )
    }

    private fun String.hasNonCanonicalIpv4Part(): Boolean = startsWith("0x", ignoreCase = true) || (length > 1 && startsWith("0"))

    private fun parseIpv4NumberPart(part: String): Long? =
        when {
            part.startsWith("0x", ignoreCase = true) -> part.drop(2).toLongOrNull(16)
            part.length > 1 && part.startsWith("0") -> part.drop(1).ifEmpty { "0" }.toLongOrNull(8)
            else -> part.toLongOrNull(10)
        }

    private fun parseIpv4Octets(host: String): List<Int>? {
        if (!IPV4_LITERAL.matches(host)) return null
        if (host.split(".").any { it.hasNonCanonicalIpv4Part() }) return null
        val octets = host.split(".").map { it.toIntOrNull() ?: return null }
        return octets.takeIf { values -> values.size == 4 && values.all { it in 0..255 } }
    }

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private data class SourceValidation(
        val riskLevel: String,
        val initialStatus: KnowledgeSourceStatus,
    )

    private companion object {
        const val MAX_CONTENT_PREVIEW_CHARS = 8_000
        const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-large"
        val ALLOWED_SOURCE_TYPES = setOf("file", "link", "text", "faq", "constitution", "preset")
        val BLOCKING_RISK_LEVELS = setOf("sensitive", "ssrf")
        val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
        val IPV4_NUMBER_PART = Regex("""(?i)(?:0x[0-9a-f]+|\d+)""")
    }
}

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
