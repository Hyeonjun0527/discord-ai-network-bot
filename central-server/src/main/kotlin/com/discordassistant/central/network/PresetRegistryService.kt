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
    fun listPublishedPresets(): List<PublishedPresetSummary> {
        featureGate.requirePresetEnabled()
        return publishedPresets
            .findByStatusOrderByLikeCountDescPublishedAtDesc("published")
            .map { published ->
                val revision = revisions.findById(published.revisionId).orElse(null)
                published.toSummary(revision)
            }
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
    fun listReports(status: String = "open"): List<PresetReportSummary> {
        featureGate.requirePresetEnabled()
        return reports
            .findByStatus(status.trim().lowercase().ifBlank { "open" })
            .sortedWith(compareByDescending<PresetReportEntity> { it.createdAt }.thenBy { it.id })
            .map { it.toSummary() }
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
        return PublishedPresetDetail(
            published = published.toSummary(revision),
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
        preset.status = "published"
        preset.visibility = "published"
        presets.save(preset)
        return publishedPresets.save(
            PublishedPresetEntity(
                presetId = preset.id,
                revisionId = revisionId,
                publisherGuildId = preset.guildId,
                publisherUserId = publisherUserId,
                title = title?.trim()?.ifBlank { null } ?: preset.name,
                description = description?.trim()?.ifBlank { null } ?: preset.summary,
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
        require(published.status != "removed") { "removed preset cannot be unliked" }
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
        require(published.status != "removed") { "removed preset cannot be reported" }
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
            title = published.title,
            description = published.description,
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
                    createdAt = now,
                ),
            )
        } else {
            savedChannel.activeBehaviorVersionId = behavior.id
            savedChannel.updatedAt = now
            channelAis.save(savedChannel)
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
        applyRoutingPolicySnapshot(sourceRevision, targetGuildId, targetChannelId, savedChannel.id, now)
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
        policy.maxCandidates = sourceRevision.maxCandidates.coerceIn(1, 5)
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
                revision.changeSummary.orEmpty(),
            ).joinToString("\n")
        require(!SECRET_PATTERN.containsMatchIn(text)) {
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
                maxCandidates = behavior.maxCandidates.coerceIn(1, 5),
                providerTagFilter = behavior.providerTagFilter.normalizedCsv(),
                costGuard = costGuard,
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
            purpose = purpose,
            tone = tone,
            answerLength = answerLength,
            constitution = constitution,
            safetyLevel = safetyLevel,
            responseMode = responseMode,
            preferredModel = preferredModel,
            minQualityTier = minQualityTier,
            maxCandidates = maxCandidates,
            providerTagFilter = splitCsv(providerTagFilter),
            costGuard = costGuard,
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

    private fun PublishedPresetEntity.toSummary(revision: PresetRevisionEntity?): PublishedPresetSummary =
        PublishedPresetSummary(
            id = id,
            presetId = presetId,
            revisionId = revisionId,
            publisherGuildId = null,
            publisherUserId = null,
            publisherLabel = "공개 프리셋 작성자",
            title = title,
            description = description,
            status = status,
            category = revision?.name,
            purpose = revision?.purpose,
            tone = revision?.tone,
            safetyLevel = revision?.safetyLevel,
            responseMode = revision?.responseMode,
            preferredModel = revision?.preferredModel,
            likeCount = likeCount,
            importCount = importCount,
            reportCount = reportCount,
            publishedAt = publishedAt.toString(),
        )

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

    private fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private companion object {
        const val REPORT_REVIEW_THRESHOLD = 1
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
    val title: String,
    val description: String?,
    val status: String,
    val category: String?,
    val purpose: String?,
    val tone: String?,
    val safetyLevel: String?,
    val responseMode: String?,
    val preferredModel: String?,
    val likeCount: Int,
    val importCount: Int,
    val reportCount: Int,
    val publishedAt: String,
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
)
