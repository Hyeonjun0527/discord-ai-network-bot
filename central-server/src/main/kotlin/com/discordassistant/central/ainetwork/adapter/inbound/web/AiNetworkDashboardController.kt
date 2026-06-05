package com.discordassistant.central.ainetwork.adapter.inbound.web

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkLaunchChecklistResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkOverviewResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkReadinessResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiCardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiChangeApprovalDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelAiFleetSummaryResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ChannelUsageResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.FeatureUserResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.KnowledgeSpaceResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ModelMapResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderCapabilityResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.ProviderHistoryResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.PublishedPresetResponse
import com.discordassistant.central.ainetwork.application.AiNetworkDashboardQueryService
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** AI Network 대시보드 read API. 프롬프트/응답 본문 없이 네트워크 메타데이터만 노출한다. */
@RestController
@RequestMapping("/api/ai-network")
class AiNetworkDashboardController(
    private val query: AiNetworkDashboardQueryService,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    @GetMapping("/{guildId}/dashboard")
    fun dashboard(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
        @RequestParam(defaultValue = "balanced") responseMode: String = "balanced",
        @RequestParam(defaultValue = "1") requestedCandidates: Int = 1,
        @RequestParam(defaultValue = "false") refreshOverview: Boolean = false,
    ): AiNetworkDashboardResponse {
        featureGate.requireDashboardEnabled()
        return query.dashboard(guildId, audience, responseMode, requestedCandidates, refreshOverview)
    }

    @GetMapping("/{guildId}/launch-checklist")
    fun launchChecklist(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "admin") audience: String = "admin",
    ): AiNetworkLaunchChecklistResponse {
        featureGate.requireDashboardEnabled()
        return query.launchChecklist(guildId, audience = audience)
    }

    @GetMapping("/{guildId}/overview")
    fun overview(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "true") refresh: Boolean = true,
    ): AiNetworkOverviewResponse {
        featureGate.requireDashboardEnabled()
        return query.overview(guildId, refresh = refresh)
    }

    @GetMapping("/{guildId}/readiness")
    fun readiness(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): AiNetworkReadinessResponse {
        featureGate.requireDashboardEnabled()
        return query.readiness(guildId, audience = audience)
    }

    @GetMapping("/{guildId}/channels")
    fun channels(
        @PathVariable guildId: Long,
    ): List<ChannelAiCardResponse> {
        featureGate.requireDashboardEnabled()
        return query.channels(guildId)
    }

    @GetMapping("/{guildId}/channels/summary")
    fun channelsSummary(
        @PathVariable guildId: Long,
    ): ChannelAiFleetSummaryResponse {
        featureGate.requireDashboardEnabled()
        return query.channelsSummary(guildId)
    }

    @GetMapping("/{guildId}/change-approval")
    fun changeApproval(
        @PathVariable guildId: Long,
    ): ChannelAiChangeApprovalDashboardResponse {
        featureGate.requireDashboardEnabled()
        return query.changeApproval(guildId)
    }

    @GetMapping("/{guildId}/providers")
    fun providers(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): List<ProviderCapabilityResponse> {
        featureGate.requireDashboardEnabled()
        return query.providers(guildId, audience)
    }

    @GetMapping("/{guildId}/model-map")
    fun modelMap(
        @PathVariable guildId: Long,
    ): List<ModelMapResponse> {
        featureGate.requireDashboardEnabled()
        return query.modelMap(guildId)
    }

    /** 어드민 (a): 채널 사용 현황 — 채널별 요청 수·고유 유저 수·마지막 사용 시각(집계만). */
    @GetMapping("/{guildId}/channel-usage")
    fun channelUsage(
        @PathVariable guildId: Long,
    ): List<ChannelUsageResponse> {
        featureGate.requireDashboardEnabled()
        return query.channelUsage(guildId)
    }

    /** 어드민 (d): 기능 사용 유저 목록 — userId·요청 수·첫/마지막 사용(프롬프트 본문 비노출, 집계만). */
    @GetMapping("/{guildId}/users")
    fun featureUsers(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "20") limit: Int = 20,
    ): List<FeatureUserResponse> {
        featureGate.requireDashboardEnabled()
        return query.featureUsers(guildId, limit)
    }

    /** 어드민 (c): 프로바이더 참여 이력 타임라인. ?providerUserId= 로 특정 프로바이더만 조회 가능. */
    @GetMapping("/{guildId}/provider-history")
    fun providerHistory(
        @PathVariable guildId: Long,
        @RequestParam(required = false) providerUserId: Long? = null,
    ): List<ProviderHistoryResponse> {
        featureGate.requireDashboardEnabled()
        return query.providerHistory(guildId, providerUserId)
    }

    @GetMapping("/{guildId}/knowledge-spaces")
    fun knowledgeSpaces(
        @PathVariable guildId: Long,
    ): List<KnowledgeSpaceResponse> {
        featureGate.requireDashboardEnabled()
        return query.knowledgeSpaces(guildId)
    }

    @GetMapping("/{guildId}/presets")
    fun guildPresets(
        @PathVariable guildId: Long,
    ): Map<String, Any> {
        featureGate.requireDashboardEnabled()
        return query.guildPresets(guildId)
    }

    @GetMapping("/presets/published")
    fun publishedPresets(): List<PublishedPresetResponse> {
        featureGate.requireDashboardEnabled()
        return query.publishedPresets()
    }
}
