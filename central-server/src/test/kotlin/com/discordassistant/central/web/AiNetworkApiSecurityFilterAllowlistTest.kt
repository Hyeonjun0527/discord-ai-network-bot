package com.discordassistant.central.web

import com.discordassistant.central.global.security.AiNetworkApiSecurityFilter
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * OAuth 로그인 사용자는 **admin 허용목록에 있을 때만** 관리자(로그인만으로는 불가). 단위 테스트.
 */
class AiNetworkApiSecurityFilterAllowlistTest {
    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun authAs(userId: String) {
        SecurityContextHolder.getContext().authentication =
            TestingAuthenticationToken(userId, null).apply { isAuthenticated = true }
    }

    private fun runAdminRead(filter: AiNetworkApiSecurityFilter): Int {
        val req = MockHttpServletRequest("GET", "/api/ai-network/100/dashboard") // 관리자 필요(민감 읽기)
        val res = MockHttpServletResponse()
        var passed = false
        filter.doFilter(req, res, FilterChain { _, _ -> passed = true })
        return if (passed) 200 else res.status
    }

    @Test
    fun `허용목록에 있는 OAuth 사용자만 통과`() {
        val filter = AiNetworkApiSecurityFilter(adminToken = "", adminUserIdsRaw = "111, 222")
        authAs("111")
        assertEquals(200, runAdminRead(filter)) // 허용목록 → 통과
    }

    @Test
    fun `허용목록에 없는 OAuth 사용자는 403`() {
        val filter = AiNetworkApiSecurityFilter(adminToken = "", adminUserIdsRaw = "111, 222")
        authAs("999")
        assertEquals(403, runAdminRead(filter)) // 로그인했어도 목록에 없으면 거부
    }

    @Test
    fun `허용목록 비어 있으면 OAuth 만으로는 거부(fail-closed)`() {
        val filter = AiNetworkApiSecurityFilter(adminToken = "", adminUserIdsRaw = "")
        authAs("111")
        assertEquals(403, runAdminRead(filter))
    }
}
