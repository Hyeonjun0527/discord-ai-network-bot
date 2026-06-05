package com.discordassistant.central.global.adapter.inbound.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대시보드 헤더용 로그인 상태 조회. 민감정보 없음(userId·관리자 여부·OAuth 활성 여부만).
 * 공개 엔드포인트(SecurityConfig permitAll) — 미로그인이면 authenticated=false 를 돌려준다.
 */
@RestController
class MeController(
    @param:Value("\${central.oauth.enabled:false}") private val oauthEnabled: Boolean,
    @param:Value("\${central.dashboard.admin-user-ids:}") private val adminUserIdsRaw: String,
) {
    private val adminUserIds: Set<String> =
        adminUserIdsRaw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    @GetMapping("/api/me")
    fun me(): Map<String, Any?> {
        val auth = SecurityContextHolder.getContext().authentication
        val authed = auth != null && auth.isAuthenticated && auth !is AnonymousAuthenticationToken
        val userId = if (authed) auth!!.name else null
        return mapOf(
            "authenticated" to authed,
            "userId" to userId,
            "admin" to (authed && userId in adminUserIds),
            "oauthEnabled" to oauthEnabled,
        )
    }
}
