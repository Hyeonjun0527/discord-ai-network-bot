package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkLaunchChecklistItemResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkLaunchChecklistResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkNextActionResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkOverviewResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkReadinessAreaResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.AiNetworkReadinessResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.ChannelAiCardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.ChannelAiChangeApprovalDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.KnowledgeSpaceResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.ModelMapResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.ProviderCapabilityResponse
import org.springframework.stereotype.Service

/**
 * AI Network 대시보드 준비도/체크리스트/추천액션 순수 계산 SSOT.
 * 컨트롤러가 수집한 응답 DTO 와 FeatureGate 스냅샷만 입력으로 받는 순수 함수 계층이라
 * 비즈니스 계산을 웹 어댑터(컨트롤러)에서 분리한다(클린아키텍처: 컨트롤러는 위임만).
 */
@Service
class AiNetworkReadinessService {
    fun readiness(
        overview: AiNetworkOverviewResponse,
        channels: List<ChannelAiCardResponse>,
        providers: List<ProviderCapabilityResponse>,
        modelMap: List<ModelMapResponse>,
        knowledgeSpaces: List<KnowledgeSpaceResponse>,
        quality: QualitySummary,
        overload: ProviderSafetyDashboard,
        changeApproval: ChannelAiChangeApprovalDashboardResponse,
    ): AiNetworkReadinessResponse {
        val hasKnowledge =
            knowledgeSpaces.any { it.status == "ready" || it.sourceCount > 0 } ||
                channels.any { it.knowledgeSpaceCount > 0 || it.indexedKnowledgeSourceCount > 0 }
        val areas =
            listOf(
                readinessArea(
                    key = "providers",
                    title = "Provider 상태",
                    status =
                        when {
                            overview.onlineProviderCount == 0 -> "blocked"
                            overload.highRiskCount > 0 -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            overview.onlineProviderCount == 0 -> 0
                            overload.highRiskCount > 0 -> 60
                            else -> 100
                        },
                    evidence =
                        listOf(
                            "online=${overview.onlineProviderCount}",
                            "approved=${overview.approvedProviderCount}",
                            "safeOnline=${overload.safeOnlineProviderCount}",
                        ),
                    nextAction =
                        when {
                            overview.onlineProviderCount == 0 -> "Provider 참여 안내로 최소 1대의 PC를 연결하세요."
                            overload.highRiskCount > 0 -> "과부하 Provider를 보호하고 후보 수/응답 모드를 낮추세요."
                            else -> "Provider 기반이 준비됐습니다."
                        },
                ),
                readinessArea(
                    key = "models",
                    title = "모델 지도",
                    status =
                        when {
                            modelMap.isEmpty() -> "blocked"
                            modelMap.size < 2 -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            modelMap.isEmpty() -> 0
                            modelMap.size < 2 -> 65
                            else -> 100
                        },
                    evidence = listOf("models=${modelMap.size}", "mappedChannels=${modelMap.sumOf { it.channelCount }}"),
                    nextAction =
                        if (modelMap.size < 2) {
                            "다른 모델을 가진 Provider를 늘리거나 채널별 선호 모델을 지정하세요."
                        } else {
                            "모델 다양성이 충분합니다."
                        },
                ),
                readinessArea(
                    key = "channel_ai",
                    title = "채널 AI 프로필",
                    status =
                        when {
                            channels.isEmpty() -> "blocked"
                            channels.any { it.readinessStatus != "ready" } -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            channels.isEmpty() -> 0
                            channels.any { it.readinessStatus != "ready" } -> 60
                            else -> 100
                        },
                    evidence = listOf("channels=${channels.size}", "ready=${channels.count { it.readinessStatus == "ready" }}"),
                    nextAction =
                        if (channels.any { it.readinessStatus != "ready" }) {
                            "채널프로필 패널에서 역할·말투·모델 정책을 마저 설정하세요."
                        } else {
                            "채널별 AI 프로필이 준비됐습니다."
                        },
                ),
                readinessArea(
                    key = "knowledge",
                    title = "RAG 지식",
                    status =
                        when {
                            !hasKnowledge -> "warning"
                            channels.any { it.blockedKnowledgeSourceCount > 0 } -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            !hasKnowledge -> 45
                            channels.any { it.blockedKnowledgeSourceCount > 0 } -> 60
                            else -> 100
                        },
                    evidence =
                        listOf(
                            "spaces=${knowledgeSpaces.size}",
                            "sources=${knowledgeSpaces.sumOf { it.sourceCount }}",
                            "blocked=${channels.sumOf { it.blockedKnowledgeSourceCount }}",
                        ),
                    nextAction =
                        when {
                            !hasKnowledge -> "README·운영규칙·FAQ를 지식공간에 추가하세요."
                            channels.any { it.blockedKnowledgeSourceCount > 0 } -> "blocked/review 지식 소스를 승인·거절·삭제하세요."
                            else -> "채널 지식 기반이 준비됐습니다."
                        },
                ),
                readinessArea(
                    key = "quality_feedback",
                    title = "품질 피드백",
                    status =
                        when {
                            quality.feedbackCount == 0 -> "warning"
                            quality.openReports > 0 -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            quality.feedbackCount == 0 -> 50
                            quality.openReports > 0 -> 65
                            else -> 100
                        },
                    evidence = listOf("feedback=${quality.feedbackCount}", "openReports=${quality.openReports}"),
                    nextAction =
                        when {
                            quality.feedbackCount == 0 -> "답변 따봉/신고 피드백을 모아 모델 선택 근거를 만드세요."
                            quality.openReports > 0 -> "열린 신고를 검토하고 resolved/dismissed 로 정리하세요."
                            else -> "품질 피드백 기반 개선 루프가 동작합니다."
                        },
                ),
                readinessArea(
                    key = "change_approval",
                    title = "AI 설정 변경 승인",
                    status =
                        when (changeApproval.status) {
                            "blocked" -> "blocked"
                            "needs_review", "warning" -> "warning"
                            else -> "ready"
                        },
                    score =
                        when (changeApproval.status) {
                            "blocked" -> 0
                            "needs_review" -> 55
                            "warning" -> 75
                            else -> 100
                        },
                    evidence =
                        listOf(
                            "pending=${changeApproval.pendingCount}",
                            "stale=${changeApproval.staleCount}",
                            "rejected=${changeApproval.rejectedCount}",
                        ),
                    nextAction = changeApproval.nextActions.firstOrNull() ?: "검토 대기 중인 AI 설정 변경은 없습니다.",
                ),
                readinessArea(
                    key = "provider_safety",
                    title = "Provider 보호",
                    status =
                        when {
                            overload.highRiskCount > 0 && overload.safeOnlineProviderCount == 0 -> "blocked"
                            overload.highRiskCount > 0 -> "warning"
                            else -> "ready"
                        },
                    score =
                        when {
                            overload.highRiskCount > 0 && overload.safeOnlineProviderCount == 0 -> 0
                            overload.highRiskCount > 0 -> 55
                            else -> 100
                        },
                    evidence = listOf("alerts=${overload.alertCount}", "highRisk=${overload.highRiskCount}"),
                    nextAction =
                        if (overload.highRiskCount > 0) {
                            "과부하 알림을 먼저 해소한 뒤 깊은 답변/다중 응답을 켜세요."
                        } else {
                            "Provider 보호 상태가 안정적입니다."
                        },
                ),
                readinessArea(
                    key = "projection",
                    title = "대시보드 Projection",
                    status = if (overview.stale) "warning" else "ready",
                    score = if (overview.stale) 70 else 100,
                    evidence = listOf("freshness=${overview.freshnessStatus}", "source=network_overview_projection"),
                    nextAction =
                        if (overview.stale) {
                            "projection을 새로고침하고 stale 원인을 확인하세요."
                        } else {
                            "읽기 모델이 최신 상태입니다."
                        },
                ),
            )
        val overallScore = areas.map { it.score }.average().toInt()
        val status =
            when {
                areas.any { it.status == "blocked" } -> "blocked"
                areas.any { it.status == "warning" } -> "warning"
                else -> "ready"
            }
        return AiNetworkReadinessResponse(
            guildId = overview.guildId,
            status = status,
            score = overallScore,
            readyAreaCount = areas.count { it.status == "ready" },
            warningAreaCount = areas.count { it.status == "warning" },
            blockedAreaCount = areas.count { it.status == "blocked" },
            areas = areas,
            topNextActions =
                areas
                    .filter { it.status != "ready" }
                    .sortedBy { it.score }
                    .take(5)
                    .map { it.nextAction },
        )
    }

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

