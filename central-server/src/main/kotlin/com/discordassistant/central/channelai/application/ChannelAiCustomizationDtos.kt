package com.discordassistant.central.channelai.application

// 응답/뷰 DTO (god-class 분해: ChannelAiCustomizationService 본문 밖 top-level 선언을 같은 패키지
// sibling 파일로 이동). 같은 패키지라 소비자 import 무변경 — 시그니처/필드 1바이트 불변.
// 서비스(@Service) write/TX 라이프사이클은 ChannelAiCustomizationService.kt 에 그대로 잔존.

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

data class ChannelAiJobPreset(
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
