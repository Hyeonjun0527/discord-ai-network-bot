package com.discordassistant.central.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/** Keeps the Prometheus scrape endpoint private while allowing the in-stack collector to use a bearer token. */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
class PrometheusScrapeSecurityFilter(
    @param:Value("\${central.metrics.scrape-token:}") private val scrapeToken: String,
    @param:Value("\${central.oauth.enabled:false}") private val oauthEnabled: Boolean,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = normalizedPath(request) != PROMETHEUS_PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val configured = scrapeToken.trim()
        if (configured.isEmpty() && !oauthEnabled) {
            filterChain.doFilter(request, response)
            return
        }
        if (configured.isNotEmpty() && constantTimeEquals(bearerToken(request), configured)) {
            filterChain.doFilter(request, response)
            return
        }
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication?.isAuthenticated == true && authentication !is AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response)
            return
        }
        response.sendError(
            if (configured.isEmpty()) HttpServletResponse.SC_SERVICE_UNAVAILABLE else HttpServletResponse.SC_UNAUTHORIZED,
        )
    }

    private fun bearerToken(request: HttpServletRequest): String? =
        request
            .getHeader("Authorization")
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.drop(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun constantTimeEquals(
        supplied: String?,
        configured: String,
    ): Boolean =
        supplied != null &&
            MessageDigest.isEqual(
                supplied.toByteArray(Charsets.UTF_8),
                configured.toByteArray(Charsets.UTF_8),
            )

    private fun normalizedPath(request: HttpServletRequest): String =
        request.requestURI.orEmpty().removePrefix(request.contextPath.orEmpty())

    companion object {
        const val PROMETHEUS_PATH = "/actuator/prometheus"
        private const val BEARER_PREFIX = "Bearer "
    }
}
