package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkNextActionResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkOverviewResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiCardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiChangeApprovalDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.KnowledgeSpaceResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ModelMapResponse
import org.springframework.stereotype.Component

/**
 * AI Network 추천 액션(next-action) 플래너 협력자 — 읽기 전용·순수 함수
 * (@Transactional·write·repo 의존 없음).
 *
 * provider/channel/model/knowledge/change-approval/quality/overload 조건별 추천 액션과
 * growth-plan 보조 액션 병합·중복 제거·우선순위 정렬을 한곳에 모은다. 본문은
 * [AiNetworkReadinessService] 에서 1바이트 불변으로 이동했으며 priority/severity·사용자 노출
 * 문구·discordCommand·dashboardPath 는 변경하지 않는다.
 */
@Component
class AiNetworkNextActionPlanner {
    fun nextActions(
        overview: AiNetworkOverviewResponse,
        channels: List<ChannelAiCardResponse>,
        modelMap: List<ModelMapResponse>,
        knowledgeSpaces: List<KnowledgeSpaceResponse>,
        quality: QualitySummary,
        overload: ProviderSafetyDashboard,
        changeApproval: ChannelAiChangeApprovalDashboardResponse,
        growthPlan: AiNetworkGrowthPlan,
    ): List<AiNetworkNextActionResponse> =
        buildList {
            if (overview.onlineProviderCount == 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 10,
                        severity = "critical",
                        actionType = "connect_provider",
                        title = "Provider를 먼저 연결하세요",
                        description = "온라인 Provider가 없어 질문을 처리할 로컬 AI가 없습니다. /프로바이더참여 안내로 첫 PC를 연결하세요.",
                        ctaLabel = "Provider 참여 안내 열기",
                        discordCommand = "/프로바이더참여",
                        dashboardPath = "/admin/dashboard/providers",
                    ),
                )
            }
            if (channels.isEmpty()) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 20,
                        severity = "recommended",
                        actionType = "create_channel_ai",
                        title = "채널 AI를 만드세요",
                        description = "채널별 이름·역할·말투가 아직 없습니다. 설정 패널에서 이 채널 AI 프로필을 만들면 네트워크 정체성이 생깁니다.",
                        ctaLabel = "채널 AI 설정",
                        discordCommand = "/채널프로필",
                        dashboardPath = "/admin/dashboard/channels",
                    ),
                )
            }
            if (modelMap.size < 2 && overview.onlineProviderCount > 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 30,
                        severity = "recommended",
                        actionType = "add_model_diversity",
                        title = "모델 다양성을 늘리세요",
                        description = "현재 선택 가능한 모델이 적습니다. 다른 모델을 가진 Provider가 참여하면 질문 유형별 라우팅 품질이 좋아집니다.",
                        ctaLabel = "모델 지도 확인",
                        discordCommand = null,
                        dashboardPath = "/admin/dashboard/model-map",
                    ),
                )
            }
            val hasKnowledge =
                knowledgeSpaces.any { space ->
                    space.status == "ready" || space.sourceCount > 0
                } ||
                    channels.any { channel ->
                        channel.knowledgeSpaceCount > 0 || channel.indexedKnowledgeSourceCount > 0
                    }
            if (!hasKnowledge) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 40,
                        severity = "optional",
                        actionType = "add_knowledge",
                        title = "채널 지식을 추가하세요",
                        description = "README·운영규칙·FAQ를 지식공간에 등록하면 채널 AI가 서버 맥락을 더 잘 반영할 수 있습니다.",
                        ctaLabel = "지식 추가",
                        discordCommand = "/지식추가",
                        dashboardPath = "/admin/dashboard/knowledge",
                    ),
                )
            }
            if (changeApproval.pendingCount > 0 || changeApproval.staleCount > 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 35,
                        severity = if (changeApproval.staleCount > 0) "critical" else "recommended",
                        actionType = "review_ai_changes",
                        title = "AI 설정 변경을 검토하세요",
                        description =
                            "대기 중인 AI 설정 변경 ${changeApproval.pendingCount}건, " +
                                "stale 제안 ${changeApproval.staleCount}건이 있습니다. " +
                                "승인/거절 후에만 채널 AI가 안전하게 바뀝니다.",
                        ctaLabel = "AI 변경 승인 대기열",
                        discordCommand = null,
                        dashboardPath = "/admin/dashboard/channels/approvals",
                    ),
                )
            }
            if (quality.openReports > 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 45,
                        severity = "recommended",
                        actionType = "review_quality_reports",
                        title = "열린 품질 신고를 검토하세요",
                        description = "미처리 신고가 ${quality.openReports}건 있습니다. 신고를 resolved/dismissed 로 정리해야 대시보드 품질 상태를 신뢰할 수 있습니다.",
                        ctaLabel = "품질 신고 검토",
                        discordCommand = null,
                        dashboardPath = "/admin/dashboard/quality/review",
                    ),
                )
            }
            if (quality.feedbackCount == 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 50,
                        severity = "optional",
                        actionType = "collect_feedback",
                        title = "답변 품질 피드백을 모으세요",
                        description = "아직 품질 피드백이 없습니다. 따봉/신고/사유를 모으면 모델 선택과 채널 AI 개선 근거가 생깁니다.",
                        ctaLabel = "품질 대시보드 보기",
                        discordCommand = null,
                        dashboardPath = "/admin/dashboard/quality",
                    ),
                )
            }
            if (overload.highRiskCount > 0) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 5,
                        severity = "critical",
                        actionType = "protect_providers",
                        title = "Provider 과부하를 먼저 낮추세요",
                        description = "과부하 Provider가 있어 깊은 답변·다중 응답보다 보호 정책이 우선됩니다. 수신정지/절약 모드/후보 수 제한을 확인하세요.",
                        ctaLabel = "과부하 알림 확인",
                        discordCommand = "/내상태",
                        dashboardPath = "/admin/dashboard/providers/overload",
                    ),
                )
            }
            val existingActionTypes = map { it.actionType }.toSet()
            growthPlan.actions
                .filterNot { growthActionCoveredByPrimaryAction(it.key, existingActionTypes) }
                .take(3)
                .forEach { action ->
                    add(
                        AiNetworkNextActionResponse(
                            priority = action.priority + 60,
                            severity = action.severity,
                            actionType = "growth_${action.key}",
                            title = action.title,
                            description = action.description,
                            ctaLabel = "성장 계획 보기",
                            discordCommand = action.command,
                            dashboardPath = action.dashboardPath,
                        ),
                    )
                }
            if (isEmpty()) {
                add(
                    AiNetworkNextActionResponse(
                        priority = 100,
                        severity = "info",
                        actionType = "optimize_network",
                        title = "AI 네트워크가 안정적으로 준비됐어요",
                        description = "Provider·채널 AI·지식·피드백 기반이 갖춰졌습니다. 이제 프리셋 공유나 다중 응답 실험을 단계적으로 켜도 됩니다.",
                        ctaLabel = "고급 기능 검토",
                        discordCommand = null,
                        dashboardPath = "/admin/dashboard/experiments",
                    ),
                )
            }
        }.sortedBy { it.priority }

    private fun growthActionCoveredByPrimaryAction(
        growthKey: String,
        existingActionTypes: Set<String>,
    ): Boolean =
        when (growthKey) {
            "connect_first_provider" -> "connect_provider" in existingActionTypes
            "create_first_channel_ai" -> "create_channel_ai" in existingActionTypes
            "increase_model_diversity" -> "add_model_diversity" in existingActionTypes
            "add_first_knowledge_space" -> "add_knowledge" in existingActionTypes
            "collect_quality_feedback" -> "collect_feedback" in existingActionTypes
            "resolve_provider_overload" -> "protect_providers" in existingActionTypes
            else -> false
        }
}
