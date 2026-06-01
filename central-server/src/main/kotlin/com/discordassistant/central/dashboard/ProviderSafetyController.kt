package com.discordassistant.central.dashboard

import com.discordassistant.central.network.ProviderSafetyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai-network/safety")
class ProviderSafetyController(
    private val safety: ProviderSafetyService,
) {
    @GetMapping("/{guildId}/overload-alerts")
    fun overloadAlerts(
        @PathVariable guildId: Long,
    ) = safety.overloadAlerts(guildId)

    @GetMapping("/{guildId}/guard")
    fun guardFanout(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "1") requestedCandidates: Int,
    ) = safety.guardFanout(guildId, requestedCandidates)

    @GetMapping("/{guildId}/execution-plan")
    fun executionPlan(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "balanced") responseMode: String,
        @RequestParam(defaultValue = "1") requestedCandidates: Int,
    ) = safety.executionPlan(guildId, responseMode, requestedCandidates)

    @PostMapping("/{guildId}/providers/{providerUserId}/overload")
    fun markOverload(
        @PathVariable guildId: Long,
        @PathVariable providerUserId: Long,
        @RequestBody request: MarkProviderOverloadRequest,
    ): Map<String, Any?> {
        val result =
            safety.markOverload(
                guildId = guildId,
                providerUserId = providerUserId,
                overloadRisk = request.overloadRisk,
                reason = request.reason,
            )
        return mapOf(
            "providerCapabilityId" to result.providerCapabilityId,
            "eventId" to result.eventId,
            "overloadAlertCount" to result.overview.overloadAlertCount,
            "healthStatus" to result.overview.healthStatus,
        )
    }
}

data class MarkProviderOverloadRequest(
    val overloadRisk: String = "high",
    val reason: String? = null,
)
