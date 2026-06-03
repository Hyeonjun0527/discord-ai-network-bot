package com.discordassistant.central.network

import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_ANSWER_LENGTH
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_CONSTITUTION
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_PURPOSE
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_SAFETY_LEVEL
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_TONE
import com.discordassistant.central.domain.ProposalStatus
import com.discordassistant.central.persistence.AiAdminRoleEntity
import com.discordassistant.central.persistence.AiAdminRoleRepository
import com.discordassistant.central.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalEntity
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

@Service
class ChannelAiCustomizationService(
    private val channelAis: ChannelAiRepository,
    private val versions: AiBehaviorVersionRepository,
    private val proposals: AiChangeProposalRepository,
    private val audits: CustomizationAuditLogRepository,
    private val routingPolicies: ChannelAiRoutingPolicyRepository? = null,
    private val aiAdminRoles: AiAdminRoleRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun wizardOptions(): ChannelAiWizardOptions {
        featureGate.requireChannelAiEnabled()
        return ChannelAiWizardOptions(
            jobs =
                listOf(
                    ChannelAiWizardOption(
                        key = "development",
                        label = "개발 질문",
                        description = "에러 분석, 코드 리뷰, 테스트 작성을 돕는 채널 AI",
                        recommendedName = "코드냥",
                    ),
                    ChannelAiWizardOption(
                        key = "translation",
                        label = "번역",
                        description = "한국어/영어 번역과 문장 다듬기를 돕는 채널 AI",
                        recommendedName = "번역냥",
                    ),
                    ChannelAiWizardOption(
                        key = "meeting",
                        label = "회의록",
                        description = "회의 요약, 결정사항, 액션아이템을 정리하는 채널 AI",
                        recommendedName = "요약냥",
                    ),
                    ChannelAiWizardOption(
                        key = "announcement",
                        label = "공지 작성",
                        description = "운영진 안내문과 릴리즈 노트 초안을 돕는 채널 AI",
                        recommendedName = "공지냥",
                    ),
                    ChannelAiWizardOption(
                        key = "custom",
                        label = "자유 설정",
                        description = "채널 목적에 맞게 직접 역할을 입력하는 채널 AI",
                        recommendedName = "채널냥",
                    ),
                ),
            tones =
                listOf(
                    ChannelAiWizardOption("friendly", "친근하게", "부담 없이 설명하고 필요한 맥락을 덧붙입니다."),
                    ChannelAiWizardOption("professional", "전문적으로", "정확하고 차분한 운영/업무 말투로 답합니다."),
                    ChannelAiWizardOption("concise", "짧고 명확하게", "핵심과 다음 행동을 먼저 말합니다."),
                ),
            answerLengths =
                listOf(
                    ChannelAiWizardOption("short", "짧게", "빠르게 훑고 바로 실행할 수 있게 답합니다."),
                    ChannelAiWizardOption("balanced", "균형", "설명과 예시를 적당히 섞어 답합니다."),
                    ChannelAiWizardOption("long", "깊게", "복잡한 질문에 자세히 답하되 Provider 부하 검토가 필요할 수 있습니다."),
                ),
            safetyRules =
                listOf(
                    "민감정보(비밀번호, API 키, 토큰, 개인키, 개인정보)는 요구·저장·반복하지 않습니다.",
                    "확실하지 않으면 추측하지 않고 확인이 필요하다고 말합니다.",
                    "채널 목적에서 벗어난 질문은 범위를 확인한 뒤 답합니다.",
                    "긴 답변/위험 지시/큰 헌법 변경은 승인 대기열로 보낼 수 있습니다.",
                ),
        )
    }

    fun draftFromAnswers(
        job: String,
        tone: String,
        answerLength: String = "balanced",
        customName: String? = null,
    ): ChannelAiWizardDraft {
        featureGate.requireChannelAiEnabled()
        val jobPreset = jobPreset(job)
        val tonePreset = tonePreset(tone)
        val normalizedAnswerLength = normalizeAnswerLength(answerLength)
        val name = customName?.trim()?.takeIf { it.isNotBlank() }?.take(80) ?: jobPreset.name
        return ChannelAiWizardDraft(
            name = name,
            job = jobPreset.purpose,
            tone = tonePreset,
            answerLength = normalizedAnswerLength,
            constitution = constitutionFor(jobPreset.key, tonePreset, normalizedAnswerLength),
            preview = "저는 $name 입니다. ${jobPreset.preview} 답변은 $tonePreset, 길이는 $normalizedAnswerLength 기준으로 맞출게요.",
        )
    }

    @Transactional
    fun createFromWizard(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        actorRoleIds: Collection<Long> = emptyList(),
        actorIsGuildAdmin: Boolean = true,
        name: String,
        avatarUrl: String?,
        job: String,
        tone: String,
        answerLength: String,
        constitution: String?,
        requireApproval: Boolean,
        customInstruction: String? = null,
    ): ChannelAiWizardResult {
        featureGate.requireChannelAiEnabled()
        requireCanManageChannelAi(guildId, channelId, actorUserId, actorRoleIds, actorIsGuildAdmin, "wizard_create")
        val now = Instant.now(clock)
        val channelAi =
            channelAis.findByGuildIdAndChannelId(guildId, channelId)
                ?: ChannelAiEntity(guildId = guildId, channelId = channelId, source = "wizard", createdAt = now)
        channelAi.displayName = name.trim().take(80).ifBlank { "냥시스턴트" }
        channelAi.avatarUrl = avatarUrl?.trim()?.ifBlank { null }
        channelAi.updatedAt = now
        val savedChannel = channelAis.saveAndFlush(channelAi)

        val previous = savedChannel.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(savedChannel.id, it) }
        val nextVersion = (versions.findTopByChannelAiIdOrderByVersionDesc(savedChannel.id)?.version ?: 0) + 1
        val behavior =
            versions.saveAndFlush(
                AiBehaviorVersionEntity(
                    channelAiId = savedChannel.id,
                    version = nextVersion,
                    purpose = normalize(job, previous?.purpose, DEFAULT_CHANNEL_AI_PURPOSE, 200),
                    tone = normalize(tone, previous?.tone, DEFAULT_CHANNEL_AI_TONE, 80),
                    answerLength = normalize(answerLength, previous?.answerLength, DEFAULT_CHANNEL_AI_ANSWER_LENGTH, 40),
                    constitution = normalizeOptional(constitution, previous?.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION, 2000),
                    safetyLevel = previous?.safetyLevel ?: DEFAULT_CHANNEL_AI_SAFETY_LEVEL,
                    customInstruction = normalizeOptional(customInstruction, previous?.customInstruction, 2000),
                    createdBy = actorUserId,
                    createdAt = now,
                    changeSummary = "created from channel AI wizard",
                ),
            )
        val approvalDecision = approvalDecision(requireApproval, behavior, channelAi.displayName)
        val status = if (approvalDecision.required) ProposalStatus.PENDING else ProposalStatus.APPROVED
        if (!approvalDecision.required) {
            savedChannel.activeBehaviorVersionId = behavior.id
            savedChannel.updatedAt = now
            channelAis.save(savedChannel)
        }
        val proposal =
            proposals.save(
                AiChangeProposalEntity(
                    guildId = guildId,
                    channelId = channelId,
                    channelAiId = savedChannel.id,
                    proposedBehaviorId = behavior.id,
                    status = status,
                    requestedBy = actorUserId,
                    reason = approvalDecision.reason ?: "channel AI wizard",
                    payloadHash = behavior.payloadHash(),
                    createdAt = now,
                    reviewedAt = if (approvalDecision.required) null else now,
                    reviewedBy = if (approvalDecision.required) null else actorUserId,
                ),
            )
        audit(
            guildId = guildId,
            channelId = channelId,
            actorUserId = actorUserId,
            action = if (approvalDecision.required) "propose" else "publish",
            targetType = "ai_behavior_version",
            targetId = behavior.id,
            summary = "wizard created v${behavior.version} status=${status.wire} reason=${approvalDecision.reason ?: "none"}",
        )
        return ChannelAiWizardResult(savedChannel.id, behavior.id, behavior.version, proposal.id, status.wire, approvalDecision.reason)
    }

    @Transactional
    fun rollbackToVersion(
        guildId: Long,
        channelId: Long,
        targetVersion: Int,
        actorUserId: Long?,
        actorRoleIds: Collection<Long> = emptyList(),
        actorIsGuildAdmin: Boolean = true,
        requireApproval: Boolean,
        reason: String?,
    ): ChannelAiWizardResult {
        featureGate.requireChannelAiEnabled()
        requireCanManageChannelAi(guildId, channelId, actorUserId, actorRoleIds, actorIsGuildAdmin, "rollback")
        val now = Instant.now(clock)
        val channelAi =
            channelAis.findByGuildIdAndChannelId(guildId, channelId)
                ?: throw IllegalArgumentException("channel ai not found: guild=$guildId channel=$channelId")
        val target =
            versions
                .findByChannelAiIdOrderByVersionDesc(channelAi.id)
                .firstOrNull { it.version == targetVersion }
                ?: throw IllegalArgumentException("behavior version not found: channelAi=${channelAi.id} version=$targetVersion")
        val nextVersion = (versions.findTopByChannelAiIdOrderByVersionDesc(channelAi.id)?.version ?: 0) + 1
        val rollbackBehavior =
            versions.saveAndFlush(
                AiBehaviorVersionEntity(
                    channelAiId = channelAi.id,
                    version = nextVersion,
                    purpose = target.purpose,
                    tone = target.tone,
                    answerLength = target.answerLength,
                    constitution = target.constitution,
                    safetyLevel = target.safetyLevel,
                    customInstruction = target.customInstruction,
                    createdBy = actorUserId,
                    createdAt = now,
                    changeSummary = "rollback to v${target.version}: ${reason?.trim()?.take(200) ?: "no reason"}",
                ),
            )
        val approvalDecision = approvalDecision(requireApproval, rollbackBehavior, channelAi.displayName)
        val status = if (approvalDecision.required) ProposalStatus.PENDING else ProposalStatus.APPROVED
        if (!approvalDecision.required) {
            channelAi.activeBehaviorVersionId = rollbackBehavior.id
            channelAi.updatedAt = now
            channelAis.save(channelAi)
        }
        val proposal =
            proposals.save(
                AiChangeProposalEntity(
                    guildId = guildId,
                    channelId = channelId,
                    channelAiId = channelAi.id,
                    proposedBehaviorId = rollbackBehavior.id,
                    status = status,
                    requestedBy = actorUserId,
                    reviewedBy = if (approvalDecision.required) null else actorUserId,
                    reason = approvalDecision.reason ?: reason?.trim()?.take(500) ?: "rollback to v${target.version}",
                    payloadHash = rollbackBehavior.payloadHash(),
                    createdAt = now,
                    reviewedAt = if (approvalDecision.required) null else now,
                ),
            )
        audit(
            guildId = guildId,
            channelId = channelId,
            actorUserId = actorUserId,
            action = if (approvalDecision.required) "rollback_propose" else "rollback_publish",
            targetType = "ai_behavior_version",
            targetId = rollbackBehavior.id,
            summary = "rollback copied v${target.version} into v${rollbackBehavior.version} status=${status.wire}",
        )
        return ChannelAiWizardResult(
            channelAiId = channelAi.id,
            behaviorVersionId = rollbackBehavior.id,
            version = rollbackBehavior.version,
            proposalId = proposal.id,
            status = status.wire,
            approvalReason = approvalDecision.reason,
        )
    }

    /**
     * 채널 AI 자유 지침(custom instruction)만 바꾼 **새 behavior 버전 제안**을 만든다.
     * 기존 활성(또는 최신) behavior 의 슬롯(purpose/tone/answerLength/constitution/safetyLevel)을 그대로 복사하고
     * customInstruction 만 교체한다(슬롯=가드레일은 보존, 자유 지침=색깔만 갱신).
     * 채널 AI 가 아직 없으면 베이스가 없어 제안을 만들 수 없으므로 에러로 안내한다.
     */
    @Transactional
    fun proposeCustomInstruction(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        actorRoleIds: Collection<Long> = emptyList(),
        actorIsGuildAdmin: Boolean = true,
        customInstruction: String?,
        requireApproval: Boolean,
    ): ChannelAiWizardResult {
        featureGate.requireChannelAiEnabled()
        requireCanManageChannelAi(guildId, channelId, actorUserId, actorRoleIds, actorIsGuildAdmin, "set_custom_instruction")
        val now = Instant.now(clock)
        val channelAi =
            channelAis.findByGuildIdAndChannelId(guildId, channelId)
                ?: throw IllegalArgumentException(
                    "이 채널에는 아직 채널 AI가 없어요. `/llm-channel-profile` 또는 `/ai-onboard` 로 먼저 채널 AI를 만든 뒤 자유 지침을 추가하세요.",
                )
        val base =
            channelAi.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(channelAi.id, it) }
                ?: versions.findTopByChannelAiIdOrderByVersionDesc(channelAi.id)
                ?: throw IllegalArgumentException(
                    "이 채널 AI에는 적용된 행동 버전이 없어요. `/llm-channel-profile` 로 먼저 설정한 뒤 자유 지침을 추가하세요.",
                )
        val nextVersion = (versions.findTopByChannelAiIdOrderByVersionDesc(channelAi.id)?.version ?: 0) + 1
        val behavior =
            versions.saveAndFlush(
                AiBehaviorVersionEntity(
                    channelAiId = channelAi.id,
                    version = nextVersion,
                    purpose = base.purpose,
                    tone = base.tone,
                    answerLength = base.answerLength,
                    constitution = base.constitution,
                    safetyLevel = base.safetyLevel,
                    customInstruction = normalizeOptional(customInstruction, null, 2000),
                    createdBy = actorUserId,
                    createdAt = now,
                    changeSummary = "custom instruction updated",
                ),
            )
        val approvalDecision = approvalDecision(requireApproval, behavior, channelAi.displayName)
        val status = if (approvalDecision.required) ProposalStatus.PENDING else ProposalStatus.APPROVED
        if (!approvalDecision.required) {
            channelAi.activeBehaviorVersionId = behavior.id
            channelAi.updatedAt = now
            channelAis.save(channelAi)
        }
        val proposal =
            proposals.save(
                AiChangeProposalEntity(
                    guildId = guildId,
                    channelId = channelId,
                    channelAiId = channelAi.id,
                    proposedBehaviorId = behavior.id,
                    status = status,
                    requestedBy = actorUserId,
                    reviewedBy = if (approvalDecision.required) null else actorUserId,
                    reason = approvalDecision.reason ?: "custom instruction update",
                    payloadHash = behavior.payloadHash(),
                    createdAt = now,
                    reviewedAt = if (approvalDecision.required) null else now,
                ),
            )
        audit(
            guildId = guildId,
            channelId = channelId,
            actorUserId = actorUserId,
            action = if (approvalDecision.required) "instruction_propose" else "instruction_publish",
            targetType = "ai_behavior_version",
            targetId = behavior.id,
            summary = "custom instruction v${behavior.version} status=${status.wire} reason=${approvalDecision.reason ?: "none"}",
        )
        return ChannelAiWizardResult(channelAi.id, behavior.id, behavior.version, proposal.id, status.wire, approvalDecision.reason)
    }

    @Transactional
    fun approveProposal(
        proposalId: Long,
        reviewerUserId: Long?,
        reviewerRoleIds: Collection<Long> = emptyList(),
        reviewerIsGuildAdmin: Boolean = true,
        reason: String? = null,
    ): AiChangeProposalReview {
        featureGate.requireChannelAiEnabled()
        val proposal = proposals.findById(proposalId).orElseThrow { IllegalArgumentException("proposal not found: $proposalId") }
        require(proposal.status == ProposalStatus.PENDING) { "pending proposal only can be approved" }
        requireCanManageChannelAi(proposal.guildId, proposal.channelId, reviewerUserId, reviewerRoleIds, reviewerIsGuildAdmin, "approve")
        val channelAiId = proposal.channelAiId ?: throw IllegalArgumentException("proposal has no channel ai")
        val behaviorId = proposal.proposedBehaviorId ?: throw IllegalArgumentException("proposal has no behavior")
        val channelAi = channelAis.findById(channelAiId).orElseThrow { IllegalArgumentException("channel ai not found: $channelAiId") }
        val behavior =
            versions.findByChannelAiIdAndId(channelAiId, behaviorId)
                ?: throw IllegalArgumentException("behavior not found: $behaviorId")
        if (proposal.payloadHash != null && proposal.payloadHash != behavior.payloadHash()) {
            proposal.transitionTo(ProposalStatus.STALE)
            proposal.reviewedBy = reviewerUserId
            proposal.reviewedAt = Instant.now(clock)
            proposal.reason = "proposal payload changed after review request"
            proposals.save(proposal)
            audit(
                guildId = proposal.guildId,
                channelId = proposal.channelId,
                actorUserId = reviewerUserId,
                action = "stale_payload",
                targetType = "ai_change_proposal",
                targetId = proposal.id,
                summary = "blocked approval because proposed behavior payload changed",
            )
            throw IllegalStateException("proposal payload changed after review request; create a new proposal")
        }
        val now = Instant.now(clock)
        channelAi.activeBehaviorVersionId = behaviorId
        channelAi.updatedAt = now
        channelAis.save(channelAi)
        applyRoutingSnapshot(proposal, channelAi.id, now)
        proposal.transitionTo(ProposalStatus.APPROVED)
        proposal.reviewedBy = reviewerUserId
        proposal.reason = reason?.trim()?.take(500) ?: proposal.reason
        proposal.reviewedAt = now
        val saved = proposals.save(proposal)
        audit(
            guildId = proposal.guildId,
            channelId = proposal.channelId,
            actorUserId = reviewerUserId,
            action = "approve",
            targetType = "ai_behavior_version",
            targetId = behaviorId,
            summary = proposal.reason ?: "approved v${behavior.version}",
        )
        return saved.toReview()
    }

    private fun applyRoutingSnapshot(
        proposal: AiChangeProposalEntity,
        channelAiId: Long,
        now: Instant,
    ) {
        val repository = routingPolicies ?: return
        val snapshot = ChannelAiRoutingSnapshot.decode(proposal.routingSnapshot) ?: return
        val policy =
            repository.findByGuildIdAndChannelId(proposal.guildId, proposal.channelId)
                ?: ChannelAiRoutingPolicyEntity(guildId = proposal.guildId, channelId = proposal.channelId, createdAt = now)
        snapshot.applyTo(policy, channelAiId, now)
        repository.save(policy)
    }

    @Transactional
    fun rejectProposal(
        proposalId: Long,
        reviewerUserId: Long?,
        reviewerRoleIds: Collection<Long> = emptyList(),
        reviewerIsGuildAdmin: Boolean = true,
        reason: String?,
    ): AiChangeProposalReview {
        featureGate.requireChannelAiEnabled()
        val proposal = proposals.findById(proposalId).orElseThrow { IllegalArgumentException("proposal not found: $proposalId") }
        require(proposal.status == ProposalStatus.PENDING) { "pending proposal only can be rejected" }
        requireCanManageChannelAi(proposal.guildId, proposal.channelId, reviewerUserId, reviewerRoleIds, reviewerIsGuildAdmin, "reject")
        proposal.transitionTo(ProposalStatus.REJECTED)
        proposal.reviewedBy = reviewerUserId
        proposal.reviewedAt = Instant.now(clock)
        proposal.reason = reason?.trim()?.take(500) ?: proposal.reason
        val saved = proposals.save(proposal)
        audit(
            guildId = proposal.guildId,
            channelId = proposal.channelId,
            actorUserId = reviewerUserId,
            action = "reject",
            targetType = "ai_change_proposal",
            targetId = proposal.id,
            summary = proposal.reason ?: "rejected",
        )
        return saved.toReview()
    }

    @Transactional
    fun replaceAiAdminRoles(
        guildId: Long,
        roleIds: Collection<Long>,
        actorUserId: Long?,
        actorRoleIds: Collection<Long> = emptyList(),
        actorIsGuildAdmin: Boolean = true,
    ): AiAdminRolePolicy {
        featureGate.requireChannelAiEnabled()
        requireCanManageChannelAi(guildId, 0, actorUserId, actorRoleIds, actorIsGuildAdmin, "replace_ai_admin_roles")
        val now = Instant.now(clock)
        val normalized = roleIds.filter { it > 0 }.distinct().sorted()
        aiAdminRoles?.deleteByGuildId(guildId)
        normalized.forEach { roleId ->
            aiAdminRoles?.save(
                AiAdminRoleEntity(
                    guildId = guildId,
                    roleId = roleId,
                    createdBy = actorUserId,
                    createdAt = now,
                ),
            )
        }
        audit(
            guildId = guildId,
            channelId = 0,
            actorUserId = actorUserId,
            action = "replace_ai_admin_roles",
            targetType = "ai_admin_role",
            targetId = null,
            summary = "roles=${normalized.joinToString(",").ifBlank { "fallback_to_discord_admin" }}",
        )
        return AiAdminRolePolicy(guildId = guildId, roleIds = normalized, protectedMode = normalized.isNotEmpty())
    }

    fun aiAdminRolePolicy(guildId: Long): AiAdminRolePolicy {
        featureGate.requireChannelAiEnabled()
        val roles = aiAdminRoleIds(guildId)
        return AiAdminRolePolicy(guildId = guildId, roleIds = roles, protectedMode = roles.isNotEmpty())
    }

    fun canManageChannelAi(
        guildId: Long,
        actorRoleIds: Collection<Long>,
        actorIsGuildAdmin: Boolean,
    ): AiAdminAccessDecision {
        val requiredRoles = aiAdminRoleIds(guildId)
        if (requiredRoles.isEmpty()) {
            return if (actorIsGuildAdmin) {
                AiAdminAccessDecision(true, "discord_admin_fallback", emptyList())
            } else {
                AiAdminAccessDecision(false, "discord_admin_required", emptyList())
            }
        }
        val actorRoles = actorRoleIds.toSet()
        val matched = requiredRoles.filter { it in actorRoles }
        return if (matched.isNotEmpty()) {
            AiAdminAccessDecision(true, "ai_admin_role_matched", requiredRoles, matched)
        } else {
            AiAdminAccessDecision(false, "ai_admin_role_required", requiredRoles)
        }
    }

    fun requireCanManageChannelAi(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        actorRoleIds: Collection<Long>,
        actorIsGuildAdmin: Boolean,
        action: String,
    ): AiAdminAccessDecision {
        val decision = canManageChannelAi(guildId, actorRoleIds, actorIsGuildAdmin)
        if (decision.allowed) return decision
        audit(
            guildId = guildId,
            channelId = channelId,
            actorUserId = actorUserId,
            action = "ai_admin_denied",
            targetType = "channel_ai_permission",
            targetId = null,
            summary = "$action denied: ${decision.reason}",
        )
        throw IllegalStateException(decision.userMessage())
    }

    fun proposalReviewSummary(
        guildId: Long,
        limit: Int = 20,
    ): ChannelAiProposalReviewSummary {
        featureGate.requireChannelAiEnabled()
        val all = proposals.findByGuildIdOrderByCreatedAtDesc(guildId)
        val pending = all.filter { it.status == ProposalStatus.PENDING }
        val statusCounts = all.groupingBy { it.status.wire }.eachCount()
        val reasonCounts =
            all
                .mapNotNull { it.reason?.trim()?.takeIf { reason -> reason.isNotBlank() } }
                .groupingBy { it }
                .eachCount()
                .toList()
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .associate { it.first to it.second }
        val staleCount = statusCounts["stale"] ?: 0
        val rejectedCount = statusCounts["rejected"] ?: 0
        val riskCodes =
            buildList {
                if (pending.isNotEmpty()) add("pending_review_required")
                if (staleCount > 0) add("stale_payload_detected")
                if (rejectedCount > 0) add("recent_rejections")
                if (pending.any { it.reason?.contains("risk", ignoreCase = true) == true || it.reason?.contains("위험") == true }) {
                    add("risky_instruction_pending")
                }
            }.distinct()
        val nextActions =
            buildList {
                if (pending.isNotEmpty()) add("AI 관리자 역할이 pending 변경을 승인하거나 거절해야 합니다.")
                if (staleCount > 0) add("stale 제안은 다시 생성해 검토해야 합니다.")
                if (rejectedCount > 0) add("거절 사유를 반영해 새 버전을 제안하세요.")
                if (isEmpty()) add("현재 검토가 필요한 AI 설정 변경은 없습니다.")
            }.distinct()
        return ChannelAiProposalReviewSummary(
            guildId = guildId,
            totalProposalCount = all.size,
            pendingProposalCount = pending.size,
            approvedProposalCount = statusCounts["approved"] ?: 0,
            rejectedProposalCount = rejectedCount,
            staleProposalCount = staleCount,
            statusCounts = statusCounts,
            reasonCounts = reasonCounts,
            riskCodes = riskCodes,
            nextActions = nextActions,
            pendingItems = pending.take(limit.coerceIn(1, 50)).map { it.toReviewItem() },
            recentItems = all.take(limit.coerceIn(1, 50)).map { it.toReviewItem() },
        )
    }

    fun pendingProposals(guildId: Long): List<PendingProposalView> {
        featureGate.requireChannelAiEnabled()
        return proposals.findByGuildIdAndStatus(guildId, ProposalStatus.PENDING).map { it.toPendingView() }
    }

    /** 현재 활성(또는 최신) behavior 의 자유 지침을 반환한다. 채널 AI/지침이 없으면 null. */
    fun currentCustomInstruction(
        guildId: Long,
        channelId: Long,
    ): String? {
        featureGate.requireChannelAiEnabled()
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId) ?: return null
        val behavior =
            channelAi.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(channelAi.id, it) }
                ?: versions.findTopByChannelAiIdOrderByVersionDesc(channelAi.id)
        return behavior?.customInstruction?.trim()?.takeIf { it.isNotBlank() }
    }

    fun channelHistory(
        guildId: Long,
        channelId: Long,
    ): ChannelAiHistory {
        featureGate.requireChannelAiEnabled()
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val behaviorVersions = channelAi?.let { versions.findByChannelAiIdOrderByVersionDesc(it.id) } ?: emptyList()
        val proposalHistory = proposals.findByGuildIdAndChannelIdOrderByCreatedAtDesc(guildId, channelId)
        val auditHistory = audits.findTop10ByGuildIdAndChannelIdOrderByCreatedAtDesc(guildId, channelId)
        return ChannelAiHistory(
            channelAi = channelAi?.let { ChannelAiHistoryHeader(id = it.id, activeBehaviorVersionId = it.activeBehaviorVersionId) },
            versions =
                behaviorVersions.map {
                    ChannelAiBehaviorVersionView(
                        id = it.id,
                        version = it.version,
                        purpose = it.purpose,
                        tone = it.tone,
                        answerLength = it.answerLength,
                        createdAt = it.createdAt.toString(),
                    )
                },
            proposals =
                proposalHistory.map {
                    ChannelAiProposalView(
                        id = it.id,
                        status = it.status.wire,
                        proposedBehaviorId = it.proposedBehaviorId,
                        requestedBy = it.requestedBy,
                        reviewedBy = it.reviewedBy,
                    )
                },
            audits =
                auditHistory.map {
                    ChannelAiAuditView(action = it.action, targetType = it.targetType, targetId = it.targetId)
                },
        )
    }

    fun promptPreview(
        guildId: Long,
        channelId: Long,
        userQuestion: String,
        ragContextText: String? = null,
    ): ChannelAiPromptPreview {
        featureGate.requireChannelAiEnabled()
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val behavior =
            channelAi?.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(channelAi.id, it) }
                ?: channelAi?.let { versions.findTopByChannelAiIdOrderByVersionDesc(it.id) }
        val name = channelAi?.displayName?.trim()?.takeIf { it.isNotBlank() } ?: "냥시스턴트"
        val purpose = behavior?.purpose ?: DEFAULT_CHANNEL_AI_PURPOSE
        val tone = behavior?.tone ?: DEFAULT_CHANNEL_AI_TONE
        val answerLength = behavior?.answerLength ?: DEFAULT_CHANNEL_AI_ANSWER_LENGTH
        val constitution = behavior?.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION
        val customInstruction = behavior?.customInstruction?.trim()?.takeIf { it.isNotBlank() }
        val sensitive = userQuestion.looksSensitive()
        val sanitizedQuestion = userQuestion.trim().take(PROMPT_USER_QUESTION_MAX)
        val rag = ragContextText?.trim()?.take(PROMPT_RAG_CONTEXT_MAX)?.ifBlank { null }
        val sections =
            buildList {
                add("safety")
                add("identity")
                if (customInstruction != null) add("custom_instruction")
                add("behavior")
                if (rag != null && !sensitive) add("rag_context")
                add("user_question")
            }
        val systemPrompt =
            buildString {
                appendLine("[우선순위 1: 안전]")
                appendLine("민감정보(비밀번호, API 키, 토큰, 개인키, 개인정보)는 요구·저장·반복하지 말고 즉시 경고합니다.")
                if (sensitive) appendLine("현재 사용자 질문에 민감정보로 보이는 내용이 있으므로 RAG/도구 사용보다 경고와 안전 안내를 우선합니다.")
                appendLine()
                appendLine("[우선순위 2: 채널 AI 정체성]")
                appendLine("이름: $name")
                appendLine("역할: $purpose")
                appendLine("말투: $tone")
                appendLine("답변 길이: $answerLength")
                if (customInstruction != null) {
                    appendLine()
                    appendLine("[우선순위 2.5: 자유 지침]")
                    appendLine("아래 지침은 채널 AI의 색깔/페르소나입니다. 단, 위 안전 규칙과 충돌하면 안전 규칙이 우선합니다.")
                    appendLine(customInstruction)
                }
                appendLine()
                appendLine("[우선순위 3: AI 헌법]")
                appendLine(constitution)
                if (rag != null && !sensitive) {
                    appendLine()
                    appendLine("[우선순위 4: 채널 지식/RAG]")
                    appendLine("아래 지식은 이 채널 범위에서만 참고합니다. 확실하지 않으면 추측하지 않습니다.")
                    appendLine(rag)
                }
            }.trim()
        val userPrompt =
            buildString {
                appendLine("[사용자 질문]")
                appendLine(sanitizedQuestion)
            }.trim()
        return ChannelAiPromptPreview(
            guildId = guildId,
            channelId = channelId,
            channelAiId = channelAi?.id,
            behaviorVersionId = behavior?.id,
            name = name,
            sections = sections,
            safetyWarning = if (sensitive) "sensitive_question_detected" else null,
            ragIncluded = rag != null && !sensitive,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
        )
    }

    fun channelOnboarding(
        guildId: Long,
        channelId: Long,
    ): ChannelAiOnboarding {
        featureGate.requireChannelAiEnabled()
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val behavior =
            channelAi?.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(channelAi.id, it) }
                ?: channelAi?.let { versions.findTopByChannelAiIdOrderByVersionDesc(it.id) }
        val name = channelAi?.displayName?.trim()?.takeIf { it.isNotBlank() } ?: "냥시스턴트"
        val purpose = behavior?.purpose ?: DEFAULT_CHANNEL_AI_PURPOSE
        val tone = behavior?.tone ?: DEFAULT_CHANNEL_AI_TONE
        val answerLength = behavior?.answerLength ?: DEFAULT_CHANNEL_AI_ANSWER_LENGTH
        val examples = examplesForPurpose(purpose)
        val safetyNotice = "비밀번호, API 키, 토큰, 개인정보 같은 민감정보는 보내지 마세요."
        val description = "$purpose\n말투는 $tone, 답변 길이는 $answerLength 기준으로 맞춰드릴게요."
        return ChannelAiOnboarding(
            guildId = guildId,
            channelId = channelId,
            channelAiId = channelAi?.id,
            name = name,
            title = "안녕하세요. 저는 이 채널의 $name 이에요.",
            description = description,
            safetyNotice = safetyNotice,
            examples = examples,
            message = onboardingMessage(name, description, safetyNotice, examples),
            empty = channelAi == null,
        )
    }

    /**
     * 제안 상태 전이(가드 적용). 도출한 전이맵([ProposalStatus])에 없는 전이는 거부한다.
     * 현재 코드의 모든 전이(`PENDING → {APPROVED, REJECTED, STALE}`)는 허용되므로 동작 불변이다.
     */
    private fun AiChangeProposalEntity.transitionTo(next: ProposalStatus) {
        require(status.canTransitionTo(next)) { "illegal proposal transition: ${status.wire} -> ${next.wire}" }
        status = next
    }

    private fun AiChangeProposalEntity.toReview(): AiChangeProposalReview =
        AiChangeProposalReview(id = id, status = status.wire, reviewedBy = reviewedBy, reason = reason)

    private fun AiChangeProposalEntity.toPendingView(): PendingProposalView =
        PendingProposalView(
            id = id,
            channelId = channelId,
            channelAiId = channelAiId,
            proposedBehaviorId = proposedBehaviorId,
            requestedBy = requestedBy,
            createdAt = createdAt.toString(),
        )

    private fun AiChangeProposalEntity.toReviewItem(): ChannelAiProposalReviewItem {
        val behavior = proposedBehaviorId?.let { behaviorId -> channelAiId?.let { versions.findByChannelAiIdAndId(it, behaviorId) } }
        return ChannelAiProposalReviewItem(
            id = id,
            channelId = channelId,
            channelAiId = channelAiId,
            proposedBehaviorId = proposedBehaviorId,
            status = status.wire,
            requestedBy = requestedBy,
            reviewedBy = reviewedBy,
            reason = reason,
            behaviorVersion = behavior?.version,
            purpose = behavior?.purpose,
            tone = behavior?.tone,
            answerLength = behavior?.answerLength,
            safetyLevel = behavior?.safetyLevel,
            changeSummary = behavior?.changeSummary,
            createdAt = createdAt.toString(),
            reviewedAt = reviewedAt?.toString(),
        )
    }

    private fun String.looksSensitive(): Boolean {
        val text = trim()
        if (text.isBlank()) return false
        return SENSITIVE_PROMPT_PATTERNS.any { it.containsMatchIn(text) }
    }

    private fun jobPreset(job: String): ChannelAiJobPreset =
        when (job.trim().lowercase()) {
            "development", "dev", "개발", "개발 질문", "1" ->
                ChannelAiJobPreset("development", "코드냥", "개발 질문, 에러 분석, 코드 리뷰, 테스트 작성을 돕습니다.", "개발 질문과 코드 문제를 도와드려요.")
            "translation", "translate", "번역", "2" ->
                ChannelAiJobPreset("translation", "번역냥", "한국어/영어 번역과 문장 다듬기를 돕습니다.", "번역과 문장 개선을 도와드려요.")
            "meeting", "minutes", "회의록", "3" ->
                ChannelAiJobPreset("meeting", "요약냥", "회의록 정리, 액션아이템 추출, 요약을 돕습니다.", "회의 내용을 보기 쉽게 정리해드려요.")
            "announcement", "notice", "공지", "공지 작성", "4" ->
                ChannelAiJobPreset("announcement", "공지냥", "공지 작성, 운영진 안내문, 릴리즈 노트 초안을 돕습니다.", "공지와 안내문 작성을 도와드려요.")
            else ->
                ChannelAiJobPreset("custom", "채널냥", job.trim().ifBlank { DEFAULT_CHANNEL_AI_PURPOSE }.take(200), "이 채널 목적에 맞춰 도와드려요.")
        }

    private fun tonePreset(tone: String): String =
        when (tone.trim().lowercase()) {
            "friendly", "친근", "친근하게", "1" -> "친근하게"
            "professional", "전문", "전문적으로", "2" -> "전문적으로"
            "concise", "short", "짧게", "짧고 명확하게", "3" -> "짧고 명확하게"
            else -> tone.trim().ifBlank { DEFAULT_CHANNEL_AI_TONE }.take(80)
        }

    private fun normalizeAnswerLength(answerLength: String): String =
        when (answerLength.trim().lowercase()) {
            "short", "짧게" -> "short"
            "long", "deep", "길게", "깊게" -> "long"
            else -> "balanced"
        }

    private fun constitutionFor(
        jobKey: String,
        tone: String,
        answerLength: String,
    ): String {
        val jobRule =
            when (jobKey) {
                "development" -> "코드는 실행 가능한 예시와 검증 방법을 함께 제안합니다."
                "translation" -> "원문의 의미를 보존하고, 필요한 경우 자연스러운 대안을 함께 제안합니다."
                "meeting" -> "결정사항, 할 일, 담당자, 기한을 분리해 정리합니다."
                "announcement" -> "사실과 일정은 명확히 쓰고, 과장되거나 확정되지 않은 표현을 피합니다."
                else -> "채널 목적에서 벗어난 질문은 범위를 확인한 뒤 답합니다."
            }
        return listOf(
            "확실하지 않으면 추측하지 말고 확인이 필요하다고 말합니다.",
            "민감정보(비밀번호, 토큰, 개인키, 개인정보)를 요구하거나 저장하지 않습니다.",
            "말투는 $tone 유지합니다.",
            "답변 길이는 $answerLength 정책을 따릅니다.",
            jobRule,
        ).joinToString("\n")
    }

    private fun examplesForPurpose(purpose: String): List<String> {
        val p = purpose.lowercase()
        return when {
            listOf("개발", "코드", "spring", "kotlin", "에러").any { it in p } ->
                listOf("이 에러가 왜 나는지 알려줘", "이 코드 리뷰해줘", "테스트 코드 만들어줘")
            listOf("번역", "영어", "문장").any { it in p } ->
                listOf("이 문장을 자연스럽게 번역해줘", "더 공손한 표현으로 바꿔줘", "영어 답장을 다듬어줘")
            listOf("회의", "요약", "회의록").any { it in p } ->
                listOf("회의 내용을 요약해줘", "결정사항과 할 일을 분리해줘", "액션아이템만 뽑아줘")
            listOf("공지", "안내", "릴리즈").any { it in p } ->
                listOf("공지 초안을 써줘", "운영진 말투로 다듬어줘", "짧은 안내문으로 바꿔줘")
            else ->
                listOf("이 내용을 쉽게 설명해줘", "핵심만 요약해줘", "다음 행동을 추천해줘")
        }
    }

    private fun onboardingMessage(
        name: String,
        description: String,
        safetyNotice: String,
        examples: List<String>,
    ): String =
        buildString {
            appendLine("❂ **$name 채널 AI가 준비됐어요**")
            appendLine()
            appendLine(description)
            appendLine()
            appendLine("**질문 예시**")
            examples.forEach { appendLine("- $it") }
            appendLine()
            appendLine("⚠️ $safetyNotice")
        }.trim()

    private fun approvalDecision(
        requestedApproval: Boolean,
        behavior: AiBehaviorVersionEntity,
        displayName: String,
    ): ApprovalDecision {
        if (requestedApproval) return ApprovalDecision(required = true, reason = "manual approval requested")
        val customInstruction = behavior.customInstruction.orEmpty()
        val text =
            listOf(
                displayName,
                behavior.purpose,
                behavior.tone,
                behavior.answerLength,
                behavior.constitution.orEmpty(),
                behavior.safetyLevel,
                customInstruction,
            ).joinToString("\n").lowercase()
        val reason =
            when {
                behavior.safetyLevel.lowercase() in HIGH_RISK_SAFETY_LEVELS -> "high risk safety level"
                behavior.answerLength.equals("long", ignoreCase = true) -> "long answer mode can increase provider load"
                // 위험어는 KnowledgeSafety.RISKY_INSTRUCTION_TERMS 단일 출처를 공유한다(OnboardingAnalyzer 와 동기화 — S2).
                KnowledgeSafety.looksRiskyInstruction(text) -> "risky channel AI instruction requires review"
                // 자유 지침에 토큰/비밀번호/개인키 같은 민감정보가 들어오면 자동 승인하지 않고 검토 큐로 보낸다.
                customInstruction.looksSensitive() -> "custom instruction contains sensitive material requires review"
                behavior.constitution.orEmpty().length > SAFE_CONSTITUTION_CHARS -> "large constitution requires review"
                else -> null
            }
        return ApprovalDecision(required = reason != null, reason = reason)
    }

    private fun aiAdminRoleIds(guildId: Long): List<Long> =
        aiAdminRoles
            ?.findByGuildId(guildId)
            ?.map { it.roleId }
            ?.distinct()
            ?.sorted()
            ?: emptyList()

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
                customInstruction.orEmpty(),
            ).joinToString("\u001F"),
        )

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun audit(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        action: String,
        targetType: String,
        targetId: Long?,
        summary: String,
    ) {
        audits.save(
            CustomizationAuditLogEntity(
                guildId = guildId,
                channelId = channelId,
                actorId = actorUserId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                summary = summary.take(1000),
                createdAt = Instant.now(clock),
            ),
        )
    }

    private fun normalize(
        value: String?,
        previous: String?,
        default: String,
        max: Int,
    ): String = value?.trim()?.takeIf { it.isNotBlank() }?.take(max) ?: previous ?: default

    private fun normalizeOptional(
        value: String?,
        previous: String?,
        max: Int,
    ): String? = value?.trim()?.takeIf { it.isNotBlank() }?.take(max) ?: previous

    private companion object {
        const val SAFE_CONSTITUTION_CHARS = 1200
        const val PROMPT_USER_QUESTION_MAX = 4_000
        const val PROMPT_RAG_CONTEXT_MAX = 4_000
        val HIGH_RISK_SAFETY_LEVELS = setOf("high", "restricted", "dangerous")
        val SENSITIVE_PROMPT_PATTERNS =
            listOf(
                Regex("""(?i)\b(password|passwd|pwd|secret)\b"""),
                Regex("(?i)(api[_-]?key|bot[_-]?token|discord[_-]?bot[_-]?token|private[_-]?key|access[_-]?token)"),
                Regex("(?i)-----BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----"),
                Regex("(?i)sk-[A-Za-z0-9_-]{20,}"),
            )
    }
}

