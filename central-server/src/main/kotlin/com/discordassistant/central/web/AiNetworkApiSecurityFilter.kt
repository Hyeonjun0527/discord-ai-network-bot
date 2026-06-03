package com.discordassistant.central.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class AiNetworkApiSecurityFilter(
    @param:Value("\${central.dashboard.admin-token:}") private val adminToken: String,
    @param:Value("\${central.dashboard.admin-user-ids:}") private val adminUserIdsRaw: String,
) : OncePerRequestFilter() {
    // 관리자 Discord user id 허용목록(콤마 구분). 비면 OAuth 로그인만으로는 관리자 권한을 주지 않는다.
    private val adminUserIds: Set<String> =
        adminUserIdsRaw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (requiresAdminAccess(request) && !hasAdminAccess(request)) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(
                """{"error":"dashboard_admin_required","message":"AI 네트워크 관리자 작업에는 Discord OAuth 로그인 또는 X-Dashboard-Admin-Token 헤더가 필요합니다."}""",
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun requiresAdminAccess(request: HttpServletRequest): Boolean {
        if (request.method.equals("OPTIONS", ignoreCase = true)) return false
        val path = normalizedPath(request)
        val method = request.method.uppercase()
        val wantsAdminAudience = request.getParameter("audience")?.equals("admin", ignoreCase = true) == true
        if (
            wantsAdminAudience &&
            (
                path.startsWith("/api/dashboard") ||
                    path.startsWith("/api/metrics") ||
                    path.startsWith("/api/ai-network")
            )
        ) {
            return true
        }
        if (path.startsWith("/api/dashboard") && method in UNSAFE_METHODS) return true
        if (!path.startsWith("/api/ai-network")) return false
        if (isPublicAiNetworkEndpoint(path, method)) return false
        if (method in UNSAFE_METHODS) return true
        return isSensitiveAiNetworkRead(path)
    }

    private fun isPublicAiNetworkEndpoint(
        path: String,
        method: String,
    ): Boolean =
        (method == "GET" && path == "/api/ai-network/features") ||
            (method == "GET" && path.startsWith("/api/ai-network/presets/catalog")) ||
            (method == "POST" && PUBLIC_PRESET_LIKE.matches(path)) ||
            (method == "DELETE" && PUBLIC_PRESET_LIKE.matches(path)) ||
            (method == "POST" && PUBLIC_PRESET_REPORT.matches(path))

    private fun isSensitiveAiNetworkRead(path: String): Boolean =
        SENSITIVE_AI_NETWORK_READ_PREFIXES.any { path.startsWith(it) } ||
            GUILD_DASHBOARD_READ.matches(path) ||
            LAUNCH_CHECKLIST.matches(path)

    private fun hasAdminAccess(request: HttpServletRequest): Boolean {
        val configuredToken = adminToken.trim()
        val headerToken = request.getHeader(ADMIN_TOKEN_HEADER)?.trim()
        if (configuredToken.isNotEmpty() && headerToken == configuredToken) return true
        val authentication = SecurityContextHolder.getContext().authentication ?: return false
        if (!authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) return false
        // OAuth 로그인 사용자는 **허용목록에 있을 때만** 관리자(로그인했다고 무조건 관리자 아님).
        // 허용목록 미설정이면 fail-closed: OAuth 만으로는 통과 불가(관리자 토큰 헤더로만).
        return authentication.name in adminUserIds
    }

    private fun normalizedPath(request: HttpServletRequest): String {
        val contextPath = request.contextPath.orEmpty()
        val uri = request.requestURI.orEmpty()
        return if (contextPath.isNotEmpty() && uri.startsWith(contextPath)) uri.removePrefix(contextPath) else uri
    }

    companion object {
        const val ADMIN_TOKEN_HEADER = "X-Dashboard-Admin-Token"
        private val UNSAFE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        private val PUBLIC_PRESET_LIKE = Regex("^/api/ai-network/presets/published/\\d+/like$")
        private val PUBLIC_PRESET_REPORT = Regex("^/api/ai-network/presets/published/\\d+/report$")
        private val GUILD_DASHBOARD_READ =
            Regex(
                "^/api/ai-network/\\d+/(dashboard|overview|readiness|channels|channels/summary|change-approval|" +
                    "providers|model-map|channel-usage|users|provider-history|knowledge-spaces|presets)$",
            )
        private val LAUNCH_CHECKLIST = Regex("^/api/ai-network/\\d+/launch-checklist$")
        private val SENSITIVE_AI_NETWORK_READ_PREFIXES =
            listOf(
                "/api/ai-network/channel-ai",
                "/api/ai-network/channel-ai-routing",
                "/api/ai-network/growth",
                "/api/ai-network/knowledge",
                "/api/ai-network/multi-response",
                "/api/ai-network/quality",
                "/api/ai-network/safety",
                "/api/ai-network/presets/guilds",
                "/api/ai-network/presets/local",
                "/api/ai-network/presets/moderation",
                "/api/ai-network/presets/reports",
            )
    }
}
