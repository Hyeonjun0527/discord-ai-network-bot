package com.discordassistant.central.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * correlation/request id 를 MDC 에 심는다(차수 15 #218). 들어오는 `X-Request-Id` 를 우선 사용하고
 * 없으면 생성한다. 로그 패턴(`%X{requestId}`)과 응답 헤더로 추적성을 확보한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val incoming = request.getHeader(HEADER)?.takeIf { it.isNotBlank() && it.length <= 64 }
        val requestId = incoming ?: UUID.randomUUID().toString()
        MDC.put(MDC_KEY, requestId)
        response.setHeader(HEADER, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
    }
}
