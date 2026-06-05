package com.discordassistant.central.web

import com.discordassistant.central.global.security.SecurityHeadersFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse

/** 보안 헤더 필터(차수 14 #209). */
class SecurityHeadersFilterTest {
    @Test
    fun `기본 보안 헤더 부여`() {
        val res = MockHttpServletResponse()
        SecurityHeadersFilter().applyHeaders(res)
        assertEquals("nosniff", res.getHeader("X-Content-Type-Options"))
        assertEquals("DENY", res.getHeader("X-Frame-Options"))
        assertEquals("no-referrer", res.getHeader("Referrer-Policy"))
    }
}