data class ChannelAiProposalReviewSummary(
    val guildId: Long,
    val totalProposalCount: Int,
    val pendingProposalCount: Int,
    val approvedProposalCount: Int,
    val rejectedProposalCount: Int,
    val staleProposalCount: Int,
    val statusCounts: Map<String, Int>,
    val reasonCounts: Map<String, Int>,
    val riskCodes: List<String>,
    val nextActions: List<String>,
    val pendingItems: List<ChannelAiProposalReviewItem>,
    val recentItems: List<ChannelAiProposalReviewItem>,
)

data class AiAdminRolePolicy(
    val guildId: Long,
    val roleIds: List<Long>,
    val protectedMode: Boolean,
)

data class AiAdminAccessDecision(
    val allowed: Boolean,
    val reason: String,
    val requiredRoleIds: List<Long> = emptyList(),
    val matchedRoleIds: List<Long> = emptyList(),
) {
    fun userMessage(): String =
        if (requiredRoleIds.isEmpty()) {
            "AI 설정 변경에는 서버 관리자 권한이 필요합니다."
        } else {
            "AI 설정 변경은 AI 관리자 역할만 할 수 있습니다. 필요한 역할: ${requiredRoleIds.joinToString(", ")}"
        }
}

data class ChannelAiProposalReviewItem(
    val id: Long,
    val channelId: Long,
    val channelAiId: Long?,
    val proposedBehaviorId: Long?,
    val status: String,
    val requestedBy: Long?,
    val reviewedBy: Long?,
    val reason: String?,
    val behaviorVersion: Int?,
    val purpose: String?,
    val tone: String?,
    val answerLength: String?,
    val safetyLevel: String?,
    val changeSummary: String?,
    val createdAt: String,
    val reviewedAt: String?,
)