    fun launchChecklist(
        dashboard: AiNetworkDashboardResponse,
        featureSnapshot: AiNetworkFeatureSnapshot,
    ): AiNetworkLaunchChecklistResponse {
        val readinessItems =
            dashboard.readiness.areas.map {
                AiNetworkLaunchChecklistItemResponse(
                    key = it.key,
                    title = it.title,
                    status = it.status,
                    evidence = it.evidence,
                    nextAction = it.nextAction,
                    blocking = it.status == "blocked",
                )
            }
        val featureBaseReady =
            featureSnapshot.aiNetwork &&
                featureSnapshot.dashboard &&
                featureSnapshot.channelAi &&
                featureSnapshot.presets &&
                featureSnapshot.rag &&
                !featureSnapshot.killSwitch
        val advancedLimited =
            !featureSnapshot.multiResponse ||
                !featureSnapshot.multiResponseDashboard ||
                !featureSnapshot.multiResponseSynthesis ||
                !featureSnapshot.multiResponseRag ||
                featureSnapshot.multiResponseMaxFanout <= 1
        val safetyItems =
            listOf(
                checklistItem(
                    key = "feature_flags",
                    title = "기능 플래그/kill switch",
                    passed = featureBaseReady && !advancedLimited,
                    warning = featureBaseReady && advancedLimited,
                    evidence =
                        listOf(
                            "aiNetwork=${featureSnapshot.aiNetwork}",
                            "dashboard=${featureSnapshot.dashboard}",
                            "channelAi=${featureSnapshot.channelAi}",
                            "presets=${featureSnapshot.presets}",
                            "rag=${featureSnapshot.rag}",
                            "multi=${featureSnapshot.multiResponse}",
                            "multiDashboard=${featureSnapshot.multiResponseDashboard}",
                            "synthesis=${featureSnapshot.multiResponseSynthesis}",
                            "multiRag=${featureSnapshot.multiResponseRag}",
                            "maxFanout=${featureSnapshot.multiResponseMaxFanout}",
                            "killSwitch=${featureSnapshot.killSwitch}",
                        ),
                    nextAction = "ENV_FILE 의 AI_NETWORK_* 플래그와 maxFanout 가 의도한 운영값인지 확인하세요.",
                ),
                checklistItem(
                    key = "provider_overload",
                    title = "Provider 과부하 보호",
                    passed = dashboard.overload.highRiskCount == 0,
                    warning = dashboard.overload.highRiskCount > 0 && dashboard.overload.safeOnlineProviderCount > 0,
                    evidence =
                        listOf(
                            "highRisk=${dashboard.overload.highRiskCount}",
                            "safeOnline=${dashboard.overload.safeOnlineProviderCount}",
                        ),
                    nextAction = "후보 수/깊은 답변/다중응답을 낮추고 과부하 Provider를 쉬게 하세요.",
                ),
                checklistItem(
                    key = "dashboard_projection",
                    title = "대시보드 Projection 최신성",
                    passed = !dashboard.metadata.stale,
                    warning = dashboard.metadata.stale,
                    evidence = listOf("freshness=${dashboard.metadata.freshnessStatus}", "source=${dashboard.metadata.source}"),
                    nextAction = dashboard.metadata.degradedReason ?: "projection freshness를 확인하세요.",
                ),
                checklistItem(
                    key = "unsafe_quality_reports",
                    title = "품질 신고 검토",
                    passed = dashboard.quality.openReports == 0,
                    warning = dashboard.quality.openReports > 0,
                    evidence = listOf("openReports=${dashboard.quality.openReports}", "feedback=${dashboard.quality.feedbackCount}"),
                    nextAction = "열린 품질 신고를 resolved/dismissed 로 정리하세요.",
                ),
                checklistItem(
                    key = "change_approval_queue",
                    title = "AI 설정 변경 승인 대기열",
                    passed = dashboard.changeApproval.pendingCount == 0 && dashboard.changeApproval.staleCount == 0,
                    warning = dashboard.changeApproval.pendingCount > 0,
                    evidence = listOf("pending=${dashboard.changeApproval.pendingCount}", "stale=${dashboard.changeApproval.staleCount}"),
                    nextAction = "승인/거절되지 않은 AI 설정 변경을 처리하세요.",
                ),
                checklistItem(
                    key = "multi_response_safety",
                    title = "다중응답 안전 게이트",
                    passed = dashboard.multiResponseOperations.safeToEnableAdvanced,
                    warning = !dashboard.multiResponseOperations.safeToEnableAdvanced,
                    evidence =
                        listOf(
                            "status=${dashboard.multiResponseOperations.status}",
                            "riskCodes=${dashboard.multiResponseOperations.riskCodes.joinToString(",")}",
                        ),
                    nextAction = dashboard.multiResponseOperations.nextActions.firstOrNull() ?: "다중응답 운영 상태를 점검하세요.",
                ),
            )
        val items = readinessItems + safetyItems
        val blocked = items.count { it.status == "blocked" }
        val warnings = items.count { it.status == "warning" }
        val status =
            when {
                blocked > 0 -> "blocked"
                warnings > 0 -> "warning"
                else -> "ready"
            }
        return AiNetworkLaunchChecklistResponse(
            guildId = dashboard.overview.guildId,
            status = status,
            score = ((items.count { it.status == "ready" }.toDouble() / items.size.coerceAtLeast(1)) * 100).toInt(),
            readyCount = items.count { it.status == "ready" },
            warningCount = warnings,
            blockedCount = blocked,
            items = items,
            releaseGate = if (blocked == 0) "pass" else "fail",
            nextActions =
                items
                    .filter { it.status != "ready" }
                    .take(8)
                    .map { it.nextAction },
        )
    }

    fun readinessRank(value: String): Int =
        when (value) {
            "needs_profile" -> 0
            "needs_knowledge" -> 1
            "needs_model_policy" -> 2
            else -> 3
        }

    private fun readinessArea(
        key: String,
        title: String,
        status: String,
        score: Int,
        evidence: List<String>,
        nextAction: String,
    ): AiNetworkReadinessAreaResponse =
        AiNetworkReadinessAreaResponse(
            key = key,
            title = title,
            status = status,
            score = score.coerceIn(0, 100),
            evidence = evidence,
            nextAction = nextAction,
        )

    private fun checklistItem(
        key: String,
        title: String,
        passed: Boolean,
        warning: Boolean,
        evidence: List<String>,
        nextAction: String,
    ): AiNetworkLaunchChecklistItemResponse =
        AiNetworkLaunchChecklistItemResponse(
            key = key,
            title = title,
            status =
                when {
                    passed -> "ready"
                    warning -> "warning"
                    else -> "blocked"
                },
            evidence = evidence,
            nextAction = if (passed) "준비됐습니다." else nextAction,
            blocking = !passed && !warning,
        )

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
