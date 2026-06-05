package com.discordassistant.central.channelai.adapter.inbound.web.dto

import com.discordassistant.central.channelai.application.AiChangeProposalReview
import com.discordassistant.central.channelai.application.ChannelAiHistory
import com.discordassistant.central.channelai.application.ChannelAiWizardDraft
import com.discordassistant.central.channelai.application.ChannelAiWizardResult
import com.discordassistant.central.channelai.application.PendingProposalView

// 채널 AI 관리 API 응답 DTO 모음(인바운드 웹 어댑터의 와이어 계약). 조립 책임만 컨트롤러 인라인 mapOf 에서 흡수했다.
// 각 toMap() 은 원본 mapOf 의 키 이름·값·순서·null·중첩·조건부키를 1바이트도 바꾸지 않고 그대로 재현한다
// (OpenApiContractTest·클라이언트 계약, 핸들러 반환 타입 map-index 접근). 입력은 application 의 타입드 result 만
// 참조한다(엔티티/리포지토리 의존 금지). history 의 audit 문자열 포맷 같은 표현 규칙도 from() 안에만 둔다.

/** wizard/draft 응답. */
data class ChannelAiWizardDraftResponse(
    val draft: ChannelAiWizardDraft,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "name" to draft.name,
            "job" to draft.job,
            "tone" to draft.tone,
            "answerLength" to draft.answerLength,
            "constitution" to draft.constitution,
            "preview" to draft.preview,
        )

    companion object {
        fun from(draft: ChannelAiWizardDraft): ChannelAiWizardDraftResponse = ChannelAiWizardDraftResponse(draft)
    }
}

/** wizard 생성/롤백 등 behavior 변경 제안 결과 응답(createFromWizard/rollback 공용). */
data class ChannelAiWizardResultResponse(
    val result: ChannelAiWizardResult,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "channelAiId" to result.channelAiId,
            "behaviorVersionId" to result.behaviorVersionId,
            "version" to result.version,
            "proposalId" to result.proposalId,
            "status" to result.status,
            "approvalReason" to result.approvalReason,
        )

    companion object {
        fun from(result: ChannelAiWizardResult): ChannelAiWizardResultResponse = ChannelAiWizardResultResponse(result)
    }
}

/** approve 응답. */
data class ApproveChannelAiProposalResponse(
    val review: AiChangeProposalReview,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to review.id,
            "status" to review.status,
            "reviewedBy" to review.reviewedBy,
            "reason" to review.reason,
        )

    companion object {
        fun from(review: AiChangeProposalReview): ApproveChannelAiProposalResponse = ApproveChannelAiProposalResponse(review)
    }
}

/** reject 응답. */
data class RejectChannelAiProposalResponse(
    val review: AiChangeProposalReview,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to review.id, "status" to review.status, "reason" to review.reason)

    companion object {
        fun from(review: AiChangeProposalReview): RejectChannelAiProposalResponse = RejectChannelAiProposalResponse(review)
    }
}

/** pending 목록의 한 항목 응답(PendingProposalView 의 와이어 표현). */
data class PendingProposalResponse(
    val view: PendingProposalView,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to view.id,
            "channelId" to view.channelId,
            "channelAiId" to view.channelAiId,
            "proposedBehaviorId" to view.proposedBehaviorId,
            "requestedBy" to view.requestedBy,
            "createdAt" to view.createdAt,
        )

    companion object {
        fun from(view: PendingProposalView): PendingProposalResponse = PendingProposalResponse(view)
    }
}

/**
 * history 응답. versions/proposals 중첩 map 과 audit 문자열 조립(`"action:targetType:targetId"`, null id 는 "-")의
 * 표현 규칙을 from() 안에 보존한다(원본 컨트롤러 인라인과 1바이트 동일).
 */
data class ChannelAiHistoryResponse(
    val history: ChannelAiHistory,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "channelAiId" to history.channelAi?.id,
            "activeBehaviorVersionId" to history.channelAi?.activeBehaviorVersionId,
            "versions" to
                history.versions.map {
                    mapOf(
                        "id" to it.id,
                        "version" to it.version,
                        "purpose" to it.purpose,
                        "tone" to it.tone,
                        "answerLength" to it.answerLength,
                        "createdAt" to it.createdAt,
                    )
                },
            "proposals" to
                history.proposals.map {
                    mapOf(
                        "id" to it.id,
                        "status" to it.status,
                        "proposedBehaviorId" to it.proposedBehaviorId,
                        "requestedBy" to it.requestedBy,
                        "reviewedBy" to it.reviewedBy,
                    )
                },
            "audits" to history.audits.map { "${it.action}:${it.targetType}:${it.targetId ?: "-"}" },
        )

    companion object {
        fun from(history: ChannelAiHistory): ChannelAiHistoryResponse = ChannelAiHistoryResponse(history)
    }
}