data class ChannelAiOnboarding(
    val guildId: Long,
    val channelId: Long,
    val channelAiId: Long?,
    val name: String,
    val title: String,
    val description: String,
    val safetyNotice: String,
    val examples: List<String>,
    val message: String,
    val empty: Boolean,
)

data class ChannelAiWizardResult(
    val channelAiId: Long,
    val behaviorVersionId: Long,
    val version: Int,
    val proposalId: Long,
    val status: String,
    val approvalReason: String? = null,
)

data class ChannelAiHistory(
    val channelAi: ChannelAiHistoryHeader?,
    val versions: List<ChannelAiBehaviorVersionView>,
    val proposals: List<ChannelAiProposalView>,
    val audits: List<ChannelAiAuditView>,
)

data class ChannelAiHistoryHeader(
    val id: Long,
    val activeBehaviorVersionId: Long?,
)

data class ChannelAiBehaviorVersionView(
    val id: Long,
    val version: Int,
    val purpose: String,
    val tone: String,
    val answerLength: String,
    val createdAt: String,
)

data class ChannelAiProposalView(
    val id: Long,
    val status: String,
    val proposedBehaviorId: Long?,
    val requestedBy: Long?,
    val reviewedBy: Long?,
)

data class ChannelAiAuditView(
    val action: String,
    val targetType: String,
    val targetId: Long?,
)

