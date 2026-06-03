package com.discordassistant.central.network

import com.discordassistant.central.domain.PresetStatus
import com.discordassistant.central.domain.PublishedPresetStatus
import com.discordassistant.central.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalEntity
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.AiPresetEntity
import com.discordassistant.central.persistence.AiPresetRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.persistence.PresetImportEntity
import com.discordassistant.central.persistence.PresetImportRepository
import com.discordassistant.central.persistence.PresetReactionEntity
import com.discordassistant.central.persistence.PresetReactionRepository
import com.discordassistant.central.persistence.PresetReportEntity
import com.discordassistant.central.persistence.PresetReportRepository
import com.discordassistant.central.persistence.PresetRevisionEntity
import com.discordassistant.central.persistence.PresetRevisionRepository
import com.discordassistant.central.persistence.PublishedPresetEntity
import com.discordassistant.central.persistence.PublishedPresetRepository
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
    fun recommendedPublishedPresets(
        category: String? = null,
        limit: Int = 10,
    ): List<PresetRecommendation> = catalog.recommendedPublishedPresets(category, limit)

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
                    status = applied?.status ?: "imported",
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
            reports.findByPublishedPresetIdAndReporterUserIdAndStatus(publishedPresetId, reporter, "open")?.let {
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
        val normalized = decision.trim().lowercase().ifBlank { "reviewed" }
        report.status =
            when (normalized) {
                "dismissed" -> "dismiss"
                "removed" -> "remove"
                "suspended" -> "suspend"
                else -> normalized
            }
        report.reviewedBy = reviewerUserId
        report.reviewedAt = Instant.now(clock)
        when (report.status) {
            "suspend", "suspended" -> published.transitionTo(PublishedPresetStatus.SUSPENDED)
            "remove", "removed" -> published.transitionTo(PublishedPresetStatus.REMOVED)
            "dismiss", "dismissed" ->
                if (published.status == PublishedPresetStatus.UNDER_REVIEW) {
                    published.transitionTo(PublishedPresetStatus.PUBLISHED)
                }
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
            status = status,
        )

    private fun PresetReportEntity.toWriteResult(): PresetReportWriteResult =
        PresetReportWriteResult(
            id = id,
            status = status,
            reasonCode = reasonCode,
            reviewedBy = reviewedBy,
            reviewedAt = reviewedAt?.toString(),
        )

    private fun buildImportPreview(
        published: PublishedPresetEntity,
        sourceRevision: PresetRevisionEntity,
        targetGuildId: Long,
        targetChannelId: Long?,
    ): PresetImportPreview {
        val existingChannelAi = targetChannelId?.let { channelAis.findByGuildIdAndChannelId(targetGuildId, it) }
        val existingRouting = targetChannelId?.let { routingPolicies.findByGuildIdAndChannelId(targetGuildId, it) }
        val highRisk = sourceRevision.safetyLevel.lowercase() in HIGH_RISK_SAFETY_LEVELS
        val conflicts = mutableListOf<PresetImportConflict>()
        if (targetChannelId == null) {
            conflicts +=
                PresetImportConflict(
                    code = "no_target_channel_import_only",
                    severity = "info",
                    message = "대상 채널이 없어 프리셋 보관함에만 가져오고 채널 AI에는 적용하지 않습니다.",
                )
        }
        if (existingChannelAi != null) {
            conflicts +=
                PresetImportConflict(
                    code = "existing_channel_ai_behavior",
                    severity = "warning",
                    message = "대상 채널에 이미 AI 프로필/행동 버전이 있어 적용 시 새 버전으로 덮어씁니다.",
                )
        }
        if (existingRouting != null) {
            conflicts +=
                PresetImportConflict(
                    code = "existing_routing_policy",
                    severity = "warning",
                    message = "대상 채널에 이미 응답 모드/모델 라우팅 정책이 있어 프리셋 정책으로 교체됩니다.",
                )
        }
        if (sourceRevision.maxCandidates > 1) {
            conflicts +=
                PresetImportConflict(
                    code = "multi_candidate_fanout",
                    severity = if (sourceRevision.maxCandidates >= 4) "warning" else "info",
                    message = "이 프리셋은 여러 Provider 후보를 사용할 수 있어 Provider 부담이 증가할 수 있습니다.",
                )
        }
        if (highRisk) {
            conflicts +=
                PresetImportConflict(
                    code = "high_risk_requires_review",
                    severity = "blocker",
                    message = "안전 등급이 높은 프리셋이라 바로 활성화하지 않고 승인 요청으로 전환합니다.",
                )
        }
        val action =
            when {
                targetChannelId == null -> "import_only"
                highRisk -> "propose_review"
                existingChannelAi != null -> "overwrite_channel_ai"
                else -> "create_channel_ai"
            }
        return PresetImportPreview(
            publishedPresetId = published.id,
            revisionId = sourceRevision.id,
            targetGuildId = targetGuildId,
            targetChannelId = targetChannelId,
            action = action,
            conflicts = conflicts,
            willImportPresetCopy = true,
            willApplyToChannel = targetChannelId != null,
            willOverwriteChannelAi = existingChannelAi != null,
            willOverwriteRoutingPolicy = existingRouting != null,
            willCreateApprovalProposal = highRisk && targetChannelId != null,
            title = published.title.publicRequired(maxLength = 120, fallback = REDACTED_PUBLIC_TITLE),
            description = published.description.publicOptional(maxLength = 500),
            purpose = sourceRevision.purpose,
            tone = sourceRevision.tone,
            answerLength = sourceRevision.answerLength,
            safetyLevel = sourceRevision.safetyLevel,
            responseMode = sourceRevision.responseMode,
            preferredModel = sourceRevision.preferredModel,
            minQualityTier = sourceRevision.minQualityTier,
            maxCandidates = sourceRevision.maxCandidates,
            providerTagFilter = splitCsv(sourceRevision.providerTagFilter),
            tags = splitCsv(sourceRevision.tags),
            costGuard = sourceRevision.costGuard,
            knowledgeSlotNames = splitCsv(sourceRevision.knowledgeSlotNames),
            knowledgeGuide = sourceRevision.knowledgeGuide,
            exampleQuestions = splitLines(sourceRevision.exampleQuestions),
        )
    }

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
        val nextVersion = (behaviorVersions.findTopByChannelAiIdOrderByVersionDesc(savedChannel.id)?.version ?: 0) + 1
        val behavior =
            behaviorVersions.saveAndFlush(
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
                ),
            )
        val highRisk = sourceRevision.safetyLevel.lowercase() in HIGH_RISK_SAFETY_LEVELS
        val status = if (highRisk) "needs_review" else "applied"
        if (highRisk) {
            proposals.save(
                AiChangeProposalEntity(
                    guildId = targetGuildId,
                    channelId = targetChannelId,
                    channelAiId = savedChannel.id,
                    proposedBehaviorId = behavior.id,
                    status = "pending",
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
                summary = "publishedPreset=${published.id} revision=${sourceRevision.revision} status=$status",
                createdAt = now,
            ),
        )
        return AppliedPresetChannelAi(savedChannel.id, behavior.id, status)
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
    ): PresetRevisionEntity {
        val preferredModel =
            behavior.preferredModel
                ?.trim()
                ?.ifBlank { null }
                ?.take(160)
        val minQualityTier =
            behavior.minQualityTier
                .trim()
                .ifBlank { "standard" }
                .take(40)
        val costGuard =
            behavior.costGuard
                .trim()
                .ifBlank { "provider_safe" }
                .take(80)
        return revisions.save(
            PresetRevisionEntity(
                presetId = preset.id,
                revision = revision,
                name = preset.name,
                purpose = behavior.purpose.trim().ifBlank { "general_assistant" },
                tone = behavior.tone.trim().ifBlank { "friendly" },
                answerLength = behavior.answerLength.trim().ifBlank { "balanced" },
                constitution = behavior.constitution?.trim()?.ifBlank { null },
                safetyLevel = behavior.safetyLevel.trim().ifBlank { "standard" },
                responseMode = normalizeResponseMode(behavior.responseMode),
                preferredModel = preferredModel,
                minQualityTier = minQualityTier,
                maxCandidates = behavior.maxCandidates.coerceIn(1, AI_NETWORK_MAX_CANDIDATES),
                providerTagFilter = behavior.providerTagFilter.normalizedCsv(),
                tags = behavior.tags.normalizedPresetTags(),
                costGuard = costGuard,
                knowledgeSlotNames = behavior.knowledgeSlotNames.normalizedKnowledgeSlots(),
                knowledgeGuide = behavior.knowledgeGuide.sanitizedKnowledgeGuide(),
                exampleQuestions = behavior.exampleQuestions.normalizedExampleQuestions(),
                changeSummary = behavior.changeSummary?.trim()?.ifBlank { null },
                createdBy = createdBy,
                createdAt = now,
            ),
        )
    }

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
            .replace(SECRET_PATTERN, "[redacted]")
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

    private fun normalizeResponseMode(value: String): String =
        when (value.trim().lowercase()) {
            "fast", "빠른", "빠른 답변" -> "fast"
            "deep", "깊은", "깊은 답변" -> "deep"
            "saving", "economy", "절약", "절약 모드" -> "saving"
            else -> "balanced"
        }

    private fun List<String>.normalizedCsv(): String? =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
            .ifBlank { null }

    private fun List<String>.normalizedPresetTags(): String? =
        map { it.normalizedPresetTag() }
            .filter { it.isNotBlank() && !it.hasSensitiveMaterial() }
            .distinct()
            .take(12)
            .joinToString(",")
            .ifBlank { null }

    private fun String.normalizedPresetTag(): String =
        trim()
            .lowercase()
            .replace(Regex("\\s+"), "-")
            .take(40)

    private fun List<String>.normalizedKnowledgeSlots(): String? =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("\\s+"), " ").take(80) }
            .distinct()
            .take(10)
            .joinToString(",")
            .ifBlank { null }

    private fun String?.sanitizedKnowledgeGuide(): String? =
        this
            ?.trim()
            ?.replace(SECRET_PATTERN, "[redacted]")
            ?.take(1000)
            ?.ifBlank { null }

    private fun List<String>.normalizedExampleQuestions(): String? =
        map { it.trim().replace(Regex("\\s+"), " ").take(160) }
            .filter { it.isNotBlank() && !it.hasSensitiveMaterial() }
            .distinct()
            .take(5)
            .joinToString("\n")
            .ifBlank { null }

    private fun String?.publicOptional(maxLength: Int): String? {
        val trimmed = this?.trim()?.ifBlank { null } ?: return null
        if (trimmed.hasSensitiveMaterial()) return REDACTED_PUBLIC_TEXT
        return trimmed.take(maxLength)
    }

    private fun String.publicRequired(
        maxLength: Int,
        fallback: String,
    ): String {
        val trimmed = trim().ifBlank { return fallback }
        if (trimmed.hasSensitiveMaterial()) return fallback
        return trimmed.take(maxLength)
    }

    private fun String.hasSensitiveMaterial(): Boolean =
        KnowledgeSafety.containsSensitiveMaterial(this) || SECRET_PATTERN.containsMatchIn(this)

    private fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun splitLines(value: String?): List<String> =
        value
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

    private companion object {
        const val REPORT_REVIEW_THRESHOLD = 1
        const val REDACTED_PUBLIC_TITLE = "비공개 프리셋"
        const val REDACTED_PUBLIC_TEXT = "[비공개 처리됨]"
        val SECRET_PATTERN = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
        val HIGH_RISK_SAFETY_LEVELS = setOf("high", "restricted", "dangerous")
        val CONFIRM_REQUIRED_CONFLICT_SEVERITIES = setOf("warning", "blocker")
    }
}

