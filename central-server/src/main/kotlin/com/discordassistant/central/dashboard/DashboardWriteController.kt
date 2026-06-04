package com.discordassistant.central.dashboard

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.policy.PolicyService
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
    private fun adminId(user: OAuth2User?): Long = (user?.getAttribute<String>("id"))?.toLongOrNull() ?: 0L

    @PostMapping("/{guildId}/auto-approve")
    fun setAutoApprove(
        @PathVariable guildId: Long,
        @RequestParam enabled: Boolean,
        @AuthenticationPrincipal user: OAuth2User?,
    ): Map<String, Any> {
        policy.setAutoApprove(guildId, enabled, adminId(user))
        return mapOf("guildId" to guildId, "autoApprove" to enabled)
    }

    @PostMapping("/{guildId}/welcome")
    fun setWelcome(
        @PathVariable guildId: Long,
        @RequestParam message: String,
        @AuthenticationPrincipal user: OAuth2User?,
    ): Map<String, Any> {
        policy.setWelcomeMessage(guildId, message, adminId(user))
        return mapOf("guildId" to guildId, "ok" to true)
    }

    @PostMapping("/{guildId}/role-policy")
    fun setRolePolicy(
        @PathVariable guildId: Long,
        @RequestParam roleId: Long,
        @RequestParam level: String,
        @RequestParam dailyLimit: Int,
        @AuthenticationPrincipal user: OAuth2User?,
    ): Map<String, Any> {
        val burden = runCatching { ModelBurden.valueOf(level.uppercase()) }.getOrDefault(ModelBurden.LIGHT)
        policy.setRolePolicy(guildId, roleId, burden, dailyLimit, adminId(user))
        return mapOf("guildId" to guildId, "roleId" to roleId, "level" to burden.name)
    }
}
