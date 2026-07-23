package com.discordassistant.central.global.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

class PrometheusScrapeSecurityFilterTest {
    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `운영 scrape token이 맞으면 collector 요청을 통과시킨다`() {
        val response = invoke(token = "secret", oauthEnabled = true, authorization = "Bearer secret")

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `운영 scrape token이 틀리면 401로 차단한다`() {
        val response = invoke(token = "secret", oauthEnabled = true, authorization = "Bearer wrong")

        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `OAuth 운영에서 token이 비어 있으면 공개하지 않고 503으로 닫는다`() {
        val response = invoke(token = "", oauthEnabled = true)

        assertThat(response.status).isEqualTo(503)
    }

    @Test
    fun `기존 OAuth 대시보드 세션은 metrics 조회를 유지한다`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                "operator",
                "n/a",
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        val response = invoke(token = "secret", oauthEnabled = true)

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `OAuth가 꺼진 로컬에서는 token 미설정 시 기존 직접 조회를 유지한다`() {
        val response = invoke(token = "", oauthEnabled = false)

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `다른 endpoint에는 scrape 인증을 적용하지 않는다`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()

        PrometheusScrapeSecurityFilter("secret", true).doFilter(request, response, MockFilterChain())

        assertThat(response.status).isEqualTo(200)
    }

    private fun invoke(
        token: String,
        oauthEnabled: Boolean,
        authorization: String? = null,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", PrometheusScrapeSecurityFilter.PROMETHEUS_PATH)
        authorization?.let { request.addHeader("Authorization", it) }
        val response = MockHttpServletResponse()
        PrometheusScrapeSecurityFilter(token, oauthEnabled).doFilter(request, response, MockFilterChain())
        return response
    }
}
