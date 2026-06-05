package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkOverviewResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkReadinessAreaResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkReadinessResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiCardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiChangeApprovalDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.KnowledgeSpaceResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ModelMapResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderCapabilityResponse
import org.springframework.stereotype.Component

/**
 * AI Network 준비도 스코어카드 계산 협력자 — 읽기 전용·순수 함수(@Transactional·write·repo 의존 없음).
 *
 * 8개 readiness 영역(provider/model/channel/knowledge/quality/change-approval/provider-safety/projection)
 * 의 status·score 임계값, evidence 문자열, nextAction 문구와 전체 점수·상태 집계를 한곳에 모은다.
 * 본문은 [AiNetworkReadinessService] 에서 1바이트 불변으로 이동했으며 점수 임계값·사용자 노출 문구는
 * 변경하지 않는다.
 */
@Component
class AiNetworkReadinessScorecard {
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
}
