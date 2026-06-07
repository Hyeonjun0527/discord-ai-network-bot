package com.discordassistant.central.provider.adapter.inbound.web

import com.discordassistant.central.platform.discord.BotGuildLister
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.ProviderRosterInfo
import com.discordassistant.central.provider.application.TokenService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 관리 작업 요청. durableToken=요청자(관리자) 신원, guildId=대상 서버, targetProviderId=대상 프로바이더(목록 조회 땐 무시). */
data class AdminActionRequest(
    val durableToken: String = "",
    val guildId: Long = 0,
    val targetProviderId: Long = 0,
)

data class AdminActionResponse(
    val ok: Boolean,
    val message: String = "",
    val token: String? = null,
)

/** 로스터 항목 — 이름·상태·제공 모델 수·오늘 처리 건수(관리 화면 13). */
data class ManageProviderDto(
    val providerId: Long,
    val name: String?,
    val state: String,
    val models: Int,
    val today: Long,
)

/** 승인 대기 항목 — 아직 미연결이라 이름만(모델/통계는 승인·연결 후). */
data class ManagePendingDto(
    val providerId: Long,
    val name: String?,
)

data class ManagePolicyDto(
    val autoApprove: Boolean,
)

data class ManageResponse(
    val ok: Boolean,
    val policy: ManagePolicyDto? = null,
    val pending: List<ManagePendingDto> = emptyList(),
    val roster: List<ManageProviderDto> = emptyList(),
)

/** 서버 제공 정책 변경 — 신규 자동 승인 토글. */
data class AdminPolicyRequest(
    val durableToken: String = "",
    val guildId: Long = 0,
    val autoApprove: Boolean = false,
)

/**
 * 데스크톱 앱(관리자)용 서버 관리 채널 — Provider 승인/거절/제거 + 목록 조회.
 *
 * 인증·권한(2단):
 *  1. **신원**: durable 토큰(dv1.…, providerId=Discord userId)의 HMAC 검증(소모하지 않음).
 *  2. **권한**: 그 사용자가 **대상 길드의 관리자**(MANAGE_SERVER|ADMINISTRATOR)인지 JDA 로 판정.
 *
 * 둘 다 통과해야만 기존 [ProviderRegistrationService] 의 관리 작업을 수행한다. 권한 상승 불가 —
 * "내 durable 토큰" 으로 "내가 관리자인 서버" 만 관리할 수 있다. (웹 OAuth 대시보드와 동등한 권한, 앱 경로.)
 *
 * 기존 슬래시 명령(/provider-approve 등)·웹 대시보드와 **같은 서비스**를 호출하므로 정책·감사 로그가 일관된다.
 */
@RestController
@RequestMapping("/provider/admin")
class ProviderAdminController(
    private val tokens: TokenService,
    private val registration: ProviderRegistrationService,
    private val botGuilds: BotGuildLister,
    private val roster: ProviderRosterInfo,
) {
    /** durable 토큰 → 요청자 providerId 복원 후 그가 guildId 관리자면 그 id 반환, 아니면 null(거부). */
    private fun authedAdmin(
        durableToken: String,
        guildId: Long,
    ): Long? {
        if (!durableToken.startsWith("dv1.")) return null // 일회용 토큰은 verify 가 소모하므로 거부
        val binding = tokens.verify(durableToken) ?: return null
        return if (botGuilds.isGuildAdmin(guildId, binding.providerId)) binding.providerId else null
    }

    @PostMapping("/approve")
    fun approve(
        @RequestBody req: AdminActionRequest,
    ): AdminActionResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminActionResponse(false, "관리자 권한이 필요합니다")
        val token =
            registration.approve(req.targetProviderId, req.guildId, adminId)
                ?: return AdminActionResponse(false, "승인할 수 없습니다(승인 대기 상태가 아님)")
        return AdminActionResponse(true, "승인됨", token)
    }

    @PostMapping("/reject")
    fun reject(
        @RequestBody req: AdminActionRequest,
    ): AdminActionResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminActionResponse(false, "관리자 권한이 필요합니다")
        return if (registration.reject(req.targetProviderId, req.guildId, adminId)) {
            AdminActionResponse(true, "거절됨")
        } else {
            AdminActionResponse(false, "거절할 수 없습니다(승인 대기 상태가 아님)")
        }
    }

    @PostMapping("/remove")
    fun remove(
        @RequestBody req: AdminActionRequest,
    ): AdminActionResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminActionResponse(false, "관리자 권한이 필요합니다")
        return if (registration.remove(req.targetProviderId, req.guildId, adminId)) {
            AdminActionResponse(true, "제거됨")
        } else {
            AdminActionResponse(false, "제거할 수 없습니다")
        }
    }

    /** 승인 대기·로스터(이름·모델·오늘 건수)·정책 조회(관리 화면 13). 권한 없으면 ok=false. */
    @PostMapping("/manage")
    fun manage(
        @RequestBody req: AdminActionRequest,
    ): ManageResponse {
        authedAdmin(req.durableToken, req.guildId) ?: return ManageResponse(false)
        val models = roster.modelsByProvider(req.guildId)
        val today = roster.todayByProvider(req.guildId)
        val rosterList =
            registration.providersInGuild(req.guildId).map { pid ->
                ManageProviderDto(
                    providerId = pid,
                    name = botGuilds.memberName(req.guildId, pid),
                    state = registration.stateOf(pid, req.guildId)?.name ?: "UNKNOWN",
                    models = models[pid] ?: 0,
                    today = today[pid] ?: 0L,
                )
            }
        val pending = registration.pending(req.guildId).map { ManagePendingDto(it, botGuilds.memberName(req.guildId, it)) }
        return ManageResponse(true, ManagePolicyDto(roster.isAutoApprove(req.guildId)), pending, rosterList)
    }

    /** 서버 제공 정책 — 신규 자동 승인 토글(관리자). 기존 PolicyService 와 동일 저장·감사. */
    @PostMapping("/manage/policy")
    fun setPolicy(
        @RequestBody req: AdminPolicyRequest,
    ): AdminActionResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminActionResponse(false, "관리자 권한이 필요합니다")
        roster.setAutoApprove(req.guildId, req.autoApprove, adminId)
        return AdminActionResponse(true, "정책을 저장했어요")
    }
}
