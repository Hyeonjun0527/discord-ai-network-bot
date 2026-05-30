package com.discordassistant.central.dev

import com.discordassistant.central.provider.ProviderRegistrationService
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.routing.AiRequestInput
import com.discordassistant.central.routing.OrchestrationResult
import com.discordassistant.central.routing.RequestOrchestrator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 개발/E2E 전용 엔드포인트 (K-차수 5 실연동). Discord 없이 토큰 발급·/ask 트리거를 가능하게 한다.
 *
 * **보안**: `central.dev.enabled=true`(기본 false) 일 때만 빈으로 등록된다. **운영에서 절대 켜지 말 것.**
 */
@RestController
@RequestMapping("/dev")
@ConditionalOnProperty(prefix = "central.dev", name = ["enabled"], havingValue = "true")
class DevController(
    private val registration: ProviderRegistrationService,
    private val orchestrator: RequestOrchestrator,
    private val registry: ConnectionRegistry,
) {
    data class TokenReq(
        val providerId: Long,
        val guildId: Long,
    )

    data class AskReq(
        val guildId: Long,
        val channelId: Long = 0,
        val userId: Long = 0,
        val prompt: String,
        val roleIds: Set<Long> = emptySet(),
    )

    /** 프로바이더를 자동 승인 등록하고 일회용 토큰을 발급한다. */
    @PostMapping("/provider-token")
    fun token(
        @RequestBody req: TokenReq,
    ): Map<String, String> {
        val join = registration.requestJoin(req.providerId, req.guildId, autoApprove = true)
        val token = join.token ?: registration.approve(req.providerId, adminId = 0)
        return mapOf("token" to (token ?: ""))
    }

    /** Discord 없이 /ask 를 트리거한다(오케스트레이터 직접 호출). */
    @PostMapping("/ask")
    fun ask(
        @RequestBody req: AskReq,
    ): OrchestrationResult =
        orchestrator.handle(
            AiRequestInput(req.guildId, req.channelId, req.userId, req.prompt, req.roleIds),
        )

    /** 현재 풀 스냅샷(연결된 프로바이더). */
    @GetMapping("/pool")
    fun pool(): Map<String, Any> = registry.snapshot()
}
