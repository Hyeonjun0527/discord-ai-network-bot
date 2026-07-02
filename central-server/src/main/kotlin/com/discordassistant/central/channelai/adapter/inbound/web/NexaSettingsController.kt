package com.discordassistant.central.channelai.adapter.inbound.web

import com.discordassistant.central.global.security.DashboardActor
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowApprovalAuthority
import com.discordassistant.central.preset.application.NexaSettingsAdminService
import com.discordassistant.central.preset.application.NexaSettingsStatus
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자 NEXA 설정 API(NEXA-P15-T018). **웹 대시보드 전용**(디스코드 명령 금지 — 메모리 nexa_onboarding_and_policy_ui).
 *
 * **인증**: `/api/ai-network/nexa` 하위는 [com.discordassistant.central.global.security.AiNetworkApiSecurityFilter]
 * 의 sensitive read 목록 + unsafe-method(POST) admin 게이트에 걸린다 — OAuth(허용목록) 또는 admin-token(durable)
 * 없이는 403(권한 없는 사용자 차단). 필터를 통과했다 = 신뢰된 전역 대시보드 관리자다.
 *
 * **권한 번역(T018/T019)**: 통과한 관리자는 shadow 영역 관리 권한([ShadowApprovalAuthority.canManageShadow])을
 * 갖는다. 그러나 **위험한 실제 전송(CANARY/LIVE) 활성화([canEnableRealSend])는 요청 body 의 명시 확인
 * (`confirmLiveSend=true`)을 추가로 요구**한다 — 무심코 LIVE 로 켜지지 않게(T019 "위험한 LIVE 전환은 확인 요구").
 * 권한 부족·확인 누락이면 application 의 도메인 전이가 fail-closed 로 거부한다.
 */
@RestController
@RequestMapping("/api/ai-network/nexa")
class NexaSettingsController(
    private val settings: NexaSettingsAdminService,
) {
    @GetMapping("/{guildId}/settings")
    fun settings(
        @PathVariable guildId: Long,
        @RequestParam(required = false) channelIds: List<Long>?,
        httpRequest: HttpServletRequest,
    ): NexaSettingsStatus {
        DashboardActor.from(httpRequest) // 게이트 통과 확인(필터 누락 시 fail-closed).
        return settings.status(guildId = guildId, channelIds = channelIds ?: emptyList())
    }

    @PostMapping("/{guildId}/lane")
    fun setGuildLane(
        @PathVariable guildId: Long,
        @RequestBody request: NexaLaneChangeRequest,
        httpRequest: HttpServletRequest,
    ): NexaSettingsMutationResponse {
        val actor = DashboardActor.from(httpRequest)
        settings.setGuildLane(
            guildId = guildId,
            requested = request.lane(),
            authority = authorityOf(request.confirmLiveSend),
            actorUserId = actor.userId,
            reason = request.reasonOrDefault(),
        )
        return NexaSettingsMutationResponse.success()
    }

    @PostMapping("/{guildId}/channel/{channelId}/lane")
    fun setChannelLane(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: NexaChannelLaneChangeRequest,
        httpRequest: HttpServletRequest,
    ): NexaSettingsMutationResponse {
        val actor = DashboardActor.from(httpRequest)
        settings.setChannelLane(
            guildId = guildId,
            channelId = channelId,
            requested = request.laneOrNull(),
            authority = authorityOf(request.confirmLiveSend),
            actorUserId = actor.userId,
        )
        return NexaSettingsMutationResponse.success()
    }

    @PostMapping("/{guildId}/channel/{channelId}/excluded")
    fun setChannelExcluded(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: NexaChannelExclusionRequest,
        httpRequest: HttpServletRequest,
    ): NexaSettingsMutationResponse {
        DashboardActor.from(httpRequest)
        // 제외(kill switch)는 끄는 방향이라 항상 허용(realSend 권한 불필요).
        settings.setChannelExcluded(
            guildId = guildId,
            channelId = channelId,
            excluded = request.excluded,
            authority = authorityOf(confirmLiveSend = false),
        )
        return NexaSettingsMutationResponse.success()
    }

    /**
     * 통과한 대시보드 관리자의 권한. shadow 관리(canManageShadow)는 항상, 실제 전송 활성화(canEnableRealSend)는
     * 명시 확인([confirmLiveSend]) 시에만 부여한다(T019 위험 전환 확인 요구).
     */
    private fun authorityOf(confirmLiveSend: Boolean): ShadowApprovalAuthority =
        ShadowApprovalAuthority(canManageShadow = true, canEnableRealSend = confirmLiveSend)
}

/** 길드 lane 변경 요청. lane 은 enum 이름(LEGACY/SHADOW/CANARY/LIVE). 실제 전송 전환은 confirmLiveSend 필요. */
data class NexaLaneChangeRequest(
    val lane: String,
    val confirmLiveSend: Boolean = false,
    val reason: String? = null,
) {
    fun lane(): ParticipationLane = ParticipationLane.valueOf(lane.trim().uppercase())

    fun reasonOrDefault(): String = reason?.trim()?.takeIf { it.isNotBlank() } ?: "대시보드 lane 변경"
}

/** 채널 lane override 변경 요청. lane=null/빈 문자열이면 override 제거(길드 lane 상속). */
data class NexaChannelLaneChangeRequest(
    val lane: String? = null,
    val confirmLiveSend: Boolean = false,
) {
    fun laneOrNull(): ParticipationLane? = lane?.trim()?.takeIf { it.isNotBlank() }?.let { ParticipationLane.valueOf(it.uppercase()) }
}

/** 채널 NEXA 제외(kill switch) 변경 요청. */
data class NexaChannelExclusionRequest(
    val excluded: Boolean,
)

/** NEXA 설정 변경 성공 응답. 기존 JSON shape: `{ "success": true }`. */
data class NexaSettingsMutationResponse(
    val success: Boolean,
) {
    companion object {
        fun success(): NexaSettingsMutationResponse = NexaSettingsMutationResponse(success = true)
    }
}
