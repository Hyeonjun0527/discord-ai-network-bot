package com.discordassistant.central.guild.adapter.inbound.web

import com.discordassistant.central.global.security.AiNetworkApiSecurityFilter
import com.discordassistant.central.guild.application.DailyLimitPolicy
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.shared.ModelBurden
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 대시보드 정책 쓰기 API(차수 14 #203/#204). 항상 등록되며 인증은 [AiNetworkApiSecurityFilter] 가 강제한다.
 * 그 필터가 `/api/dashboard` 하위의 모든 쓰기(POST/PUT/DELETE)를 관리자 작업으로 보고, OAuth 허용목록
 * 사용자 또는 `X-Dashboard-Admin-Token` 헤더(운영자 토큰)가 있을 때만 통과시킨다 — 둘 다 없으면 403.
 * OAuth 미설정(로컬·A안 토큰 운영)에서도 토큰만으로 동작하므로 운영/로컬 동작이 일치한다.
 * (이전엔 OAuth 활성 시에만 등록되는 조건이 붙어 로컬에서 404 였다.)
 */
@RestController
@RequestMapping("/api/dashboard")
class DashboardWriteController(
    private val policy: PolicyService,
) {
    private val log = LoggerFactory.getLogger(DashboardWriteController::class.java)

    private fun adminId(user: OAuth2User?): Long = (user?.getAttribute<String>("id"))?.toLongOrNull() ?: 0L

    @PostMapping("/{guildId}/auto-approve")
    fun setAutoApprove(
        @PathVariable guildId: Long,
        @RequestParam enabled: Boolean,
        @AuthenticationPrincipal user: OAuth2User?,
    ): AutoApproveDashboardWriteResult {
        policy.setAutoApprove(guildId, enabled, adminId(user))
        // snowflake(guildId/roleId)는 2^53 을 넘어 JS number 로 내리면 정밀도가 깨진다 → 문자열로 직렬화(정확한 ID 보존).
        return AutoApproveDashboardWriteResult(guildId = guildId.toString(), autoApprove = enabled)
    }

    @PostMapping("/{guildId}/welcome")
    fun setWelcome(
        @PathVariable guildId: Long,
        @RequestParam message: String,
        @AuthenticationPrincipal user: OAuth2User?,
    ): WelcomeDashboardWriteResult {
        policy.setWelcomeMessage(guildId, message, adminId(user))
        return WelcomeDashboardWriteResult(guildId = guildId.toString(), ok = true)
    }

    @PostMapping("/{guildId}/role-policy")
    fun setRolePolicy(
        @PathVariable guildId: Long,
        @RequestParam roleId: Long,
        @RequestParam level: String,
        @RequestParam dailyLimit: Int,
        @AuthenticationPrincipal user: OAuth2User?,
    ): RolePolicyDashboardWriteResult {
        // 예외를 흐름제어로 쓰지 않고(예외 원칙 5) enum 을 사전 매칭한다. 잘못된 값은 조용히 LIGHT 로 떨어뜨리지 않고 남긴다.
        val burden = ModelBurden.entries.firstOrNull { it.name == level.uppercase() }
        if (burden == null) log.warn("잘못된 ModelBurden level='{}' → LIGHT 로 폴백", level)
        val resolved = burden ?: ModelBurden.LIGHT
        policy.setRolePolicy(guildId, roleId, resolved, dailyLimit, adminId(user))
        val limit = DailyLimitPolicy.normalize(dailyLimit)
        return RolePolicyDashboardWriteResult(
            guildId = guildId.toString(),
            roleId = roleId.toString(),
            level = resolved.name,
            dailyLimit = limit.value,
            dailyLimitUnlimited = limit.isUnlimited,
            dailyLimitLabel = limit.displayText,
        )
    }
}

sealed interface DashboardWriteResult {
    val guildId: String
}

data class AutoApproveDashboardWriteResult(
    override val guildId: String,
    val autoApprove: Boolean,
) : DashboardWriteResult

data class WelcomeDashboardWriteResult(
    override val guildId: String,
    val ok: Boolean,
) : DashboardWriteResult

data class RolePolicyDashboardWriteResult(
    override val guildId: String,
    val roleId: String,
    val level: String,
    val dailyLimit: Int,
    val dailyLimitUnlimited: Boolean,
    val dailyLimitLabel: String,
) : DashboardWriteResult
