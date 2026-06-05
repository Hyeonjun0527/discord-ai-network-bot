package com.discordassistant.central.channelai.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.channelai.domain.model.ProposalStatus
import org.springframework.stereotype.Component

/**
 * 채널 AI 제안/이력 읽기 — 읽기 전용 협력자(@Transactional·write 없음).
 *
 * 모든 메서드는 조회·매핑만 한다. 별 빈으로 빼도 새 TX 가 열리지 않으며(@Transactional 미부여),
 * write TX 메서드(approveProposal/rejectProposal)가 순수 매퍼([toReview])를 위임 호출해도 같은 TX 문맥에서
 * 그대로 동작한다(부작용 없음). 집계/문구는 추출 전과 1바이트 불변.
 */
@Component
class ChannelAiProposalQueryService(
    private val channelAis: ChannelAiRepository,
    private val versions: AiBehaviorVersionRepository,
    private val proposals: AiChangeProposalRepository,
    private val audits: CustomizationAuditLogRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
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

    /** 순수 매퍼 — write TX 메서드(approve/reject)가 위임 호출해도 부작용 없이 동작한다. */
    fun toReview(proposal: AiChangeProposalEntity): AiChangeProposalReview =
        AiChangeProposalReview(
            id = proposal.id,
            status = proposal.status.wire,
            reviewedBy = proposal.reviewedBy,
            reason = proposal.reason,
        )

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
}
