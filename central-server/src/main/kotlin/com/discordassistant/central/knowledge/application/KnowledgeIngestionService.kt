package com.discordassistant.central.knowledge.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSpaceStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

@Service
class KnowledgeIngestionService(
    private val spaces: KnowledgeSpaceRepository,
    private val sources: KnowledgeSourceRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
    private val audits: CustomizationAuditLogRepository? = null,
    // 순수/읽기 협력자(동작보존 분해). 기본값은 기존 의존성으로 구성해 수동 생성(테스트) 호환을 유지하고,
    // Spring 컨텍스트에서는 동일 시그니처의 @Component 빈이 주입된다. write/@Transactional 라이프사이클은 파사드 잔존.
    private val sourceValidator: KnowledgeSourceValidator = KnowledgeSourceValidator(),
    private val readinessReporter: KnowledgeReadinessReporter = KnowledgeReadinessReporter(spaces, sources, featureGate),
    private val indexingPlanner: KnowledgeIndexingPlanner = KnowledgeIndexingPlanner(spaces, sources, featureGate),
    // @Transactional 미부여 협력자 — 파사드의 활성 TX 에 합류한다(새 TX 미발생). clock 은 파사드와 공유.
    private val auditWriter: KnowledgeAuditWriter = KnowledgeAuditWriter(audits, clock),
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
    ): List<KnowledgeSourceSummary> = readinessReporter.listSources(guildId, spaceId)

    fun spaceStatus(
        guildId: Long,
        spaceId: Long,
    ): KnowledgeSpaceStatusSummary = readinessReporter.spaceStatus(guildId, spaceId)

    fun guildReadiness(guildId: Long): KnowledgeGuildReadiness = readinessReporter.guildReadiness(guildId)

    fun qualitySummary(guildId: Long): KnowledgeQualitySummary = readinessReporter.qualitySummary(guildId)

    fun indexingPlan(
        guildId: Long,
        spaceId: Long,
        force: Boolean = false,
    ): KnowledgeIndexingPlan = indexingPlanner.indexingPlan(guildId, spaceId, force)

    fun indexingOperations(
        guildId: Long,
        force: Boolean = false,
    ): KnowledgeIndexingOperationsSummary = indexingPlanner.indexingOperations(guildId, force)

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
        val validation = sourceValidator.validateSource(normalizedType, normalizedUri, contentPreview, screenInjection)
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

    /**
     * addSource + 인라인 색인을 오케스트레이션한다(기존 컨트롤러 인라인 로직 이관, 동작 불변).
     *
     * - `addSource` 와 `indexInlineSourceIfPossible` 는 각자 `@Transactional` 경계를 유지한다. 이 메서드는
     *   의도적으로 `@Transactional` 을 붙이지 않아 두 호출의 TX 경계를 합치지 않는다(REQUIRES_NEW 신규부여 금지).
     * - `indexing` 이 null(미구성)이면 인라인 색인을 건너뛰고 기존과 동일하게 source.status 를 그대로 노출한다.
     * - `effectiveStatus` 파생(인라인 색인됨 → "indexed", 아니면 source.status)을 그대로 보존한다.
     */
    fun addSourceWithInlineIndexing(
        command: AddKnowledgeSourceCommand,
        indexing: KnowledgeIndexingService?,
    ): AddKnowledgeSourceResult {
        val source =
            addSource(
                guildId = command.guildId,
                spaceId = command.spaceId,
                sourceType = command.sourceType,
                title = command.title,
                sourceUri = command.sourceUri,
                contentPreview = command.contentPreview,
                addedBy = command.addedBy,
            )
        val inlineIndexing =
            indexing?.indexInlineSourceIfPossible(
                guildId = command.guildId,
                spaceId = command.spaceId,
                sourceId = source.id,
                documentText = command.contentPreview,
                triggeredBy = command.addedBy,
            )
        val effectiveStatus = if (inlineIndexing?.indexed == true) "indexed" else source.status
        return AddKnowledgeSourceResult(
            id = source.id,
            effectiveStatus = effectiveStatus,
            riskLevel = source.riskLevel,
            inlineIndexed = (inlineIndexing?.indexed ?: false),
            indexSkippedReason = inlineIndexing?.skippedReason,
            documentId = inlineIndexing?.documentId,
            indexJobId = inlineIndexing?.jobId,
            chunkCount = (inlineIndexing?.chunkCount ?: 0),
        )
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
        source.transitionTo(KnowledgeSourceStatus.rejected(auditWriter.sanitizeReason(reason)))
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
        source.transitionTo(KnowledgeSourceStatus.deleted(auditWriter.sanitizeReason(reason)))
        val saved = sources.save(source)
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).count { it.status.isDeleted.not() }
        space.updatedAt = Instant.now(clock)
        spaces.save(space)
        audit(guildId, space.channelId, null, "knowledge_source_delete", "knowledge_source", saved.id, reason)
        return saved.toMutationResult()
    }

    /**
     * removeSource + 삭제 색인 tombstone 을 오케스트레이션한다(기존 컨트롤러 인라인 로직 이관, 동작 불변).
     *
     * - `removeSource` 와 `tombstoneDeletedSourceIndex` 는 각자 `@Transactional` 경계를 유지한다. 이 메서드는
     *   의도적으로 `@Transactional` 을 붙이지 않아 두 호출의 TX 경계를 합치지 않는다(REQUIRES_NEW 신규부여 금지).
     * - `indexing` 이 null(미구성)이면 tombstone 을 건너뛰고 기존과 동일하게 카운트 기본값(0/null)을 노출한다.
     */
    fun removeSourceAndTombstone(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        reason: String,
        actorUserId: Long?,
        indexing: KnowledgeIndexingService?,
    ): RemoveKnowledgeSourceResult {
        val source = removeSource(guildId, spaceId, sourceId, reason)
        val deletionIndex =
            indexing?.tombstoneDeletedSourceIndex(
                guildId = guildId,
                spaceId = spaceId,
                sourceId = source.id,
                triggeredBy = actorUserId,
            )
        return RemoveKnowledgeSourceResult(
            id = source.id,
            status = source.status,
            deletionIndexJobId = deletionIndex?.jobId,
            tombstonedDocumentCount = (deletionIndex?.tombstonedDocumentCount ?: 0),
            tombstonedChunkCount = (deletionIndex?.tombstonedChunkCount ?: 0),
            remainingReadyChunkCount = deletionIndex?.remainingReadyChunkCount,
        )
    }

    private fun audit(
        guildId: Long,
        channelId: Long?,
        actorUserId: Long?,
        action: String,
        targetType: String,
        targetId: Long?,
        summary: String,
    ) = auditWriter.audit(guildId, channelId, actorUserId, action, targetType, targetId, summary)

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

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
