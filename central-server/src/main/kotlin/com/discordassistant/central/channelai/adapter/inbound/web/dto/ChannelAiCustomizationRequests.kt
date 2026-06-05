package com.discordassistant.central.channelai.adapter.inbound.web.dto

// 채널 AI 관리 API 요청 DTO 모음(인바운드 웹 어댑터). 입력은 application 의 값 타입만 참조한다
// (엔티티/리포지토리 의존 금지). 필드/기본값은 컨트롤러에 인라인이던 원본과 1:1 동일 — JSON 바인딩 계약 불변.
//
// 보안(#1): 아래 DTO 들은 권한/신원 플래그(actorUserId/actorRoleIds/actorIsGuildAdmin/reviewer*)를
// **의도적으로 담지 않는다**. 권한은 서버측 인증 주체(DashboardActor)에서만 유도한다. 클라이언트가
// 이런 필드를 추가로 보내도 역직렬화에서 무시되므로(알 수 없는 필드 무시) 권한 부여에 절대 쓰이지 않는다.

data class ChannelAiWizardDraftRequest(
    val job: String,
    val tone: String = "friendly",
    val answerLength: String = "balanced",
    val name: String? = null,
)

data class ChannelAiWizardRequest(
    val name: String,
    val avatarUrl: String? = null,
    val job: String,
    val tone: String = "friendly",
    val answerLength: String = "balanced",
    val constitution: String? = null,
    // requireApproval 은 더 이상 받지 않는다 — 대시보드 wizard 는 항상 검토 큐로 보낸다(즉시 active 우회 차단).
)

data class RollbackChannelAiVersionRequest(
    val targetVersion: Int,
    val requireApproval: Boolean = false,
    val reason: String? = null,
)

data class ReviewChannelAiProposalRequest(
    val reason: String? = null,
)

data class ReplaceAiAdminRolesRequest(
    val roleIds: Set<Long> = emptySet(),
)

data class ChannelAiPromptPreviewRequest(
    val userQuestion: String,
    val ragContextText: String? = null,
)
