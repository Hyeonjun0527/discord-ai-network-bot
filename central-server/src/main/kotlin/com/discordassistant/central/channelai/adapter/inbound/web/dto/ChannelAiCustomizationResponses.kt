package com.discordassistant.central.channelai.adapter.inbound.web.dto

import com.discordassistant.central.channelai.application.AiChangeProposalReview
import com.discordassistant.central.channelai.application.ChannelAiBehaviorVersionView
import com.discordassistant.central.channelai.application.ChannelAiHistory
import com.discordassistant.central.channelai.application.ChannelAiProposalView
import com.discordassistant.central.channelai.application.ChannelAiWizardDraft
import com.discordassistant.central.channelai.application.ChannelAiWizardResult
import com.discordassistant.central.channelai.application.PendingProposalView

// 채널 AI 관리 API 응답 DTO 모음(인바운드 웹 어댑터의 와이어 계약).
// 기존 JSON field 이름은 유지하되 Map 기반 응답 조립을 제거한다.

/** wizard/draft 응답. */
data class ChannelAiWizardDraftResponse(
    val name: String,
    val job: String,
    val tone: String,
    val answerLength: String,
    val constitution: String,
    val preview: String,
) {
    companion object {
        fun from(draft: ChannelAiWizardDraft): ChannelAiWizardDraftResponse =
            ChannelAiWizardDraftResponse(
                name = draft.name,
                job = draft.job,
                tone = draft.tone,
                answerLength = draft.answerLength,
                constitution = draft.constitution,
                preview = draft.preview,
            )
    }
}

/** wizard 생성/롤백 등 behavior 변경 제안 결과 응답(createFromWizard/rollback 공용). */
data class ChannelAiWizardResultResponse(
    val channelAiId: Long,
    val behaviorVersionId: Long,
    val version: Int,
    val proposalId: Long,
    val status: String,
    val approvalReason: String?,
) {
    companion object {
        fun from(result: ChannelAiWizardResult): ChannelAiWizardResultResponse =
            ChannelAiWizardResultResponse(
                channelAiId = result.channelAiId,
                behaviorVersionId = result.behaviorVersionId,
                version = result.version,
                proposalId = result.proposalId,
                status = result.status,
                approvalReason = result.approvalReason,
            )
    }
}

/** approve 응답. */
data class ApproveChannelAiProposalResponse(
    val id: Long,
    val status: String,
    val reviewedBy: Long?,
    val reason: String?,
) {
    companion object {
        fun from(review: AiChangeProposalReview): ApproveChannelAiProposalResponse =
            ApproveChannelAiProposalResponse(
                id = review.id,
                status = review.status,
                reviewedBy = review.reviewedBy,
                reason = review.reason,
            )
    }
}

/** reject 응답. */
data class RejectChannelAiProposalResponse(
    val id: Long,
    val status: String,
    val reason: String?,
) {
    companion object {
        fun from(review: AiChangeProposalReview): RejectChannelAiProposalResponse =
            RejectChannelAiProposalResponse(
                id = review.id,
                status = review.status,
                reason = review.reason,
            )
    }
}

/** pending 목록의 한 항목 응답(PendingProposalView 의 와이어 표현). */
data class PendingProposalResponse(
    val id: Long,
    val channelId: Long,
    val channelAiId: Long?,
    val proposedBehaviorId: Long?,
    val requestedBy: Long?,
    val createdAt: String,
) {
    companion object {
        fun from(view: PendingProposalView): PendingProposalResponse =
            PendingProposalResponse(
                id = view.id,
                channelId = view.channelId,
                channelAiId = view.channelAiId,
                proposedBehaviorId = view.proposedBehaviorId,
                requestedBy = view.requestedBy,
                createdAt = view.createdAt,
            )
    }
}

/**
 * history 응답. audit 문자열 조립(`"action:targetType:targetId"`, null id 는 "-")의 표현 규칙은
 * from() 안에 보존한다.
 */
data class ChannelAiHistoryResponse(
    val channelAiId: Long?,
    val activeBehaviorVersionId: Long?,
    val versions: List<ChannelAiHistoryVersionResponse>,
    val proposals: List<ChannelAiHistoryProposalResponse>,
    val audits: List<String>,
) {
    companion object {
        fun from(history: ChannelAiHistory): ChannelAiHistoryResponse =
            ChannelAiHistoryResponse(
                channelAiId = history.channelAi?.id,
                activeBehaviorVersionId = history.channelAi?.activeBehaviorVersionId,
                versions = history.versions.map(ChannelAiHistoryVersionResponse::from),
                proposals = history.proposals.map(ChannelAiHistoryProposalResponse::from),
                audits = history.audits.map { "${it.action}:${it.targetType}:${it.targetId ?: "-"}" },
            )
    }
}

data class ChannelAiHistoryVersionResponse(
    val id: Long,
    val version: Int,
    val purpose: String,
    val tone: String,
    val answerLength: String,
    val createdAt: String,
) {
    companion object {
        fun from(version: ChannelAiBehaviorVersionView): ChannelAiHistoryVersionResponse =
            ChannelAiHistoryVersionResponse(
                id = version.id,
                version = version.version,
                purpose = version.purpose,
                tone = version.tone,
                answerLength = version.answerLength,
                createdAt = version.createdAt,
            )
    }
}

data class ChannelAiHistoryProposalResponse(
    val id: Long,
    val status: String,
    val proposedBehaviorId: Long?,
    val requestedBy: Long?,
    val reviewedBy: Long?,
) {
    companion object {
        fun from(proposal: ChannelAiProposalView): ChannelAiHistoryProposalResponse =
            ChannelAiHistoryProposalResponse(
                id = proposal.id,
                status = proposal.status,
                proposedBehaviorId = proposal.proposedBehaviorId,
                requestedBy = proposal.requestedBy,
                reviewedBy = proposal.reviewedBy,
            )
    }
}
