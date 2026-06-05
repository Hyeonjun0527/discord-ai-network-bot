package com.discordassistant.central.channelai.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingSnapshot
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.channelai.application.DEFAULT_CHANNEL_AI_ANSWER_LENGTH
import com.discordassistant.central.channelai.application.DEFAULT_CHANNEL_AI_CONSTITUTION
import com.discordassistant.central.channelai.application.DEFAULT_CHANNEL_AI_PURPOSE
import com.discordassistant.central.channelai.application.DEFAULT_CHANNEL_AI_SAFETY_LEVEL
import com.discordassistant.central.channelai.application.DEFAULT_CHANNEL_AI_TONE
import com.discordassistant.central.channelai.domain.model.ProposalStatus
import com.discordassistant.central.guild.adapter.outbound.persistence.AiAdminRoleEntity
import com.discordassistant.central.guild.adapter.outbound.persistence.AiAdminRoleRepository
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
    // 순수/읽기 협력자(동작보존 분해). 기본값은 기존 의존성으로 구성해 수동 생성(테스트) 호환을 유지하고,
    // Spring 컨텍스트에서는 동일 시그니처의 @Component 빈이 주입된다. write/TX 라이프사이클은 이 파사드에 잔존.
    private val presetFactory: ChannelAiWizardPresetFactory = ChannelAiWizardPresetFactory(),
    private val promptRenderer: ChannelAiPromptRenderer = ChannelAiPromptRenderer(channelAis, versions, featureGate),
    private val onboardingPresenter: ChannelAiOnboardingPresenter = ChannelAiOnboardingPresenter(channelAis, versions, featureGate),
    private val approvalPolicy: ChannelAiApprovalPolicy = ChannelAiApprovalPolicy(),
    private val proposalQuery: ChannelAiProposalQueryService =
        ChannelAiProposalQueryService(channelAis, versions, proposals, audits, featureGate),
    // @Transactional 미부여 협력자 — 파사드의 활성 TX 에 합류한다(새 TX 미발생). clock 은 파사드와 공유.
    private val auditRecorder: CustomizationAuditRecorder = CustomizationAuditRecorder(audits, clock),
    // PESSIMISTIC_WRITE 채번 헬퍼(@Transactional 미부여). 파사드의 활성 TX 에 합류해 락·재시도 의미가 보존된다.
    private val behaviorVersionWriter: BehaviorVersionWriter = BehaviorVersionWriter(channelAis, versions),
) {
    fun wizardOptions(): ChannelAiWizardOptions {
        featureGate.requireChannelAiEnabled()
        return presetFactory.wizardOptions()
    }

    fun draftFromAnswers(
        job: String,
        tone: String,
        answerLength: String = "balanced",
        customName: String? = null,
    ): ChannelAiWizardDraft {
        featureGate.requireChannelAiEnabled()
        return presetFactory.draftFromAnswers(job, tone, answerLength, customName)
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
        val behavior =
            saveNextBehaviorVersion(savedChannel.id) { nextVersion ->
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
                )
            }
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

    /**
     * 신뢰된 전역 대시보드 관리자(self-hosted 단일 운영자) 전용 wizard 생성 오버로드(#1).
     * 권한 격상 규약(per-guild AI-admin 역할 우회: `actorRoleIds=emptySet()`, `actorIsGuildAdmin=true`)과
     * 즉시-active 우회 차단 불변식(`requireApproval=true`)을 **여기 한 곳에서** 강제한다 — 컨트롤러는
     * 인증 주체(DashboardActor)에서 신원(userId)만 넘기고 권한 격상 로직을 갖지 않는다.
     */
    @Transactional
    fun createFromWizardAsTrustedDashboardAdmin(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        name: String,
        avatarUrl: String?,
        job: String,
        tone: String,
        answerLength: String,
        constitution: String?,
    ): ChannelAiWizardResult =
        createFromWizard(
            guildId = guildId,
            channelId = channelId,
            actorUserId = actorUserId,
            actorRoleIds = emptySet(),
            actorIsGuildAdmin = true,
            name = name,
            avatarUrl = avatarUrl,
            job = job,
            tone = tone,
            answerLength = answerLength,
            constitution = constitution,
            // 즉시 active 우회 차단(#1): 검토 강제가 기본. body 로 false 를 줘도 검토를 끌 수 없다.
            requireApproval = true,
        )

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
        val rollbackBehavior =
            saveNextBehaviorVersion(channelAi.id) { nextVersion ->
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
                )
            }
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
     * 신뢰된 전역 대시보드 관리자 전용 rollback 오버로드(#1). per-guild 역할 우회 규약을 한 곳에서 강제한다.
     * `requireApproval`/`reason` 은 호출자(대시보드 요청 body)가 그대로 결정한다(동작 불변).
     */
    @Transactional
    fun rollbackToVersionAsTrustedDashboardAdmin(
        guildId: Long,
        channelId: Long,
        targetVersion: Int,
        actorUserId: Long?,
        requireApproval: Boolean,
        reason: String?,
    ): ChannelAiWizardResult =
        rollbackToVersion(
            guildId = guildId,
            channelId = channelId,
            targetVersion = targetVersion,
            actorUserId = actorUserId,
            actorRoleIds = emptySet(),
            actorIsGuildAdmin = true,
            requireApproval = requireApproval,
            reason = reason,
        )

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
        val behavior =
            saveNextBehaviorVersion(channelAi.id) { nextVersion ->
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
                )
            }
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
        // PESSIMISTIC_WRITE 로 잠가 동시 승인/거절을 직렬화한다(#3): 이중 APPROVED·activeBehaviorVersionId lost update 방지.
        val proposal =
            proposals.findByIdForUpdate(proposalId)
                ?: throw IllegalArgumentException("proposal not found: $proposalId")
        require(proposal.status == ProposalStatus.PENDING) { "pending proposal only can be approved" }
        requireCanManageChannelAi(proposal.guildId, proposal.channelId, reviewerUserId, reviewerRoleIds, reviewerIsGuildAdmin, "approve")
        val channelAiId = proposal.channelAiId ?: throw IllegalArgumentException("proposal has no channel ai")
        val behaviorId = proposal.proposedBehaviorId ?: throw IllegalArgumentException("proposal has no behavior")
        // 같은 채널의 동시 승인 간 activeBehaviorVersionId last-writer 비결정성을 완화하기 위해 채널 AI 행도 잠근다(#3).
        val channelAi =
            channelAis.findByIdForUpdate(channelAiId)
                ?: throw IllegalArgumentException("channel ai not found: $channelAiId")
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
        // approveProposal 과 동일하게 PESSIMISTIC_WRITE 로 잠가 동시 승인/거절을 직렬화한다(#3).
        val proposal =
            proposals.findByIdForUpdate(proposalId)
                ?: throw IllegalArgumentException("proposal not found: $proposalId")
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

    /** 신뢰된 전역 대시보드 관리자 전용 approve 오버로드(#1). 검토자 권한 격상 규약을 한 곳에서 강제한다. */
    @Transactional
    fun approveProposalAsTrustedDashboardAdmin(
        proposalId: Long,
        reviewerUserId: Long?,
        reason: String? = null,
    ): AiChangeProposalReview =
        approveProposal(
            proposalId = proposalId,
            reviewerUserId = reviewerUserId,
            reviewerRoleIds = emptySet(),
            reviewerIsGuildAdmin = true,
            reason = reason,
        )

    /** 신뢰된 전역 대시보드 관리자 전용 reject 오버로드(#1). 검토자 권한 격상 규약을 한 곳에서 강제한다. */
    @Transactional
    fun rejectProposalAsTrustedDashboardAdmin(
        proposalId: Long,
        reviewerUserId: Long?,
        reason: String?,
    ): AiChangeProposalReview =
        rejectProposal(
            proposalId = proposalId,
            reviewerUserId = reviewerUserId,
            reviewerRoleIds = emptySet(),
            reviewerIsGuildAdmin = true,
            reason = reason,
        )

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

    /** 신뢰된 전역 대시보드 관리자 전용 AI-admin 역할 교체 오버로드(#1). 권한 격상 규약을 한 곳에서 강제한다. */
    @Transactional
    fun replaceAiAdminRolesAsTrustedDashboardAdmin(
        guildId: Long,
        roleIds: Collection<Long>,
        actorUserId: Long?,
    ): AiAdminRolePolicy =
        replaceAiAdminRoles(
            guildId = guildId,
            roleIds = roleIds,
            actorUserId = actorUserId,
            actorRoleIds = emptySet(),
            actorIsGuildAdmin = true,
        )

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
    ): ChannelAiProposalReviewSummary = proposalQuery.proposalReviewSummary(guildId, limit)

    fun pendingProposals(guildId: Long): List<PendingProposalView> = proposalQuery.pendingProposals(guildId)

    /** 현재 활성(또는 최신) behavior 의 자유 지침을 반환한다. 채널 AI/지침이 없으면 null. */
    fun currentCustomInstruction(
        guildId: Long,
        channelId: Long,
    ): String? = proposalQuery.currentCustomInstruction(guildId, channelId)

    fun channelHistory(
        guildId: Long,
        channelId: Long,
    ): ChannelAiHistory = proposalQuery.channelHistory(guildId, channelId)

    fun promptPreview(
        guildId: Long,
        channelId: Long,
        userQuestion: String,
        ragContextText: String? = null,
    ): ChannelAiPromptPreview = promptRenderer.promptPreview(guildId, channelId, userQuestion, ragContextText)

    fun channelOnboarding(
        guildId: Long,
        channelId: Long,
    ): ChannelAiOnboarding = onboardingPresenter.channelOnboarding(guildId, channelId)

    /**
     * 제안 상태 전이(가드 적용). 도출한 전이맵([ProposalStatus])에 없는 전이는 거부한다.
     * 현재 코드의 모든 전이(`PENDING → {APPROVED, REJECTED, STALE}`)는 허용되므로 동작 불변이다.
     */
    private fun AiChangeProposalEntity.transitionTo(next: ProposalStatus) {
        require(status.canTransitionTo(next)) { "illegal proposal transition: ${status.wire} -> ${next.wire}" }
        status = next
    }

    private fun AiChangeProposalEntity.toReview(): AiChangeProposalReview = proposalQuery.toReview(this)

    private fun approvalDecision(
        requestedApproval: Boolean,
        behavior: AiBehaviorVersionEntity,
        displayName: String,
    ): ApprovalDecision = approvalPolicy.approvalDecision(requestedApproval, behavior, displayName)

    /**
     * behavior version 채번(`MAX(version)+1`)+insert 를 [BehaviorVersionWriter] 에 위임한다.
     * writer 는 @Transactional 미부여라 이 파사드의 활성 TX 에 합류한다 — 락(PESSIMISTIC_WRITE)·재시도·
     * 예외 메시지가 추출 전과 1바이트도 다르지 않다.
     */
    private fun saveNextBehaviorVersion(
        channelAiId: Long,
        build: (Int) -> AiBehaviorVersionEntity,
    ): AiBehaviorVersionEntity = behaviorVersionWriter.saveNextBehaviorVersion(channelAiId, build)

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
    ) = auditRecorder.audit(guildId, channelId, actorUserId, action, targetType, targetId, summary)

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
}
