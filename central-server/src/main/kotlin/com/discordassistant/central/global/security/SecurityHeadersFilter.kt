package com.discordassistant.central.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 기본 보안 응답 헤더(차수 14 #209). 대시보드/REST 응답에 클릭재킹·MIME 스니핑·레퍼러 누출 방어
 * 헤더를 일괄 부여한다. (인증/OAuth 는 #196/#197 별도.)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        applyHeaders(response)
        filterChain.doFilter(request, response)
    }

    /** 헤더 부여 로직(테스트에서 직접 호출 가능). */
    fun applyHeaders(response: HttpServletResponse) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("X-XSS-Protection", "0") // 최신 브라우저 권장값(레거시 필터 비활성)
    }
}
