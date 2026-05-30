package com.discordassistant.central.dashboard

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.policy.PolicyService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 대시보드 정책 쓰기 API(차수 14 #203/#204). **OAuth 활성 시에만 노출**(@ConditionalOnProperty).
 * 비활성(기본)일 땐 빈으로 등록되지 않아 오픈 환경에서 무인증 쓰기가 불가능하다.
 * SecurityConfig 가 이 경로를 인증 사용자로 제한한다.
 */
@RestController
@RequestMapping("/api/dashboard")
@ConditionalOnProperty("central.oauth.enabled")
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
