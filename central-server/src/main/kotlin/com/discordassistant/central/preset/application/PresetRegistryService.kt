package com.discordassistant.central.preset.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingSnapshot
import com.discordassistant.central.ainetwork.domain.model.AI_NETWORK_MAX_CANDIDATES
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.channelai.domain.model.ProposalStatus
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetReactionEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetReactionRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetReportEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetReportRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetRepository
import com.discordassistant.central.preset.domain.model.PresetImportStatus
import com.discordassistant.central.preset.domain.model.PresetReportStatus
import com.discordassistant.central.preset.domain.model.PresetStatus
import com.discordassistant.central.preset.domain.model.PublishedPresetStatus
import com.discordassistant.central.shared.ContentSafety
import com.discordassistant.central.shared.ContentSafety.HIGH_RISK_SAFETY_LEVELS
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

@Service
class PresetRegistryService(
    private val presets: AiPresetRepository,
    private val revisions: PresetRevisionRepository,
    private val publishedPresets: PublishedPresetRepository,
    private val imports: PresetImportRepository,
    private val reactions: PresetReactionRepository,
    private val reports: PresetReportRepository,
    private val channelAis: ChannelAiRepository,
    private val routingPolicies: ChannelAiRoutingPolicyRepository,
    private val behaviorVersions: AiBehaviorVersionRepository,
    private val proposals: AiChangeProposalRepository,
    private val audits: CustomizationAuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
    private val contentSafety: PresetContentSafety = PresetContentSafety(),
    private val revisionFactory: PresetRevisionFactory =
        PresetRevisionFactory(
            revisions = revisions,
            safety = contentSafety,
        ),
    private val importPreviewBuilder: PresetImportPreviewBuilder =
        PresetImportPreviewBuilder(
            channelAis = channelAis,
            routingPolicies = routingPolicies,
            safety = contentSafety,
        ),
    private val catalog: PresetCatalogQueryService =
        PresetCatalogQueryService(
            presets = presets,
            revisions = revisions,
            publishedPresets = publishedPresets,
            imports = imports,
            reports = reports,
            clock = clock,
            featureGate = featureGate,
        ),
) {
    @Transactional
    fun createPreset(
        guildId: Long,
        ownerUserId: Long?,
        name: String,
        summary: String?,
        category: String,
        visibility: String,
        behavior: PresetBehaviorInput,
    ): PresetWriteResult {
        featureGate.requirePresetEnabled()
        val now = Instant.now(clock)
        val preset =
            presets.save(
                AiPresetEntity(
                    guildId = guildId,
                    ownerUserId = ownerUserId,
                    name = name.trim(),
                    summary = summary?.trim(),
                    category = category.trim().ifBlank { "general" },
                    visibility = visibility.trim().ifBlank { "guild_private" },
                    status = PresetStatus.DRAFT,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        val revision = createRevision(preset, revision = 1, behavior = behavior, createdBy = ownerUserId, now = now)
        preset.currentRevisionId = revision.id
        preset.updatedAt = now
        return presets.save(preset).toWriteResult()
    }

    @Transactional
    fun saveChannelAsPreset(
        guildId: Long,
        channelId: Long,
        ownerUserId: Long?,
        name: String?,
        summary: String?,
        category: String = "channel_ai",
        visibility: String = "guild_private",
    ): PresetWriteResult {
        featureGate.requirePresetEnabled()
        val channelAi =
            channelAis.findByGuildIdAndChannelId(guildId, channelId)
                ?: throw IllegalArgumentException("channel ai not found: guild=$guildId channel=$channelId")
        val behavior =
            channelAi.activeBehaviorVersionId?.let { behaviorVersions.findByChannelAiIdAndId(channelAi.id, it) }
                ?: behaviorVersions.findTopByChannelAiIdOrderByVersionDesc(channelAi.id)
                ?: throw IllegalArgumentException("channel ai has no behavior version: ${channelAi.id}")
        val routing = routingPolicies.findByGuildIdAndChannelId(guildId, channelId)
        return createPreset(
            guildId = guildId,
            ownerUserId = ownerUserId,
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: channelAi.displayName,
            summary = summary?.trim()?.ifBlank { null } ?: behavior.purpose,
            category = category.trim().ifBlank { "channel_ai" },
            visibility = visibility.trim().ifBlank { "guild_private" },
            behavior =
                PresetBehaviorInput(
                    purpose = behavior.purpose,
                    tone = behavior.tone,
                    answerLength = behavior.answerLength,
                    constitution = behavior.constitution,
                    safetyLevel = behavior.safetyLevel,
                    responseMode = routing?.responseMode ?: "balanced",
                    preferredModel = routing?.preferredModel,
                    minQualityTier = routing?.minQualityTier ?: "standard",
                    maxCandidates = routing?.maxCandidates ?: 1,
                    providerTagFilter = splitCsv(routing?.providerTagFilter),
                    costGuard = routing?.costGuard ?: "provider_safe",
                    changeSummary = "saved from channel AI #${channelAi.id} channel #$channelId",
                ),
        )
    }

    // --- read-only 카탈로그/검색/조회/moderation 표면은 PresetCatalogQueryService 에 위임 (CQRS 분리, 동작 불변) ---

    @Transactional(readOnly = true)
    fun listGuildPresets(guildId: Long): List<PresetSummary> = catalog.listGuildPresets(guildId)

    @Transactional(readOnly = true)
    fun listPublishedPresets(): List<PublishedPresetSummary> = catalog.listPublishedPresets()

    @Transactional(readOnly = true)
    fun searchPublishedPresets(
        query: String? = null,
        category: String? = null,
        sort: String = "popular",
        limit: Int = 20,
    ): List<PublishedPresetSummary> = catalog.searchPublishedPresets(query, category, sort, limit)

    @Transactional(readOnly = true)
    fun searchPublishedPresetsResult(
        query: String? = null,
        category: String? = null,
        sort: String = "popular",
        limit: Int = 20,
    ): PresetCatalogResult = catalog.searchPublishedPresetsResult(query, category, sort, limit)

    @Transactional(readOnly = true)
    fun recommendedPublishedPresets(
        category: String? = null,
        limit: Int = 10,
    ): List<PresetRecommendation> = catalog.recommendedPublishedPresets(category, limit)

    @Transactional(readOnly = true)
    fun recommendedPublishedPresetsResult(
        category: String? = null,
        limit: Int = 10,
    ): PresetRecommendationResult = catalog.recommendedPublishedPresetsResult(category, limit)

    fun webReadiness(): PresetWebReadiness = catalog.webReadiness()

    @Transactional(readOnly = true)
    fun catalogFacets(): PresetCatalogFacets = catalog.catalogFacets()

    @Transactional(readOnly = true)
    fun presetDetail(presetId: Long): PresetDetail = catalog.presetDetail(presetId)

    @Transactional(readOnly = true)
    fun importHistory(
        targetGuildId: Long,
        targetChannelId: Long? = null,
    ): List<PresetImportSummary> = catalog.importHistory(targetGuildId, targetChannelId)

    @Transactional(readOnly = true)
    fun importHistoryResult(
        targetGuildId: Long,
        targetChannelId: Long? = null,
    ): PresetImportHistoryResult = catalog.importHistoryResult(targetGuildId, targetChannelId)

    @Transactional(readOnly = true)
    fun listReports(status: String = "open"): List<PresetReportSummary> = catalog.listReports(status)

    @Transactional(readOnly = true)
    fun moderationSummary(): PresetModerationSummary = catalog.moderationSummary()

    @Transactional(readOnly = true)
    fun publishedPresetDetail(publishedPresetId: Long): PublishedPresetDetail = catalog.publishedPresetDetail(publishedPresetId)

    @Transactional(readOnly = true)
    fun publishedPresetDetailBySlug(slug: String): PublishedPresetDetail = catalog.publishedPresetDetailBySlug(slug)

    @Transactional
    fun updatePreset(
        presetId: Long,
        actorUserId: Long?,
        name: String?,
        summary: String?,
        category: String?,
        visibility: String?,
        behavior: PresetBehaviorInput?,
    ): PresetWriteResult {
        featureGate.requirePresetEnabled()
        val now = Instant.now(clock)
        val preset = presets.findById(presetId).orElseThrow { IllegalArgumentException("preset not found: $presetId") }
        requireActivePreset(preset)
        name?.trim()?.takeIf { it.isNotBlank() }?.let { preset.name = it }
        summary?.trim()?.let { preset.summary = it.ifBlank { null } }
        category?.trim()?.takeIf { it.isNotBlank() }?.let { preset.category = it }
        visibility?.trim()?.takeIf { it.isNotBlank() }?.let { preset.visibility = it }
        if (behavior != null) {
            val nextRevision = (revisions.findByPresetIdOrderByRevisionDesc(preset.id).firstOrNull()?.revision ?: 0) + 1
            val revision = createRevision(preset, nextRevision, behavior, actorUserId, now)
            preset.currentRevisionId = revision.id
        }
        preset.updatedAt = now
        return presets.save(preset).toWriteResult()
    }

    @Transactional
    fun publishPreset(
        presetId: Long,
        publisherUserId: Long?,
        title: String?,
        description: String?,
    ): PublishedPresetWriteResult {
        featureGate.requirePresetEnabled()
        val preset = presets.findById(presetId).orElseThrow { IllegalArgumentException("preset not found: $presetId") }
        requireActivePreset(preset)
        val revisionId =
            preset.currentRevisionId
                ?: revisions.findByPresetIdOrderByRevisionDesc(preset.id).firstOrNull()?.id
                ?: throw IllegalArgumentException("preset has no revision: $presetId")
        val revision =
            revisions.findById(revisionId).orElseThrow {
                IllegalArgumentException("preset revision not found: $revisionId")
            }
        requirePublishableRevision(revision)
        val publishTitle = title?.trim()?.ifBlank { null } ?: preset.name
        val publishDescription = description?.trim()?.ifBlank { null } ?: preset.summary
        requirePublishablePublicMetadata(publishTitle, publishDescription, preset.category)
        preset.transitionTo(PresetStatus.PUBLISHED)
        preset.visibility = "published"
        presets.save(preset)
        return publishedPresets
            .save(
                PublishedPresetEntity(
                    presetId = preset.id,
                    revisionId = revisionId,
                    publisherGuildId = preset.guildId,
                    publisherUserId = publisherUserId,
                    slug = uniqueSlug(publishTitle.take(120), preset.id),
                    title = publishTitle.take(120),
                    description = publishDescription?.take(500),
                    status = PublishedPresetStatus.PUBLISHED,
                    publishedAt = Instant.now(clock),
                ),
            ).toWriteResult()
    }

    @Transactional(readOnly = true)
    fun previewImport(
        publishedPresetId: Long,
        targetGuildId: Long,
        targetChannelId: Long?,
    ): PresetImportPreview {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        requirePublishedPreset(published)
        val sourceRevision =
            revisions.findById(published.revisionId).orElseThrow {
                IllegalArgumentException("published revision not found: ${published.revisionId}")
            }
        requirePublishablePublicMetadata(published.title, published.description)
        requirePublishableRevision(sourceRevision)
        return buildImportPreview(published, sourceRevision, targetGuildId, targetChannelId)
    }

    @Transactional
    fun updatePublishedPreset(
        publishedPresetId: Long,
        actorUserId: Long?,
        title: String?,
        description: String?,
        behavior: PresetBehaviorInput?,
    ): PublishedPresetWriteResult {
        featureGate.requirePresetEnabled()
        val now = Instant.now(clock)
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        requirePublishedPreset(published)
        val preset =
            presets.findById(published.presetId).orElseThrow {
                IllegalArgumentException("preset not found: ${published.presetId}")
            }
        requireActivePreset(preset)
        val nextTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: published.title
        val nextDescription = description?.trim()?.let { it.ifBlank { null } } ?: published.description
        requirePublishablePublicMetadata(nextTitle, nextDescription, preset.category)
        title?.trim()?.takeIf { it.isNotBlank() }?.let { published.title = it.take(120) }
        description?.trim()?.let { published.description = it.ifBlank { null }?.take(500) }
        if (behavior != null) {
            val nextRevision = (revisions.findByPresetIdOrderByRevisionDesc(preset.id).firstOrNull()?.revision ?: 0) + 1
            val revision = createRevision(preset, nextRevision, behavior, actorUserId, now)
            preset.currentRevisionId = revision.id
            preset.updatedAt = now
            presets.save(preset)
            requirePublishableRevision(revision)
            published.revisionId = revision.id
        }
        return publishedPresets.save(published).toWriteResult()
    }

    @Transactional
    fun importPreset(
        publishedPresetId: Long,
        targetGuildId: Long,
        targetChannelId: Long?,
        importedBy: Long?,
        confirmConflicts: Boolean = false,
    ): PresetImportResult {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        requirePublishedPreset(published)
        val sourceRevision =
            revisions.findById(published.revisionId).orElseThrow {
                IllegalArgumentException("published revision not found: ${published.revisionId}")
            }
        requirePublishablePublicMetadata(published.title, published.description)
        requirePublishableRevision(sourceRevision)
        val preview = buildImportPreview(published, sourceRevision, targetGuildId, targetChannelId)
        val confirmationRequired = preview.conflicts.any { it.severity in CONFIRM_REQUIRED_CONFLICT_SEVERITIES }
        require(confirmConflicts || !confirmationRequired) {
            "preset import has conflicts; preview and confirm first: ${preview.conflicts.joinToString(",") { it.code }}"
        }
        val importedPreset =
            createPreset(
                guildId = targetGuildId,
                ownerUserId = importedBy,
                name = published.title,
                summary = published.description,
                category = "imported",
                visibility = "guild_private",
                behavior =
                    PresetBehaviorInput(
                        purpose = sourceRevision.purpose,
                        tone = sourceRevision.tone,
                        answerLength = sourceRevision.answerLength,
                        constitution = sourceRevision.constitution,
                        safetyLevel = sourceRevision.safetyLevel,
                        tags = splitCsv(sourceRevision.tags),
                        knowledgeSlotNames = splitCsv(sourceRevision.knowledgeSlotNames),
                        knowledgeGuide = sourceRevision.knowledgeGuide,
                        exampleQuestions = splitLines(sourceRevision.exampleQuestions),
                        changeSummary = "imported from published preset #${published.id}",
                    ),
            )
        val now = Instant.now(clock)
        val applied =
            targetChannelId?.let {
                applyRevisionToChannel(
                    published = published,
                    sourceRevision = sourceRevision,
                    targetGuildId = targetGuildId,
                    targetChannelId = it,
                    importedBy = importedBy,
                    now = now,
                )
            }
        published.importCount += 1
        publishedPresets.save(published)
        return imports
            .save(
                PresetImportEntity(
                    publishedPresetId = publishedPresetId,
                    sourceRevisionId = sourceRevision.id,
                    targetGuildId = targetGuildId,
                    targetChannelId = targetChannelId,
                    importedBy = importedBy,
                    importedPresetId = importedPreset.id,
                    createdChannelAiId = applied?.channelAiId,
                    createdBehaviorVersionId = applied?.behaviorVersionId,
                    status = applied?.status ?: PresetImportStatus.IMPORTED,
                    importedAt = now,
                ),
            ).toResult()
    }

    @Transactional
    fun likePreset(
        publishedPresetId: Long,
        userId: Long,
    ): PublishedPresetWriteResult {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        requirePublishedPreset(published)
        if (reactions.findByPublishedPresetIdAndUserIdAndReaction(publishedPresetId, userId, "like") == null) {
            reactions.save(
                PresetReactionEntity(
                    publishedPresetId = publishedPresetId,
                    userId = userId,
                    reaction = "like",
                    createdAt = Instant.now(clock),
                ),
            )
            published.likeCount += 1
        }
        return publishedPresets.save(published).toWriteResult()
    }

    @Transactional
    fun unlikePreset(
        publishedPresetId: Long,
        userId: Long,
    ): PublishedPresetWriteResult {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        require(published.status !in setOf(PublishedPresetStatus.REMOVED, PublishedPresetStatus.UNLISTED)) {
            "${published.status.wire} preset cannot be unliked"
        }
        reactions.findByPublishedPresetIdAndUserIdAndReaction(publishedPresetId, userId, "like")?.let { reaction ->
            reactions.delete(reaction)
            published.likeCount = (published.likeCount - 1).coerceAtLeast(0)
        }
        return publishedPresets.save(published).toWriteResult()
    }

    @Transactional
    fun reportPreset(
        publishedPresetId: Long,
        reporterUserId: Long?,
        reason: String,
        reasonCode: String? = null,
        details: String? = null,
    ): PresetReportWriteResult {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        require(published.status !in setOf(PublishedPresetStatus.REMOVED, PublishedPresetStatus.UNLISTED)) {
            "${published.status.wire} preset cannot be reported"
        }
        reporterUserId?.let { reporter ->
            reports.findByPublishedPresetIdAndReporterUserIdAndStatus(publishedPresetId, reporter, PresetReportStatus.OPEN)?.let {
                return it.toWriteResult()
            }
        }
        published.reportCount += 1
        if (published.reportCount >= REPORT_REVIEW_THRESHOLD && published.status == PublishedPresetStatus.PUBLISHED) {
            published.transitionTo(PublishedPresetStatus.UNDER_REVIEW)
        }
        publishedPresets.save(published)
        val sanitizedReason = sanitizeText(reason, maxLength = 500)
        val sanitizedDetails =
            details
                ?.let { sanitizeText(it, maxLength = 500) }
                ?.takeIf { it != "no reason provided" }
        val normalizedReasonCode = normalizeReportReasonCode(reasonCode ?: reason)
        return reports
            .save(
                PresetReportEntity(
                    publishedPresetId = publishedPresetId,
                    reporterUserId = reporterUserId,
                    reason = sanitizedReason,
                    reasonCode = normalizedReasonCode,
                    details = sanitizedDetails,
                    createdAt = Instant.now(clock),
                ),
            ).toWriteResult()
    }

    @Transactional
    fun deletePreset(presetId: Long): PresetWriteResult {
        featureGate.requirePresetEnabled()
        val preset = presets.findById(presetId).orElseThrow { IllegalArgumentException("preset not found: $presetId") }
        preset.transitionTo(PresetStatus.REMOVED)
        preset.visibility = "removed"
        preset.updatedAt = Instant.now(clock)
        return presets.save(preset).toWriteResult()
    }

    @Transactional
    fun deletePublishedPreset(publishedPresetId: Long): PublishedPresetWriteResult {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        published.transitionTo(PublishedPresetStatus.REMOVED)
        return publishedPresets.save(published).toWriteResult()
    }

    @Transactional
    fun unlistPublishedPreset(publishedPresetId: Long): PublishedPresetWriteResult {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        require(published.status != PublishedPresetStatus.REMOVED) { "removed preset cannot be unlisted" }
        published.transitionTo(PublishedPresetStatus.UNLISTED)
        return publishedPresets.save(published).toWriteResult()
    }

    @Transactional
    fun republishPreset(publishedPresetId: Long): PublishedPresetWriteResult {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        require(published.status == PublishedPresetStatus.UNLISTED) {
            "only unlisted preset can be republished: ${published.status.wire}"
        }
        val revision =
            revisions.findById(published.revisionId).orElseThrow {
                IllegalArgumentException("published revision not found: ${published.revisionId}")
            }
        requirePublishablePublicMetadata(published.title, published.description)
        requirePublishableRevision(revision)
        published.transitionTo(PublishedPresetStatus.PUBLISHED)
        return publishedPresets.save(published).toWriteResult()
    }

    @Transactional
    fun reviewReport(
        reportId: Long,
        decision: String,
        reviewerUserId: Long? = null,
    ): PresetReportWriteResult {
        featureGate.requirePresetEnabled()
        val report = reports.findById(reportId).orElseThrow { IllegalArgumentException("preset report not found: $reportId") }
        val published =
            publishedPresets.findById(report.publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: ${report.publishedPresetId}")
            }
        report.status = PresetReportStatus.fromDecision(decision)
        report.reviewedBy = reviewerUserId
        report.reviewedAt = Instant.now(clock)
        when (report.status) {
            PresetReportStatus.SUSPEND -> published.transitionTo(PublishedPresetStatus.SUSPENDED)
            PresetReportStatus.REMOVE -> published.transitionTo(PublishedPresetStatus.REMOVED)
            PresetReportStatus.DISMISS ->
                if (published.status == PublishedPresetStatus.UNDER_REVIEW) {
                    published.transitionTo(PublishedPresetStatus.PUBLISHED)
                }
            PresetReportStatus.OPEN, PresetReportStatus.REVIEWED -> Unit
        }
        publishedPresets.save(published)
        return reports.save(report).toWriteResult()
    }

    // --- write 결과 엔티티 → DTO 매핑 (web↛entity 누수 제거, 원시값 그대로) ---

    private fun AiPresetEntity.toWriteResult(): PresetWriteResult =
        PresetWriteResult(
            id = id,
            currentRevisionId = currentRevisionId,
            status = status.wire,
        )

    private fun PublishedPresetEntity.toWriteResult(): PublishedPresetWriteResult =
        PublishedPresetWriteResult(
            id = id,
            revisionId = revisionId,
            status = status.wire,
            slug = slug,
            title = title,
            description = description,
            likeCount = likeCount,
        )

    private fun PresetImportEntity.toResult(): PresetImportResult =
        PresetImportResult(
            id = id,
            importedPresetId = importedPresetId,
            sourceRevisionId = sourceRevisionId,
            createdChannelAiId = createdChannelAiId,
            createdBehaviorVersionId = createdBehaviorVersionId,
            status = status.wire,
        )

    private fun PresetReportEntity.toWriteResult(): PresetReportWriteResult =
        PresetReportWriteResult(
            id = id,
            status = status.wire,
            reasonCode = reasonCode,
            reviewedBy = reviewedBy,
            reviewedAt = reviewedAt?.toString(),
        )

    private fun buildImportPreview(
        published: PublishedPresetEntity,
        sourceRevision: PresetRevisionEntity,
        targetGuildId: Long,
        targetChannelId: Long?,
    ): PresetImportPreview = importPreviewBuilder.buildImportPreview(published, sourceRevision, targetGuildId, targetChannelId)

    private fun applyRevisionToChannel(
        published: PublishedPresetEntity,
        sourceRevision: PresetRevisionEntity,
        targetGuildId: Long,
        targetChannelId: Long,
        importedBy: Long?,
        now: Instant,
    ): AppliedPresetChannelAi {
        val channelAi =
            channelAis.findByGuildIdAndChannelId(targetGuildId, targetChannelId)
                ?: ChannelAiEntity(
                    guildId = targetGuildId,
                    channelId = targetChannelId,
                    source = "preset_import",
                    createdAt = now,
                )
        channelAi.displayName =
            published.title
                .trim()
                .take(80)
                .ifBlank { sourceRevision.name.take(80).ifBlank { "냥시스턴트" } }
        channelAi.updatedAt = now
        val savedChannel = channelAis.saveAndFlush(channelAi)
        val behavior =
            saveNextBehaviorVersion(savedChannel.id) { nextVersion ->
                AiBehaviorVersionEntity(
                    channelAiId = savedChannel.id,
                    version = nextVersion,
                    purpose = sourceRevision.purpose,
                    tone = sourceRevision.tone,
                    answerLength = sourceRevision.answerLength,
                    constitution = sourceRevision.constitution,
                    safetyLevel = sourceRevision.safetyLevel,
                    createdBy = importedBy,
                    createdAt = now,
                    changeSummary = "imported from published preset #${published.id} revision #${sourceRevision.revision}",
                )
            }
        val highRisk = sourceRevision.safetyLevel.lowercase() in HIGH_RISK_SAFETY_LEVELS
        val status = if (highRisk) PresetImportStatus.NEEDS_REVIEW else PresetImportStatus.APPLIED
        if (highRisk) {
            proposals.save(
                AiChangeProposalEntity(
                    guildId = targetGuildId,
                    channelId = targetChannelId,
                    channelAiId = savedChannel.id,
                    proposedBehaviorId = behavior.id,
                    status = ProposalStatus.PENDING,
                    requestedBy = importedBy,
                    reason = "preset import requires review: ${sourceRevision.safetyLevel}",
                    payloadHash = behavior.payloadHash(),
                    routingSnapshot = ChannelAiRoutingSnapshot.fromRevision(sourceRevision).encode(),
                    createdAt = now,
                ),
            )
        } else {
            savedChannel.activeBehaviorVersionId = behavior.id
            savedChannel.updatedAt = now
            channelAis.save(savedChannel)
            applyRoutingPolicySnapshot(sourceRevision, targetGuildId, targetChannelId, savedChannel.id, now)
        }
        audits.save(
            CustomizationAuditLogEntity(
                guildId = targetGuildId,
                channelId = targetChannelId,
                actorId = importedBy,
                action = if (highRisk) "preset_import_proposed" else "preset_import_applied",
                targetType = "ai_behavior_version",
                targetId = behavior.id,
                summary = "publishedPreset=${published.id} revision=${sourceRevision.revision} status=${status.wire}",
                createdAt = now,
            ),
        )
        return AppliedPresetChannelAi(savedChannel.id, behavior.id, status)
    }

    /**
     * behavior version 채번(`MAX(version)+1`)+insert 를 동시성 안전하게 수행한다(#2와 동일 패턴).
     * 채널 AI 행을 PESSIMISTIC_WRITE 로 잠가 같은 채널의 채번을 직렬화하고, 유니크 위반 시
     * version 을 재조회해 최대 [MAX_VERSION_RETRIES] 회 재시도한다.
     */
    private fun saveNextBehaviorVersion(
        channelAiId: Long,
        build: (Int) -> AiBehaviorVersionEntity,
    ): AiBehaviorVersionEntity {
        var attempt = 0
        while (true) {
            channelAis.findByIdForUpdate(channelAiId)
            val nextVersion = (behaviorVersions.findTopByChannelAiIdOrderByVersionDesc(channelAiId)?.version ?: 0) + 1
            try {
                return behaviorVersions.saveAndFlush(build(nextVersion))
            } catch (ex: org.springframework.dao.DataIntegrityViolationException) {
                attempt += 1
                if (attempt >= MAX_VERSION_RETRIES) {
                    throw IllegalStateException(
                        "프리셋 적용 중 채널 AI 행동 버전 채번이 동시 변경과 계속 충돌했어요. 잠시 후 다시 시도해 주세요.",
                        ex,
                    )
                }
            }
        }
    }

    private fun applyRoutingPolicySnapshot(
        sourceRevision: PresetRevisionEntity,
        targetGuildId: Long,
        targetChannelId: Long,
        channelAiId: Long,
        now: Instant,
    ) {
        val policy =
            routingPolicies.findByGuildIdAndChannelId(targetGuildId, targetChannelId)
                ?: ChannelAiRoutingPolicyEntity(guildId = targetGuildId, channelId = targetChannelId, createdAt = now)
        policy.channelAiId = channelAiId
        policy.responseMode = normalizeResponseMode(sourceRevision.responseMode)
        policy.preferredModel = sourceRevision.preferredModel?.trim()?.ifBlank { null }
        policy.minQualityTier = sourceRevision.minQualityTier.trim().ifBlank { "standard" }
        policy.maxCandidates = sourceRevision.maxCandidates.coerceIn(1, AI_NETWORK_MAX_CANDIDATES)
        policy.providerTagFilter = sourceRevision.providerTagFilter?.trim()?.ifBlank { null }
        policy.costGuard = sourceRevision.costGuard.trim().ifBlank { "provider_safe" }
        policy.updatedAt = now
        routingPolicies.save(policy)
    }

    private fun requireActivePreset(preset: AiPresetEntity) {
        require(preset.status != PresetStatus.REMOVED) { "removed preset cannot be changed" }
    }

    private fun requirePublishedPreset(published: PublishedPresetEntity) {
        require(published.status == PublishedPresetStatus.PUBLISHED) {
            "published preset is not importable or likable: ${published.status.wire}"
        }
    }

    /** 도메인 전이 가드: 허용되지 않은 전이는 거부(기존 코드가 실제 하는 전이는 [PresetStatus] 맵에서 전부 허용). */
    private fun AiPresetEntity.transitionTo(next: PresetStatus) {
        require(status.canTransitionTo(next)) { "illegal preset status transition: ${status.wire} -> ${next.wire}" }
        status = next
    }

    /** 도메인 전이 가드: 허용되지 않은 전이는 거부(기존 코드가 실제 하는 전이는 [PublishedPresetStatus] 맵에서 전부 허용). */
    private fun PublishedPresetEntity.transitionTo(next: PublishedPresetStatus) {
        require(status.canTransitionTo(next)) { "illegal published preset status transition: ${status.wire} -> ${next.wire}" }
        status = next
    }

    private fun requirePublishablePublicMetadata(vararg values: String?) {
        val text = values.joinToString("\n") { it.orEmpty() }
        require(!text.hasSensitiveMaterial()) {
            "published preset cannot include secrets or sensitive credentials"
        }
    }

    private fun requirePublishableRevision(revision: PresetRevisionEntity) {
        val text =
            listOf(
                revision.name,
                revision.purpose,
                revision.tone,
                revision.answerLength,
                revision.constitution.orEmpty(),
                revision.safetyLevel,
                revision.responseMode,
                revision.preferredModel.orEmpty(),
                revision.providerTagFilter.orEmpty(),
                revision.tags.orEmpty(),
                revision.costGuard,
                revision.knowledgeSlotNames.orEmpty(),
                revision.knowledgeGuide.orEmpty(),
                revision.exampleQuestions.orEmpty(),
                revision.changeSummary.orEmpty(),
            ).joinToString("\n")
        require(!text.hasSensitiveMaterial()) {
            "published preset cannot include secrets or sensitive credentials"
        }
    }

    private fun createRevision(
        preset: AiPresetEntity,
        revision: Int,
        behavior: PresetBehaviorInput,
        createdBy: Long?,
        now: Instant,
    ): PresetRevisionEntity = revisionFactory.createRevision(preset, revision, behavior, createdBy, now)

    private fun AiBehaviorVersionEntity.payloadHash(): String =
        sha256(
            listOf(
                channelAiId.toString(),
                version.toString(),
                purpose,
                tone,
                answerLength,
                constitution.orEmpty(),
                safetyLevel,
                changeSummary.orEmpty(),
                // ChannelAiCustomizationService.payloadHash 와 동일 필드 구성을 유지해야 한다(preset import 제안도
                // 같은 approveProposal 에서 해시 검증을 받기 때문). 자유 지침 컬럼 추가에 맞춰 같이 포함한다.
                customInstruction.orEmpty(),
            ).joinToString("\u001F"),
        )

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun uniqueSlug(
        title: String,
        presetId: Long,
    ): String {
        val base =
            title
                .lowercase()
                .replace(Regex("[^a-z0-9가-힣]+"), "-")
                .trim('-')
                .take(80)
                .ifBlank { "preset" }
        val preferred = "$base-$presetId"
        if (publishedPresets.findBySlug(preferred) == null) return preferred
        return "$base-$presetId-${Instant.now(clock).toEpochMilli()}"
    }

    private fun sanitizeText(
        value: String,
        maxLength: Int,
    ): String =
        value
            .trim()
            .replace(ContentSafety.SECRET_PATTERN, "[redacted]")
            .take(maxLength)
            .ifBlank { "no reason provided" }

    private fun normalizeReportReasonCode(value: String): String {
        val normalized =
            value
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9_\\-가-힣]+"), "_")
                .trim('_', '-')
        return when (normalized) {
            "unsafe", "unsafe_prompt", "위험", "위험한_프롬프트" -> "unsafe_prompt"
            "sensitive", "secret", "secrets", "sensitive_data", "민감정보" -> "sensitive_data"
            "spam", "abuse", "스팸" -> "spam"
            "low_quality", "quality", "품질" -> "low_quality"
            "copyright", "저작권" -> "copyright"
            "harmful", "harm", "유해" -> "harmful"
            "policy", "policy_violation", "정책" -> "policy_violation"
            "other", "기타", "" -> "other"
            else -> normalized.take(60).ifBlank { "other" }
        }
    }

    private fun normalizeResponseMode(value: String): String = revisionFactory.normalizeResponseMode(value)

    private fun String.hasSensitiveMaterial(): Boolean = with(contentSafety) { hasSensitiveMaterial() }

    private fun splitCsv(value: String?): List<String> = contentSafety.splitCsv(value)

    private fun splitLines(value: String?): List<String> = contentSafety.splitLines(value)

    private companion object {
        const val MAX_VERSION_RETRIES = 5
        const val REPORT_REVIEW_THRESHOLD = 1
        val CONFIRM_REQUIRED_CONFLICT_SEVERITIES = setOf("warning", "blocker")
    }
}

private data class AppliedPresetChannelAi(
    val channelAiId: Long,
    val behaviorVersionId: Long,
    val status: PresetImportStatus,
)
