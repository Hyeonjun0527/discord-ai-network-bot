package com.discordassistant.central.dashboard

import com.discordassistant.central.discord.BotChannelInfo
import com.discordassistant.central.discord.BotGuildInfo
import com.discordassistant.central.discord.BotGuildLister
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.network.AiNetworkFeatureGate
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.requestlog.application.AnalyticsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.math.abs

/**
 * 대시보드 백엔드 API(차수 14 #195): 길드 개요·요청 로그·정책 스냅샷(읽기전용 JSON).
 * 인증/권한(Discord OAuth2, 길드 관리자만)은 #196/#197 에서 추가. 공개 기본값은 집계/요약만 노출하며
 * 프롬프트 본문·Provider snowflake 등 민감 식별자는 포함하지 않는다(프라이버시).
 */
@RestController
@RequestMapping("/api/dashboard")
class DashboardController(
    private val registry: ConnectionRegistry,
    private val policy: PolicyService,
    private val analytics: AnalyticsService,
    private val featureGate: AiNetworkFeatureGate,
    private val botGuilds: BotGuildLister,
) {
    /**
     * 봇이 들어가 있는 서버 목록(id + 이름). 어드민 서버 선택 드롭다운용 — 운영자가 18자리 길드 ID 를
     * 외워 입력하지 않아도 되게 한다. 어드민 전용([AiNetworkApiSecurityFilter] 가 `/api/dashboard/guilds`
     * 를 관리자 토큰/OAuth 로 보호). 봇 비활성/미연결이면 빈 목록.
     */
    @GetMapping("/guilds")
    fun guilds(): List<BotGuildInfo> {
        featureGate.requireDashboardEnabled()
        return botGuilds.botGuilds()
    }

    /**
     * 한 서버의 텍스트 채널 목록(id + 이름). 어드민 채널 선택 드롭다운용 — 채널 ID 를 직접 입력하지 않고
     * 실제 디스코드 채널을 골라 채널 상세로 들어가게 한다. 어드민 전용([AiNetworkApiSecurityFilter] 가
     * `/api/dashboard/{id}/channels` 를 관리자 토큰/OAuth 로 보호). 봇 비활성/서버 미참여면 빈 목록.
     */
    @GetMapping("/{guildId}/channels")
    fun channels(
        @PathVariable guildId: Long,
    ): List<BotChannelInfo> {
        featureGate.requireDashboardEnabled()
        return botGuilds.botChannels(guildId)
    }

    /** 서버 개요: 풀 크기·정책 요약·총 요청 수. */
    @GetMapping("/{guildId}/overview")
    fun overview(
        @PathVariable guildId: Long,
    ): Map<String, Any?> {
        featureGate.requireDashboardEnabled()
        return mapOf(
            "guildId" to guildId,
            "activeProviders" to registry.byGuild(guildId).size,
            "defaultModel" to policy.guildDefaultModel(guildId),
            "language" to policy.guildLanguage(guildId),
            "autoApprove" to policy.isAutoApprove(guildId),
            "totalRequests" to analytics.guildRequestCount(guildId),
        )
    }

    /** 프로바이더 본인 처리 내역(#166): 부하 점수 + 최근 처리(프롬프트/유저 미포함). */
    @GetMapping("/provider/{providerId}/history")
    fun providerHistory(
        @PathVariable providerId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): Map<String, Any?> {
        featureGate.requireDashboardEnabled()
        val visibility = DashboardAudience.from(audience)
        return buildMap {
            put("providerLabel", providerHistoryLabel(providerId, visibility))
            if (visibility.canSeeProviderIdentity) put("providerId", providerId)
            put("computeScore", analytics.providerComputeScore(providerId))
            put("recent", analytics.providerHistory(providerId))
        }
    }

    /** 사용량 트렌드(#227): 최근 days 일의 일자별 요청 수. */
    @GetMapping("/{guildId}/usage-trend")
    fun usageTrend(
        @PathVariable guildId: Long,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") days: Int,
    ): List<AnalyticsService.DailyCount> {
        featureGate.requireDashboardEnabled()
        return analytics.usageTrend(guildId, days)
    }

    /** 최근 요청 로그(최대 20건). 프롬프트 본문 제외, 상태/제공자/시각만. */
    @GetMapping("/{guildId}/requests")
    fun requests(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): List<Map<String, Any?>> {
        featureGate.requireDashboardEnabled()
        return analytics.recentGuildRequests(guildId).mapIndexed { index, request ->
            val visibility = DashboardAudience.from(audience)
            buildMap {
                val providerId = request.providerId
                put("requestId", request.requestId)
                put("state", request.state)
                put("burden", request.requiredBurden)
                put("providerLabel", providerLabel(guildId, providerId, index))
                if (visibility.canSeeProviderIdentity) {
                    put("providerId", providerId)
                }
                put("failReason", request.failReason)
                put("createdAt", request.createdAt)
            }
        }
    }

    private fun providerHistoryLabel(
        providerId: Long,
        audience: DashboardAudience,
    ): String = if (audience.canSeeProviderIdentity) "provider:$providerId" else "Provider"

    private fun providerLabel(
        guildId: Long,
        providerId: Long?,
        fallbackIndex: Int,
    ): String =
        if (providerId == null) {
            "Provider ${fallbackIndex + 1}"
        } else {
            "Provider " + abs("$guildId:$providerId".hashCode()).toString(36).padStart(4, '0').take(6)
        }
}
