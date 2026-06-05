package com.discordassistant.central.ainetwork.adapter.inbound.web

import com.discordassistant.central.relay.ConnectionRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.math.abs

/**
 * 대시보드/관측성용 읽기전용 메트릭 API (차수 15 #226, 차수 14 #195 백엔드 일부).
 * 인증/길드 권한(OAuth2)은 차수 14 #196/#197 에서 추가. 현재는 집계 스냅샷만 노출.
 */
@RestController
@RequestMapping("/api/metrics")
class MetricsApiController(
    private val registry: ConnectionRegistry,
) {
    /** 풀 전역 메트릭: 활성 프로바이더 수, 길드별 풀 크기, 처리중 합계. */
    @GetMapping("/pool")
    fun pool(): Map<String, Any> {
        val sessions = registry.snapshotSessions()
        return mapOf(
            "activeProviders" to sessions.size,
            "inFlightTotal" to sessions.sumOf { it.activeRequests },
            "guildPoolSizes" to
                sessions
                    .groupingBy { it.guildId }
                    .eachCount()
                    .mapKeys { (k, _) -> k?.toString() ?: "unscoped" },
        )
    }

    /** 길드 단위 프로바이더 상세(상태·처리중·실패·모델 수). */
    @GetMapping("/pool/{guildId}")
    fun guild(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "public") audience: String = "public",
    ): Map<String, Any> {
        val pool = registry.byGuild(guildId)
        val visibility = DashboardAudience.from(audience)
        return mapOf(
            "guildId" to guildId,
            "providers" to
                pool.mapIndexed { index, session ->
                    buildMap {
                        put("providerLabel", providerLabel(guildId, session.providerId, index))
                        put("state", visibility.state(session.state.name))
                        put("modelCount", session.capability.models.size)
                        if (visibility.canSeeProviderIdentity) {
                            put("providerId", session.providerId)
                        }
                        if (visibility.canSeeProviderCapacity) {
                            put("inFlight", session.activeRequests)
                            put("queued", session.queueDepth())
                            put("failures", session.failures)
                        }
                    }
                },
        )
    }

    private fun providerLabel(
        guildId: Long,
        providerId: Long,
        fallbackIndex: Int,
    ): String =
        "Provider " +
            abs("$guildId:$providerId:$fallbackIndex".hashCode())
                .toString(36)
                .padStart(4, '0')
                .take(6)
}
