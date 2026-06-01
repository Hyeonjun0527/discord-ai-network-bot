package com.discordassistant.central.network

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
    ): AiPresetEntity {
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
                    status = "draft",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        val revision = createRevision(preset, revision = 1, behavior = behavior, createdBy = ownerUserId, now = now)
        preset.currentRevisionId = revision.id
        preset.updatedAt = now
        return presets.save(preset)
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
    ): AiPresetEntity {
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

    @Transactional(readOnly = true)
    fun listGuildPresets(guildId: Long): List<PresetSummary> {
        featureGate.requirePresetEnabled()
        return presets
            .findByGuildId(guildId)
            .filter { it.status != "removed" }
            .sortedWith(compareByDescending<AiPresetEntity> { it.updatedAt }.thenBy { it.id })
            .map { it.toSummary() }
    }

    @Transactional(readOnly = true)
    fun listPublishedPresets(): List<PublishedPresetSummary> = searchPublishedPresets()

    @Transactional(readOnly = true)
    fun searchPublishedPresets(
        query: String? = null,
        category: String? = null,
        sort: String = "popular",
        limit: Int = 20,
    ): List<PublishedPresetSummary> {
        featureGate.requirePresetEnabled()
        val normalizedQuery = query.normalizedSearchToken()
        val normalizedCategory = category.normalizedSearchToken()
        val cappedLimit = limit.coerceIn(1, 100)
        val summaries =
            publishedPresets
                .findByStatusOrderByLikeCountDescPublishedAtDesc("published")
                .map { publishedSummary(it) }
                .filter { summary ->
                    normalizedQuery == null || summary.searchHaystack().contains(normalizedQuery)
                }.filter { summary ->
                    normalizedCategory == null || summary.category.orEmpty().lowercase() == normalizedCategory
                }
        return when (sort.trim().lowercase()) {
            "new", "latest", "recent" -> summaries.sortedByDescending { Instant.parse(it.publishedAt) }
            "imports", "import" ->
                summaries.sortedWith(
                    compareByDescending<PublishedPresetSummary> { it.importCount }
                        .thenByDescending { it.likeCount }
                        .thenByDescending { Instant.parse(it.publishedAt) },
                )
            "reports", "reported" ->
                summaries.sortedWith(
                    compareByDescending<PublishedPresetSummary> { it.reportCount }
                        .thenByDescending { it.likeCount }
                        .thenByDescending { Instant.parse(it.publishedAt) },
                )
            else ->
                summaries.sortedWith(
                    compareByDescending<PublishedPresetSummary> { it.likeCount }
                        .thenByDescending { it.importCount }
                        .thenByDescending { Instant.parse(it.publishedAt) },
                )
        }.take(cappedLimit)
    }

    @Transactional(readOnly = true)
    fun recommendedPublishedPresets(
        category: String? = null,
        limit: Int = 10,
    ): List<PresetRecommendation> {
        featureGate.requirePresetEnabled()
        val normalizedCategory = category.normalizedSearchToken()
        return publishedPresets
            .findByStatusOrderByLikeCountDescPublishedAtDesc("published")
            .map { publishedSummary(it) }
            .filter { it.reportCount == 0 }
            .filter { normalizedCategory == null || it.category.orEmpty().lowercase() == normalizedCategory }
            .map { it.toRecommendation() }
            .sortedWith(
                compareByDescending<PresetRecommendation> { it.score }
                    .thenByDescending { it.preset.likeCount }
                    .thenByDescending { it.preset.importCount }
                    .thenByDescending { Instant.parse(it.preset.publishedAt) },
            ).take(limit.coerceIn(1, 50))
    }

    @Transactional(readOnly = true)
    fun catalogFacets(): PresetCatalogFacets {
        featureGate.requirePresetEnabled()
        val summaries =
            publishedPresets
                .findByStatusOrderByLikeCountDescPublishedAtDesc("published")
                .map { publishedSummary(it) }
        return PresetCatalogFacets(
            totalPublished = summaries.size,
            totalLikes = summaries.sumOf { it.likeCount },
            totalImports = summaries.sumOf { it.importCount },
            categories = summaries.facetBy { it.category ?: "uncategorized" },
            safetyLevels = summaries.facetBy { it.safetyLevel ?: "unknown" },
            responseModes = summaries.facetBy { it.responseMode ?: "balanced" },
            qualityTiers = summaries.facetBy { it.minQualityTier ?: "standard" },
            topPresets =
                summaries
                    .filter { it.reportCount == 0 }
                    .sortedWith(
                        compareByDescending<PublishedPresetSummary> { it.likeCount }
                            .thenByDescending { it.importCount }
                            .thenByDescending { Instant.parse(it.publishedAt) },
                    ).take(5),
        )
    }

    @Transactional(readOnly = true)
    fun presetDetail(presetId: Long): PresetDetail {
        featureGate.requirePresetEnabled()
        val preset = presets.findById(presetId).orElseThrow { IllegalArgumentException("preset not found: $presetId") }
        requireActivePreset(preset)
        return PresetDetail(
            preset = preset.toSummary(),
            revisions = revisions.findByPresetIdOrderByRevisionDesc(preset.id).map { it.toSummary() },
        )
    }

    @Transactional(readOnly = true)
    fun importHistory(
        targetGuildId: Long,
        targetChannelId: Long? = null,
    ): List<PresetImportSummary> {
        featureGate.requirePresetEnabled()
        return imports
            .findByTargetGuildId(targetGuildId)
            .filter { targetChannelId == null || it.targetChannelId == targetChannelId }
            .sortedWith(compareByDescending<PresetImportEntity> { it.importedAt }.thenByDescending { it.id })
            .map { it.toSummary() }
    }

    @Transactional(readOnly = true)
    fun listReports(status: String = "open"): List<PresetReportSummary> {
        featureGate.requirePresetEnabled()
        return reports
            .findByStatus(status.trim().lowercase().ifBlank { "open" })
            .sortedWith(compareByDescending<PresetReportEntity> { it.createdAt }.thenBy { it.id })
            .map { it.toSummary() }
    }

    @Transactional(readOnly = true)
    fun moderationSummary(): PresetModerationSummary {
        featureGate.requirePresetEnabled()
        val published = publishedPresets.findAll()
        val reportRows = reports.findAll()
        val reportStatusCounts = reportRows.groupingBy { it.status.ifBlank { "unknown" } }.eachCount()
        val queue =
            published
                .map { preset -> moderationItem(preset) }
                .filter { it.riskCodes.isNotEmpty() }
                .sortedWith(
                    compareBy<PresetModerationQueueItem> { moderationSeverityRank(it) }
                        .thenByDescending { it.reportCount }
                        .thenByDescending { it.likeCount }
                        .thenBy { it.publishedPresetId },
                )
        val statusCounts = published.groupingBy { it.status.ifBlank { "unknown" } }.eachCount()
        return PresetModerationSummary(
            totalPublishedRows = published.size,
            activePublishedCount = statusCounts["published"] ?: 0,
            underReviewCount = statusCounts["under_review"] ?: 0,
            suspendedCount = statusCounts["suspended"] ?: 0,
            removedCount = statusCounts["removed"] ?: 0,
            openReportCount = reportStatusCounts["open"] ?: 0,
            reviewedReportCount = reportRows.count { it.status != "open" },
            statusCounts = statusCounts,
            reportStatusCounts = reportStatusCounts,
            queue = queue.take(50),
            nextActions = presetModerationNextActions(queue, reportStatusCounts),
        )
    }

    @Transactional(readOnly = true)
    fun publishedPresetDetail(publishedPresetId: Long): PublishedPresetDetail {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        requirePublishedPreset(published)
        val revision =
            revisions.findById(published.revisionId).orElseThrow {
                IllegalArgumentException("published revision not found: ${published.revisionId}")
            }
        val preset = presets.findById(published.presetId).orElse(null)
        return PublishedPresetDetail(
            published = published.toSummary(revision, preset),
            behavior = revision.toBehaviorSnapshot(),
        )
    }

    @Transactional
    fun updatePreset(
        presetId: Long,
        actorUserId: Long?,
        name: String?,
        summary: String?,
        category: String?,
        visibility: String?,
        behavior: PresetBehaviorInput?,
    ): AiPresetEntity {
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
        return presets.save(preset)
    }

    @Transactional
    fun publishPreset(
        presetId: Long,
        publisherUserId: Long?,
        title: String?,
        description: String?,
    ): PublishedPresetEntity {
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
        preset.status = "published"
        preset.visibility = "published"
        presets.save(preset)
        return publishedPresets.save(
            PublishedPresetEntity(
                presetId = preset.id,
                revisionId = revisionId,
                publisherGuildId = preset.guildId,
                publisherUserId = publisherUserId,
                slug = uniqueSlug(publishTitle.take(120), preset.id),
                title = publishTitle.take(120),
                description = publishDescription?.take(500),
                status = "published",
                publishedAt = Instant.now(clock),
            ),
        )
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
    ): PublishedPresetEntity {
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
        return publishedPresets.save(published)
    }

    @Transactional
    fun importPreset(
        publishedPresetId: Long,
        targetGuildId: Long,
        targetChannelId: Long?,
        importedBy: Long?,
        confirmConflicts: Boolean = false,
    ): PresetImportEntity {
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
                        knowledgeSlotNames = splitCsv(sourceRevision.knowledgeSlotNames),
                        knowledgeGuide = sourceRevision.knowledgeGuide,
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
        return imports.save(
            PresetImportEntity(
                publishedPresetId = publishedPresetId,
                targetGuildId = targetGuildId,
                targetChannelId = targetChannelId,
                importedBy = importedBy,
                importedPresetId = importedPreset.id,
                createdChannelAiId = applied?.channelAiId,
                createdBehaviorVersionId = applied?.behaviorVersionId,
                status = applied?.status ?: "imported",
                importedAt = now,
            ),
        )
    }

    @Transactional
    fun likePreset(
        publishedPresetId: Long,
        userId: Long,
    ): PublishedPresetEntity {
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
        return publishedPresets.save(published)
    }

    @Transactional
    fun unlikePreset(
        publishedPresetId: Long,
        userId: Long,
    ): PublishedPresetEntity {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        require(published.status !in setOf("removed", "unlisted")) { "${published.status} preset cannot be unliked" }
        reactions.findByPublishedPresetIdAndUserIdAndReaction(publishedPresetId, userId, "like")?.let { reaction ->
            reactions.delete(reaction)
            published.likeCount = (published.likeCount - 1).coerceAtLeast(0)
        }
        return publishedPresets.save(published)
    }

    @Transactional
    fun reportPreset(
        publishedPresetId: Long,
        reporterUserId: Long?,
        reason: String,
    ): PresetReportEntity {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        require(published.status !in setOf("removed", "unlisted")) { "${published.status} preset cannot be reported" }
        reporterUserId?.let { reporter ->
            reports.findByPublishedPresetIdAndReporterUserIdAndStatus(publishedPresetId, reporter, "open")?.let {
                return it
            }
        }
        published.reportCount += 1
        if (published.reportCount >= REPORT_REVIEW_THRESHOLD && published.status == "published") {
            published.status = "under_review"
        }
        publishedPresets.save(published)
        return reports.save(
            PresetReportEntity(
                publishedPresetId = publishedPresetId,
                reporterUserId = reporterUserId,
                reason = sanitizeText(reason, maxLength = 500),
                createdAt = Instant.now(clock),
            ),
        )
    }

    @Transactional
    fun deletePreset(presetId: Long): AiPresetEntity {
        featureGate.requirePresetEnabled()
        val preset = presets.findById(presetId).orElseThrow { IllegalArgumentException("preset not found: $presetId") }
        preset.status = "removed"
        preset.visibility = "removed"
        preset.updatedAt = Instant.now(clock)
        return presets.save(preset)
    }

    @Transactional
    fun deletePublishedPreset(publishedPresetId: Long): PublishedPresetEntity {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        published.status = "removed"
        return publishedPresets.save(published)
    }

    @Transactional
    fun unlistPublishedPreset(publishedPresetId: Long): PublishedPresetEntity {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        require(published.status != "removed") { "removed preset cannot be unlisted" }
        published.status = "unlisted"
        return publishedPresets.save(published)
    }

    @Transactional
    fun republishPreset(publishedPresetId: Long): PublishedPresetEntity {
        featureGate.requirePresetEnabled()
        val published =
            publishedPresets.findById(publishedPresetId).orElseThrow {
                IllegalArgumentException("published preset not found: $publishedPresetId")
            }
        require(published.status == "unlisted") { "only unlisted preset can be republished: ${published.status}" }
        val revision =
            revisions.findById(published.revisionId).orElseThrow {
                IllegalArgumentException("published revision not found: ${published.revisionId}")
            }
        requirePublishablePublicMetadata(published.title, published.description)
        requirePublishableRevision(revision)
        published.status = "published"
        return publishedPresets.save(published)
    }

    @Transactional
    fun reviewReport(
        reportId: Long,
        decision: String,
    ): PresetReportEntity {
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
        report.reviewedAt = Instant.now(clock)
        when (report.status) {
            "suspend", "suspended" -> published.status = "suspended"
            "remove", "removed" -> published.status = "removed"
            "dismiss", "dismissed" -> if (published.status == "under_review") published.status = "published"
        }
        publishedPresets.save(published)
        return reports.save(report)
    }

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
            costGuard = sourceRevision.costGuard,
            knowledgeSlotNames = splitCsv(sourceRevision.knowledgeSlotNames),
            knowledgeGuide = sourceRevision.knowledgeGuide,
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
        require(preset.status != "removed") { "removed preset cannot be changed" }
    }

    private fun requirePublishedPreset(published: PublishedPresetEntity) {
        require(published.status == "published") { "published preset is not importable or likable: ${published.status}" }
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
                revision.costGuard,
                revision.knowledgeSlotNames.orEmpty(),
                revision.knowledgeGuide.orEmpty(),
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
                costGuard = costGuard,
                knowledgeSlotNames = behavior.knowledgeSlotNames.normalizedKnowledgeSlots(),
                knowledgeGuide = behavior.knowledgeGuide.sanitizedKnowledgeGuide(),
                changeSummary = behavior.changeSummary?.trim()?.ifBlank { null },
                createdBy = createdBy,
                createdAt = now,
            ),
        )
    }

    private fun AiPresetEntity.toSummary(): PresetSummary =
        PresetSummary(
            id = id,
            guildId = guildId,
            ownerUserId = ownerUserId,
            name = name,
            summary = summary,
            category = category,
            visibility = visibility,
            status = status,
            currentRevisionId = currentRevisionId,
            updatedAt = updatedAt.toString(),
        )

    private fun PresetRevisionEntity.toSummary(): PresetRevisionSummary =
        PresetRevisionSummary(
            id = id,
            revision = revision,
            name = name,
            purpose = purpose,
            tone = tone,
            answerLength = answerLength,
            safetyLevel = safetyLevel,
            responseMode = responseMode,
            preferredModel = preferredModel,
            minQualityTier = minQualityTier,
            maxCandidates = maxCandidates,
            providerTagFilter = splitCsv(providerTagFilter),
            costGuard = costGuard,
            knowledgeSlotNames = splitCsv(knowledgeSlotNames),
            knowledgeGuide = knowledgeGuide,
            changeSummary = changeSummary,
            createdAt = createdAt.toString(),
        )

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

    private fun PresetRevisionEntity.toBehaviorSnapshot(): PresetBehaviorSnapshot =
        PresetBehaviorSnapshot(
            purpose = purpose.publicRequired(maxLength = 1000, fallback = REDACTED_PUBLIC_TEXT),
            tone = tone.publicRequired(maxLength = 160, fallback = "friendly"),
            answerLength = answerLength.publicRequired(maxLength = 80, fallback = "balanced"),
            constitution = constitution.publicOptional(maxLength = 3000),
            safetyLevel = safetyLevel.publicRequired(maxLength = 80, fallback = "standard"),
            responseMode = responseMode.publicRequired(maxLength = 80, fallback = "balanced"),
            preferredModel = preferredModel.publicOptional(maxLength = 160),
            minQualityTier = minQualityTier.publicRequired(maxLength = 80, fallback = "standard"),
            maxCandidates = maxCandidates,
            providerTagFilter = splitCsv(providerTagFilter).filterNot { it.hasSensitiveMaterial() },
            costGuard = costGuard.publicRequired(maxLength = 80, fallback = "provider_safe"),
            knowledgeSlotNames = splitCsv(knowledgeSlotNames).filterNot { it.hasSensitiveMaterial() },
            knowledgeGuide = knowledgeGuide.publicOptional(maxLength = 1000),
        )

    private fun PresetImportEntity.toSummary(): PresetImportSummary =
        PresetImportSummary(
            id = id,
            publishedPresetId = publishedPresetId,
            targetGuildId = targetGuildId,
            targetChannelId = targetChannelId,
            importedBy = importedBy,
            importedPresetId = importedPresetId,
            createdChannelAiId = createdChannelAiId,
            createdBehaviorVersionId = createdBehaviorVersionId,
            status = status,
            importedAt = importedAt.toString(),
            detachedCopy = importedPresetId != null,
        )

    private fun PresetReportEntity.toSummary(): PresetReportSummary =
        PresetReportSummary(
            id = id,
            publishedPresetId = publishedPresetId,
            reporterUserId = reporterUserId,
            reason = reason,
            status = status,
            createdAt = createdAt.toString(),
            reviewedAt = reviewedAt?.toString(),
        )

    private fun moderationItem(published: PublishedPresetEntity): PresetModerationQueueItem {
        val summary = publishedSummary(published)
        val riskCodes =
            buildList {
                if (summary.status == "under_review") add("under_review")
                if (summary.status == "suspended") add("suspended")
                if (summary.status == "removed") add("removed")
                if (summary.reportCount > 0) add("reported")
                if (summary.reportCount > 0 && summary.likeCount + summary.importCount >= 5) add("popular_reported")
                if (summary.safetyLevel.orEmpty().lowercase() in HIGH_RISK_SAFETY_LEVELS) add("high_safety_level")
            }.distinct()
        return PresetModerationQueueItem(
            publishedPresetId = summary.id,
            title = summary.title,
            status = summary.status,
            reportCount = summary.reportCount,
            likeCount = summary.likeCount,
            importCount = summary.importCount,
            safetyLevel = summary.safetyLevel,
            riskCodes = riskCodes,
            recommendedAction = presetModerationAction(summary.status, riskCodes),
        )
    }

    private fun presetModerationAction(
        status: String,
        riskCodes: List<String>,
    ): String =
        when {
            status == "removed" -> "removed 상태를 유지하고 카탈로그에는 노출하지 마세요."
            status == "suspended" -> "검수자가 수정 요청 또는 제거 결정을 내려야 합니다."
            "popular_reported" in riskCodes -> "인기 프리셋이 신고됐으므로 우선 검토하고 필요하면 일시 중단하세요."
            "reported" in riskCodes -> "신고 사유를 확인하고 dismiss/suspend/remove 중 하나로 처리하세요."
            "high_safety_level" in riskCodes -> "높은 안전 등급 프리셋은 게시 설명과 행동 스냅샷을 수동 검토하세요."
            else -> "추가 조치가 필요 없습니다."
        }

    private fun presetModerationNextActions(
        queue: List<PresetModerationQueueItem>,
        reportStatusCounts: Map<String, Int>,
    ): List<String> =
        buildList {
            if ((reportStatusCounts["open"] ?: 0) > 0) add("open 신고를 검토해 dismiss/suspend/remove 처리하세요.")
            if (queue.any { "popular_reported" in it.riskCodes }) add("추천/인기 프리셋 중 신고된 항목을 먼저 검토하세요.")
            if (queue.any { "high_safety_level" in it.riskCodes }) add("high/restricted safety 프리셋은 승인 없이 자동 추천하지 마세요.")
            if (queue.any { it.status == "suspended" }) add("suspended 프리셋은 수정 요청 또는 제거로 상태를 확정하세요.")
            if (isEmpty()) add("프리셋 검수 큐가 비어 있습니다.")
        }.distinct()

    private fun moderationSeverityRank(item: PresetModerationQueueItem): Int =
        when {
            item.status == "under_review" -> 0
            "popular_reported" in item.riskCodes -> 1
            item.status == "suspended" -> 2
            "reported" in item.riskCodes -> 3
            "high_safety_level" in item.riskCodes -> 4
            else -> 5
        }

    private fun publishedSummary(published: PublishedPresetEntity): PublishedPresetSummary {
        val revision = revisions.findById(published.revisionId).orElse(null)
        val preset = presets.findById(published.presetId).orElse(null)
        return published.toSummary(revision, preset)
    }

    private fun PublishedPresetEntity.toSummary(
        revision: PresetRevisionEntity?,
        preset: AiPresetEntity?,
    ): PublishedPresetSummary =
        PublishedPresetSummary(
            id = id,
            presetId = presetId,
            revisionId = revisionId,
            publisherGuildId = null,
            publisherUserId = null,
            publisherLabel = "공개 프리셋 작성자",
            slug = slug.publicSlug(id),
            title = title.publicRequired(maxLength = 120, fallback = REDACTED_PUBLIC_TITLE),
            description = description.publicOptional(maxLength = 500),
            status = status,
            category = (preset?.category).publicOptional(maxLength = 80),
            purpose = (revision?.purpose).publicOptional(maxLength = 1000),
            tone = (revision?.tone).publicOptional(maxLength = 160),
            safetyLevel = (revision?.safetyLevel).publicOptional(maxLength = 80),
            responseMode = (revision?.responseMode).publicOptional(maxLength = 80),
            preferredModel = (revision?.preferredModel).publicOptional(maxLength = 160),
            minQualityTier = (revision?.minQualityTier).publicOptional(maxLength = 80),
            likeCount = likeCount,
            importCount = importCount,
            reportCount = reportCount,
            publishedAt = publishedAt.toString(),
        )

    private fun PublishedPresetSummary.searchHaystack(): String =
        listOf(
            slug,
            title,
            description.orEmpty(),
            category.orEmpty(),
            purpose.orEmpty(),
            tone.orEmpty(),
            safetyLevel.orEmpty(),
            responseMode.orEmpty(),
            preferredModel.orEmpty(),
        ).joinToString("\n") { it.lowercase() }

    private fun String?.normalizedSearchToken(): String? =
        this
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    private fun List<PublishedPresetSummary>.facetBy(selector: (PublishedPresetSummary) -> String): List<PresetCatalogFacet> =
        groupingBy { selector(it).trim().lowercase().ifBlank { "unknown" } }
            .eachCount()
            .map { (value, count) -> PresetCatalogFacet(value = value, count = count) }
            .sortedWith(compareByDescending<PresetCatalogFacet> { it.count }.thenBy { it.value })

    private fun PublishedPresetSummary.toRecommendation(): PresetRecommendation {
        val safetyPenalty =
            when (safetyLevel.orEmpty().lowercase()) {
                "high", "restricted", "dangerous" -> 50
                "elevated", "medium" -> 12
                else -> 0
            }
        val reportPenalty = reportCount * 15
        val score = (likeCount * 3) + (importCount * 2) - safetyPenalty - reportPenalty
        val reasons =
            buildList {
                add("likes=$likeCount")
                add("imports=$importCount")
                if (safetyPenalty > 0) add("safetyPenalty=$safetyPenalty")
                if (reportPenalty > 0) add("reportPenalty=$reportPenalty")
            }
        return PresetRecommendation(preset = this, score = score, reasons = reasons)
    }

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

    private fun String.publicSlug(id: Long): String {
        val trimmed = trim()
        if (trimmed.hasSensitiveMaterial() || SENSITIVE_SLUG_PATTERN.containsMatchIn(trimmed)) return "preset-$id"
        return trimmed.take(120).ifBlank { "preset-$id" }
    }

    private fun String.hasSensitiveMaterial(): Boolean =
        KnowledgeSafety.containsSensitiveMaterial(this) || SECRET_PATTERN.containsMatchIn(this)

    private fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private companion object {
        const val REPORT_REVIEW_THRESHOLD = 1
        const val REDACTED_PUBLIC_TITLE = "비공개 프리셋"
        const val REDACTED_PUBLIC_TEXT = "[비공개 처리됨]"
        val SECRET_PATTERN = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
        val SENSITIVE_SLUG_PATTERN = Regex("""(?i)(password|passwd|token|api[-_]?key|secret|authorization|bearer)""")
        val HIGH_RISK_SAFETY_LEVELS = setOf("high", "restricted", "dangerous")
        val CONFIRM_REQUIRED_CONFLICT_SEVERITIES = setOf("warning", "blocker")
    }
}

private data class AppliedPresetChannelAi(
    val channelAiId: Long,
    val behaviorVersionId: Long,
    val status: String,
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
    val costGuard: String = "provider_safe",
    val knowledgeSlotNames: List<String> = emptyList(),
    val knowledgeGuide: String? = null,
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
    val costGuard: String,
    val knowledgeSlotNames: List<String>,
    val knowledgeGuide: String?,
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
    val costGuard: String,
    val knowledgeSlotNames: List<String>,
    val knowledgeGuide: String?,
)

data class PresetImportSummary(
    val id: Long,
    val publishedPresetId: Long,
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
    val status: String,
    val createdAt: String,
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
    val costGuard: String,
    val knowledgeSlotNames: List<String>,
    val knowledgeGuide: String?,
)
