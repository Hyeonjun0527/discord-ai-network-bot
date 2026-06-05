package com.discordassistant.central.ainetwork.adapter.inbound.web.dto

import com.discordassistant.central.ainetwork.application.AiNetworkLevelStatus
import com.discordassistant.central.ainetwork.application.DashboardAudience
import com.discordassistant.central.ainetwork.application.NetworkGrowthEventCard
import com.discordassistant.central.ainetwork.application.ProviderGrowthResult

// AI Network 성장(growth) API 응답 DTO 모음(인바운드 웹 어댑터의 와이어 계약).
//
// - 응답 JSON 키·값·순서·null·중첩·조건부키는 분해 이전과 1바이트도 다르지 않다.
// - audience 기반 마스킹(보안)·providerLabel 프레젠테이션은 이 파일의 from 안에만 있다.
// - 컨트롤러는 핸들러 반환 타입(Map/List)을 유지한 채 toMap()/toMaps() 로 위임한다(테스트 map-index 접근 보존).
// - 엔티티/리포 의존 0(application 결과만 받는다).

/** provider 라우팅 라벨(audience 마스킹). identity 가시(본인/관리자)면 실식별자, 공개는 익명 번호. */
internal fun providerGrowthLabel(
    providerUserId: Long?,
    index: Int,
    audience: DashboardAudience,
): String =
    if (audience.canSeeProviderIdentity) {
        providerUserId?.let { "provider:$it" } ?: "network"
    } else {
        "Provider ${index + 1}"
    }

data class ProviderJoinedResponse(
    val providerUserId: Long,
    val result: ProviderGrowthResult,
    val audience: DashboardAudience,
) {
    fun toMap(): Map<String, Any?> =
        buildMap {
            put("providerLabel", providerGrowthLabel(providerUserId, 0, audience))
            if (audience.canSeeProviderIdentity) put("providerCapabilityId", result.providerCapabilityId)
            put("eventId", result.eventId)
            put("networkLevel", result.networkLevel)
        }

    companion object {
        fun from(
            providerUserId: Long,
            result: ProviderGrowthResult,
            audience: DashboardAudience,
        ): ProviderJoinedResponse = ProviderJoinedResponse(providerUserId, result, audience)
    }
}

data class AiNetworkLevelStatusResponse(
    val status: AiNetworkLevelStatus,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "guildId" to status.guildId,
            "currentLevel" to status.currentLevel,
            "currentTitle" to status.currentTitle,
            "currentDescription" to status.currentDescription,
            "nextMilestone" to status.nextMilestone,
            "milestones" to status.milestones,
        )

    companion object {
        fun from(status: AiNetworkLevelStatus): AiNetworkLevelStatusResponse = AiNetworkLevelStatusResponse(status)
    }
}

data class GrowthTimelineCardResponse(
    val event: NetworkGrowthEventCard,
    val index: Int,
    val audience: DashboardAudience,
) {
    fun toMap(): Map<String, Any?> =
        buildMap {
            put("id", event.id)
            put("eventType", event.eventType)
            put("providerLabel", providerGrowthLabel(event.providerUserId, index, audience))
            if (audience.canSeeProviderIdentity) {
                put("providerUserId", event.providerUserId)
            }
            put("channelId", event.channelId)
            put("title", event.title)
            put("summary", event.summary)
            put("impactBullets", event.impactBullets)
            put("levelBefore", event.levelBefore)
            put("levelAfter", event.levelAfter)
            put("createdAt", event.createdAt)
        }

    companion object {
        fun from(
            cards: List<NetworkGrowthEventCard>,
            audience: DashboardAudience,
        ): List<Map<String, Any?>> =
            cards.mapIndexed { index, event ->
                GrowthTimelineCardResponse(event, index, audience).toMap()
            }
    }
}
