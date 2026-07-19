package com.discordassistant.central.ainetwork.adapter.inbound.web.dto

import com.discordassistant.central.ainetwork.application.DashboardAudience
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.requestlog.application.AnalyticsService
import kotlin.math.abs

// 대시보드 read API 응답 DTO 모음(인바운드 웹 어댑터의 와이어 계약).
//
// - 응답 JSON 키·값·순서·null·중첩·조건부키는 분해 이전과 1바이트도 다르지 않다.
// - audience 기반 마스킹(보안)·providerLabel 프레젠테이션은 이 파일의 from 안에만 있다.
// - 컨트롤러는 핸들러 반환 타입(Map/List)을 유지한 채 toMap()/toMaps() 로 위임한다(테스트 map-index 접근 보존).
// - 엔티티/리포 의존 0(application 결과·집계 입력만 받는다).

/** 서버 개요: 풀 크기·정책 요약·총 요청 수. */
data class DashboardOverviewResponse(
    val guildId: Long,
    val activeProviders: Int,
    val defaultModel: String?,
    val language: String,
    val autoApprove: Boolean,
    val totalRequests: Long,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "guildId" to guildId,
            "activeProviders" to activeProviders,
            "defaultModel" to defaultModel,
            "language" to language,
            "autoApprove" to autoApprove,
            "totalRequests" to totalRequests,
        )

    companion object {
        fun from(
            guildId: Long,
            registry: ConnectionRegistry,
            policy: PolicyService,
            analytics: AnalyticsService,
        ): DashboardOverviewResponse =
            DashboardOverviewResponse(
                guildId = guildId,
                activeProviders = registry.byGuild(guildId).size,
                defaultModel = policy.guildDefaultModel(guildId),
                language = policy.guildLanguage(guildId),
                autoApprove = policy.isAutoApprove(guildId),
                totalRequests = analytics.guildRequestCount(guildId),
            )
    }
}

/** 프로바이더 본인 처리 내역(#166): 부하 점수 + 최근 처리(프롬프트/유저 미포함). */
data class DashboardProviderHistoryResponse(
    val providerId: Long,
    val computeScore: Long,
    val recent: List<Map<String, Any?>>,
    val audience: DashboardAudience,
) {
    fun toMap(): Map<String, Any?> =
        buildMap {
            put("providerLabel", providerHistoryLabel(providerId, audience))
            if (audience.canSeeProviderIdentity) put("providerId", providerId)
            put("computeScore", computeScore)
            put("recent", recent)
        }

    companion object {
        fun from(
            providerId: Long,
            analytics: AnalyticsService,
            audience: DashboardAudience,
        ): DashboardProviderHistoryResponse =
            DashboardProviderHistoryResponse(
                providerId = providerId,
                computeScore = analytics.providerComputeScore(providerId),
                recent = analytics.providerHistory(providerId),
                audience = audience,
            )

        private fun providerHistoryLabel(
            providerId: Long,
            audience: DashboardAudience,
        ): String = if (audience.canSeeProviderIdentity) "provider:$providerId" else "Provider"
    }
}

/** 최근 요청 로그(최대 20건). 프롬프트 본문 제외, 상태/제공자/시각만. */
data class DashboardRequestLogResponse(
    val guildId: Long,
    val request: AnalyticsService.RequestLogEntry,
    val index: Int,
    val audience: DashboardAudience,
) {
    fun toMap(): Map<String, Any?> =
        buildMap {
            val providerId = request.providerId
            put("requestId", request.requestId)
            put("channelId", request.channelId)
            put("state", request.state)
            put("burden", request.requiredBurden)
            put("providerLabel", providerLabel(guildId, providerId, index))
            if (audience.canSeeProviderIdentity) {
                put("providerId", providerId)
            }
            put("failReason", request.failReason)
            put("createdAt", request.createdAt)
        }

    companion object {
        fun from(
            guildId: Long,
            requests: List<AnalyticsService.RequestLogEntry>,
            audience: DashboardAudience,
        ): List<Map<String, Any?>> =
            requests.mapIndexed { index, request ->
                DashboardRequestLogResponse(guildId, request, index, audience).toMap()
            }

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
}
