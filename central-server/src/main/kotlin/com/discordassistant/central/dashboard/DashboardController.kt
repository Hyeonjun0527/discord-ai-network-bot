package com.discordassistant.central.dashboard

import com.discordassistant.central.persistence.AiRequestRepository
import com.discordassistant.central.policy.PolicyService
import com.discordassistant.central.relay.ConnectionRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대시보드 백엔드 API(차수 14 #195): 길드 개요·요청 로그·정책 스냅샷(읽기전용 JSON).
 * 인증/권한(Discord OAuth2, 길드 관리자만)은 #196/#197 에서 추가. 현재는 집계/요약만 노출하며
 * 프롬프트 본문 등 민감 정보는 포함하지 않는다(프라이버시).
 */
@RestController
@RequestMapping("/api/dashboard")
class DashboardController(
    private val registry: ConnectionRegistry,
    private val policy: PolicyService,
    private val requests: AiRequestRepository,
    private val analytics: AnalyticsService,
) {
    /** 서버 개요: 풀 크기·정책 요약·총 요청 수. */
    @GetMapping("/{guildId}/overview")
    fun overview(@PathVariable guildId: Long): Map<String, Any?> = mapOf(
        "guildId" to guildId,
        "activeProviders" to registry.byGuild(guildId).size,
        "defaultModel" to policy.guildDefaultModel(guildId),
        "language" to policy.guildLanguage(guildId),
        "autoApprove" to policy.isAutoApprove(guildId),
        "totalRequests" to requests.countByGuildId(guildId),
    )

    /** 프로바이더 본인 처리 내역(#166): 부하 점수 + 최근 처리(프롬프트/유저 미포함). */
    @GetMapping("/provider/{providerId}/history")
    fun providerHistory(@PathVariable providerId: Long): Map<String, Any?> = mapOf(
        "providerId" to providerId,
        "computeScore" to analytics.providerComputeScore(providerId),
        "recent" to analytics.providerHistory(providerId),
    )

    /** 사용량 트렌드(#227): 최근 days 일의 일자별 요청 수. */
    @GetMapping("/{guildId}/usage-trend")
    fun usageTrend(
        @PathVariable guildId: Long,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") days: Int,
    ): List<AnalyticsService.DailyCount> = analytics.usageTrend(guildId, days)

    /** 최근 요청 로그(최대 20건). 프롬프트 본문 제외, 상태/제공자/시각만. */
    @GetMapping("/{guildId}/requests")
    fun requests(@PathVariable guildId: Long): List<Map<String, Any?>> =
        requests.findTop20ByGuildIdOrderByIdDesc(guildId).map {
            mapOf(
                "requestId" to it.requestId,
                "state" to it.state,
                "burden" to it.requiredBurden,
                "providerId" to it.providerId,
                "failReason" to it.failReason,
                "createdAt" to it.createdAt.toString(),
            )
        }
}