data class AiChangeProposalReview(
    val id: Long,
    val status: String,
    val reviewedBy: Long?,
    val reason: String?,
)

data class PendingProposalView(
    val id: Long,
    val channelId: Long,
    val channelAiId: Long?,
    val proposedBehaviorId: Long?,
    val requestedBy: Long?,
    val createdAt: String,
)

data class ChannelAiWizardDraft(
    val name: String,
    val job: String,
    val tone: String,
    val answerLength: String,
    val constitution: String,
    val preview: String,
)

data class ChannelAiWizardOptions(
    val jobs: List<ChannelAiWizardOption>,
    val tones: List<ChannelAiWizardOption>,
    val answerLengths: List<ChannelAiWizardOption>,
    val safetyRules: List<String>,
)

data class ChannelAiWizardOption(
    val key: String,
    val label: String,
    val description: String,
    val recommendedName: String? = null,
)

private data class ApprovalDecision(
    val required: Boolean,
    val reason: String?,
)

private data class ChannelAiJobPreset(
    val key: String,
    val name: String,
    val purpose: String,
    val preview: String,
)

data class ChannelAiPromptPreview(
    val guildId: Long,
    val channelId: Long,
    val channelAiId: Long?,
    val behaviorVersionId: Long?,
    val name: String,
    val sections: List<String>,
    val safetyWarning: String?,
    val ragIncluded: Boolean,
    val systemPrompt: String,
    val userPrompt: String,
)