private data class AppliedPresetChannelAi(
    val channelAiId: Long,
    val behaviorVersionId: Long,
    val status: String,
)

/**
 * write 결과 DTO. 컨트롤러/CommandService 가 JPA 엔티티 대신 이 값을 읽어 응답을 만든다
 * (web↛entity 누수 제거, 감사 2026-06-03 C). 필드는 컨트롤러가 기존에 엔티티에서 직접 읽던
 * 원시값(raw)을 그대로 담아 HTTP 응답 JSON 을 불변으로 유지한다(공개 마스킹 미적용).
 */
data class PresetWriteResult(
    val id: Long,
    val currentRevisionId: Long?,
    val status: String,
)

data class PublishedPresetWriteResult(
    val id: Long,
    val revisionId: Long,
    val status: String,
    val slug: String,
    val title: String,
    val description: String?,
    val likeCount: Int,
)

data class PresetImportResult(
    val id: Long,
    val importedPresetId: Long?,
    val sourceRevisionId: Long?,
    val createdChannelAiId: Long?,
    val createdBehaviorVersionId: Long?,
    val status: String,
)

data class PresetReportWriteResult(
    val id: Long,
    val status: String,
    val reasonCode: String,
    val reviewedBy: Long?,
    val reviewedAt: String?,
)

