package com.discordassistant.central.global.security

import com.discordassistant.central.global.adapter.inbound.web.ApiError
import com.discordassistant.central.global.adapter.inbound.web.ApiErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class AiNetworkApiSecurityFilter(
    @param:Value("\${central.dashboard.admin-token:}") private val adminToken: String,
    @param:Value("\${central.dashboard.admin-user-ids:}") private val adminUserIdsRaw: String,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
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
        val requiresAdmin = requiresAdminAccess(request)
        if (requiresAdmin) {
            // 통과/차단만 하지 말고 **인증 주체**를 request attribute 로 실어 컨트롤러로 넘긴다(#1):
            // 컨트롤러는 권한/신원을 body 가 아니라 이 주체에서 유도한다(클라이언트 isGuildAdmin/roleIds 불신).
            val actor = resolveAdminActor(request)
            if (actor == null) {
                // 필터는 MVC 밖이라 GlobalExceptionHandler 를 못 탄다 → 같은 통일 모양의 JSON 을 직접 쓴다.
                writeForbidden(response)
                return
            }
            request.setAttribute(DashboardActor.REQUEST_ATTRIBUTE, actor)
        }
        filterChain.doFilter(request, response)
    }

    private fun writeForbidden(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = "application/json;charset=UTF-8"
        val payload =
            ApiErrorResponse(
                error =
                    ApiError(
                        code = "DASHBOARD_ADMIN_REQUIRED",
                        message = "AI 네트워크 관리자 작업에는 Discord OAuth 로그인 또는 X-Dashboard-Admin-Token 헤더가 필요합니다.",
                        failedCondition = "dashboard_admin_authenticated",
                        blockedAction = "AI_NETWORK_ADMIN_ACCESS",
                        actionGuide = "Discord OAuth로 로그인하거나 X-Dashboard-Admin-Token 헤더를 포함해 다시 요청하세요.",
                    ),
                status = HttpServletResponse.SC_FORBIDDEN,
                requestId = MDC.get(RequestIdFilter.MDC_KEY),
            )
        objectMapper.writeValue(response.writer, payload)
    }

    private fun requiresAdminAccess(request: HttpServletRequest): Boolean {
        if (request.method.equals("OPTIONS", ignoreCase = true)) return false
        val path = normalizedPath(request)
        val method = request.method.uppercase()
        val wantsAdminAudience = request.getParameter("audience")?.equals("admin", ignoreCase = true) == true
        if (path.startsWith("/api/admin")) return true
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
        // 서버 목록(이름 포함)·채널 목록은 운영 정보이므로 GET 이라도 관리자만(토큰/OAuth). 어드민 드롭다운용.
        if (path == "/api/dashboard/guilds") return true
        if (DASHBOARD_CHANNELS.matches(path)) return true
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

    /**
     * 관리자 접근을 허용하면 인증 주체([DashboardActor])를, 막으면 null 을 반환한다.
     * self-hosted 단일 운영자 모델: 통과한 주체는 **신뢰된 전역 대시보드 관리자**다.
     */
    private fun resolveAdminActor(request: HttpServletRequest): DashboardActor? {
        val configuredToken = adminToken.trim()
        val headerToken = request.getHeader(ADMIN_TOKEN_HEADER)?.trim()
        // admin-token 헤더 통과 → system 주체(운영자 자동화/CLI). user id 는 없다.
        if (configuredToken.isNotEmpty() && headerToken == configuredToken) {
            return DashboardActor(userId = null, systemToken = true)
        }
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        if (!authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) return null
        // OAuth 로그인 사용자는 **허용목록에 있을 때만** 관리자(로그인했다고 무조건 관리자 아님).
        // 허용목록 미설정이면 fail-closed: OAuth 만으로는 통과 불가(관리자 토큰 헤더로만).
        if (authentication.name !in adminUserIds) return null
        // OAuth 통과 → authentication.name 은 Discord user id. 추적성을 위해 user id 로 실어 넘긴다.
        return DashboardActor(userId = authentication.name.toLongOrNull(), systemToken = false)
    }

    private fun normalizedPath(request: HttpServletRequest): String {
        val contextPath = request.contextPath.orEmpty()
        val uri = request.requestURI.orEmpty()
        return if (contextPath.isNotEmpty() && uri.startsWith(contextPath)) uri.removePrefix(contextPath) else uri
    }

    companion object {
        const val ADMIN_TOKEN_HEADER = "X-Dashboard-Admin-Token"
        private val UNSAFE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        private val DASHBOARD_CHANNELS = Regex("^/api/dashboard/\\d+/channels$")
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
                "/api/ai-network/guild-prompt-set",
                "/api/ai-network/growth",
                "/api/ai-network/knowledge",
                "/api/ai-network/license",
                "/api/ai-network/multi-response",
                "/api/ai-network/nexa",
                "/api/ai-network/quality",
                "/api/ai-network/safety",
                "/api/ai-network/shadow",
                "/api/ai-network/presets/guilds",
                "/api/ai-network/presets/local",
                "/api/ai-network/presets/moderation",
                "/api/ai-network/presets/reports",
            )
    }
}
