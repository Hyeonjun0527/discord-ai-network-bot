package com.discordassistant.central.provider.adapter.inbound.web

import com.discordassistant.central.licensing.application.EntitlementView
import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.platform.discord.BotGuildLister
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.TokenService
import com.discordassistant.central.relay.ConnectionRegistry
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AgentSyncRequest(
    val durableToken: String = "",
)

data class AgentSyncJoinDto(
    val guildId: Long,
    val guildName: String?,
    val token: String,
)

data class AgentSyncResponse(
    val joins: List<AgentSyncJoinDto>,
    // 라이선스 entitlement 동봉(ADR 0005, 차수 4). 신규 필드라 구버전 에이전트는 무시(하위호환).
    val entitlement: EntitlementView? = null,
)

/**
 * 에이전트 자동 동기화(목표: 연동된 사용자의 `/프로바이더참여` 를 앱이 자동 완료).
 *
 * 실행 중인 NEXA 앱이 **durable 토큰**(dv1.…, providerId=Discord userId 인코딩)으로 자신을 인증하면,
 * 그 사용자가 **승인됐지만 아직 연결돼 있지 않은** 서버들에 대한 일회용 토큰을 돌려준다. 앱은 이 토큰으로
 * 그 서버에 자동 연결한다 → 가이드/재설치 없이 참여 완료.
 *
 * 인증: durable 토큰의 HMAC 검증(소모하지 않음). 일회용 토큰(dv1. 아님)은 소모되므로 거부한다.
 * 노출 범위: **같은 사용자**가 승인된 길드뿐(권한 상승 없음).
 */
@RestController
@RequestMapping("/provider/agent")
class ProviderAgentSyncController(
    private val tokens: TokenService,
    private val registration: ProviderRegistrationService,
    private val registry: ConnectionRegistry,
    private val botGuilds: BotGuildLister,
    private val licenses: LicenseService,
) {
    @PostMapping("/sync")
    fun sync(
        @RequestBody req: AgentSyncRequest,
    ): AgentSyncResponse {
        // durable 토큰만 허용(일회용 토큰은 verify 가 소모하므로 보호).
        if (!req.durableToken.startsWith("dv1.")) return AgentSyncResponse(emptyList())
        val binding = tokens.verify(req.durableToken) ?: return AgentSyncResponse(emptyList())
        val providerId = binding.providerId
        // 라이선스 entitlement 동봉(앱 UI 표시·잠금용). 판정 권위는 central, 앱은 표시만.
        val entitlement = licenses.view(providerId)
        val connected = registry.providerGuilds(providerId)
        val joins = registration.joinsToConnect(providerId, connected)
        val names = botGuilds.botGuilds().associate { it.id to it.name }
        return AgentSyncResponse(
            joins.map { AgentSyncJoinDto(it.guildId, names[it.guildId], it.token) },
            entitlement,
        )
    }
}
