package com.discordassistant.central.dashboard

import com.discordassistant.central.relay.ConnectionRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
    ): Map<String, Any> {
        val pool = registry.byGuild(guildId)
        return mapOf(
            "guildId" to guildId,
            "providers" to
                pool.map {
                    mapOf(
                        "providerId" to it.providerId,
                        "state" to it.state.name,
                        "inFlight" to it.activeRequests,
                        "queued" to it.queueDepth(),
                        "failures" to it.failures,
                        "models" to it.capability.models.size,
                    )
                },
        )
    }
}