data class PresetBehaviorInput(
    val purpose: String = "general_assistant",
    val tone: String = "friendly",
    val answerLength: String = "balanced",
    val constitution: String? = null,
    val safetyLevel: String = "standard",
    val responseMode: String = "balanced",
    val preferredModel: String? = null,
    val minQualityTier: String = "standard",
    val maxCandidates: Int = 1,
    val providerTagFilter: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val costGuard: String = "provider_safe",
    val knowledgeSlotNames: List<String> = emptyList(),
    val knowledgeGuide: String? = null,
    val exampleQuestions: List<String> = emptyList(),
    val changeSummary: String? = null,
)

data class PresetSummary(
    val id: Long,
    val guildId: Long,
    val ownerUserId: Long?,
    val name: String,
    val summary: String?,
    val category: String,
    val visibility: String,
    val status: String,
    val currentRevisionId: Long?,
    val updatedAt: String,
)

data class PresetRevisionSummary(
    val id: Long,
    val revision: Int,
    val name: String,
    val purpose: String,
    val tone: String,
    val answerLength: String,
    val safetyLevel: String,
    val responseMode: String,
    val preferredModel: String?,
    val minQualityTier: String,
    val maxCandidates: Int,
    val providerTagFilter: List<String>,
    val tags: List<String>,
    val costGuard: String,
    val knowledgeSlotNames: List<String>,
    val knowledgeGuide: String?,
    val exampleQuestions: List<String>,
    val changeSummary: String?,
    val createdAt: String,
)

