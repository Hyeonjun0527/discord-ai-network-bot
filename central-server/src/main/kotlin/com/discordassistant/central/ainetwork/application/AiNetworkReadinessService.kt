package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkLaunchChecklistResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkNextActionResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkOverviewResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkReadinessResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiCardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiChangeApprovalDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.KnowledgeSpaceResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ModelMapResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderCapabilityResponse
import org.springframework.stereotype.Service

/**
 * AI Network 대시보드 준비도/체크리스트/추천액션 순수 계산 SSOT.
 * 컨트롤러가 수집한 응답 DTO 와 FeatureGate 스냅샷만 입력으로 받는 순수 함수 계층이라
 * 비즈니스 계산을 웹 어댑터(컨트롤러)에서 분리한다(클린아키텍처: 컨트롤러는 위임만).
 *
 * 응집된 계산 덩어리는 읽기 전용 협력자로 분해했고(스코어카드/next-action 플래너/런치 게이트)
 * 이 파사드는 public 시그니처를 그대로 유지한 채 위임만 한다. 협력자는 생성자 기본값으로 와이어돼
 * 기존 호출자(`AiNetworkReadinessService()`)는 무수정이다.
 */
@Service
class AiNetworkReadinessService(
    private val scorecard: AiNetworkReadinessScorecard = AiNetworkReadinessScorecard(),
    private val nextActionPlanner: AiNetworkNextActionPlanner = AiNetworkNextActionPlanner(),
    private val launchGate: AiNetworkLaunchGate = AiNetworkLaunchGate(),
) {
    fun readiness(
        overview: AiNetworkOverviewResponse,
        channels: List<ChannelAiCardResponse>,
        providers: List<ProviderCapabilityResponse>,
        modelMap: List<ModelMapResponse>,
        knowledgeSpaces: List<KnowledgeSpaceResponse>,
        quality: QualitySummary,
        overload: ProviderSafetyDashboard,
        changeApproval: ChannelAiChangeApprovalDashboardResponse,
    ): AiNetworkReadinessResponse =
        scorecard.readiness(
            overview = overview,
            channels = channels,
            providers = providers,
            modelMap = modelMap,
            knowledgeSpaces = knowledgeSpaces,
            quality = quality,
            overload = overload,
            changeApproval = changeApproval,
        )

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
        nextActionPlanner.nextActions(
            overview = overview,
            channels = channels,
            modelMap = modelMap,
            knowledgeSpaces = knowledgeSpaces,
            quality = quality,
            overload = overload,
            changeApproval = changeApproval,
            growthPlan = growthPlan,
        )

    fun launchChecklist(
        dashboard: AiNetworkDashboardResponse,
        featureSnapshot: AiNetworkFeatureSnapshot,
    ): AiNetworkLaunchChecklistResponse = launchGate.launchChecklist(dashboard, featureSnapshot)

    fun readinessRank(value: String): Int =
        when (value) {
            "needs_profile" -> 0
            "needs_knowledge" -> 1
            "needs_model_policy" -> 2
            else -> 3
        }
}