data class PresetDetail(
    val preset: PresetSummary,
    val revisions: List<PresetRevisionSummary>,
)

data class PublishedPresetSummary(
    val id: Long,
    val presetId: Long,
    val revisionId: Long,
    val publisherGuildId: Long?,
    val publisherUserId: Long?,
    val publisherLabel: String,
    val slug: String,
    val title: String,
    val description: String?,
    val status: String,
    val category: String?,
    val purpose: String?,
    val tone: String?,
    val safetyLevel: String?,
    val responseMode: String?,
    val preferredModel: String?,
    val minQualityTier: String?,
    val tags: List<String>,
    val likeCount: Int,
    val importCount: Int,
    val reportCount: Int,
    val publishedAt: String,
)

data class PresetRecommendation(
    val preset: PublishedPresetSummary,
    val score: Int,
    val reasons: List<String>,
)

data class PresetCatalogFacet(
    val value: String,
    val count: Int,
)

data class PresetCatalogFacets(
    val totalPublished: Int,
    val totalLikes: Int,
    val totalImports: Int,
    val categories: List<PresetCatalogFacet>,
    val tags: List<PresetCatalogFacet>,
    val safetyLevels: List<PresetCatalogFacet>,
    val responseModes: List<PresetCatalogFacet>,
    val qualityTiers: List<PresetCatalogFacet>,
    val topPresets: List<PublishedPresetSummary>,
)

data class PresetModerationSummary(
    val totalPublishedRows: Int,
    val activePublishedCount: Int,
    val underReviewCount: Int,
    val suspendedCount: Int,
    val removedCount: Int,
    val openReportCount: Int,
    val reviewedReportCount: Int,
    val statusCounts: Map<String, Int>,
    val reportStatusCounts: Map<String, Int>,
    val queue: List<PresetModerationQueueItem>,
    val nextActions: List<String>,
)

data class PresetModerationQueueItem(
    val publishedPresetId: Long,
    val title: String,
    val status: String,
    val reportCount: Int,
    val likeCount: Int,
    val importCount: Int,
    val safetyLevel: String?,
    val riskCodes: List<String>,
    val reportReasonCodes: Map<String, Int>,
    val recommendedAction: String,
)

data class PresetBehaviorSnapshot(
    val purpose: String,
    val tone: String,
    val answerLength: String,
    val constitution: String?,
    val safetyLevel: String,
    val responseMode: String,
    val preferredModel: String?,
    val minQualityTier: String,
    val maxCandidates: Int,
    val providerTagFilter: List<String>,
    val tags: List<String>,
    val costGuard: String,
    val knowledgeSlotNames: List<String>,
    val knowledgeGuide: String?,
    val exampleQuestions: List<String>,
)

data class PresetImportSummary(
    val id: Long,
    val publishedPresetId: Long,
    val sourceRevisionId: Long?,
    val targetGuildId: Long,
    val targetChannelId: Long?,
    val importedBy: Long?,
    val importedPresetId: Long?,
    val createdChannelAiId: Long?,
    val createdBehaviorVersionId: Long?,
    val status: String,
    val importedAt: String,
    val detachedCopy: Boolean,
)

data class PresetReportSummary(
    val id: Long,
    val publishedPresetId: Long,
    val reporterUserId: Long?,
    val reason: String,
    val reasonCode: String,
    val details: String?,
    val status: String,
    val createdAt: String,
    val reviewedBy: Long?,
    val reviewedAt: String?,
)

data class PublishedPresetDetail(
    val published: PublishedPresetSummary,
    val behavior: PresetBehaviorSnapshot,
)

data class PresetImportConflict(
    val code: String,
    val severity: String,
    val message: String,
)

data class PresetImportPreview(
    val publishedPresetId: Long,
    val revisionId: Long,
    val targetGuildId: Long,
    val targetChannelId: Long?,
    val action: String,
    val conflicts: List<PresetImportConflict>,
    val willImportPresetCopy: Boolean,
    val willApplyToChannel: Boolean,
    val willOverwriteChannelAi: Boolean,
    val willOverwriteRoutingPolicy: Boolean,
    val willCreateApprovalProposal: Boolean,
    val title: String,
    val description: String?,
    val purpose: String,
    val tone: String,
    val answerLength: String,
    val safetyLevel: String,
    val responseMode: String,
    val preferredModel: String?,
    val minQualityTier: String,
    val maxCandidates: Int,
    val providerTagFilter: List<String>,
    val tags: List<String>,
    val costGuard: String,
    val knowledgeSlotNames: List<String>,
    val knowledgeGuide: String?,
    val exampleQuestions: List<String>,
)
