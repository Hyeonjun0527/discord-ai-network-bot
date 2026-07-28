package com.discordassistant.central.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "central.oauth.enabled=true",
        "central.connect.discord-client-id=test-client",
        "central.connect.discord-client-secret=test-secret",
    ],
)
@AutoConfigureMockMvc
class ApiAuthenticationEntryPointTest
    @Autowired
    constructor(
        private val mvc: MockMvc,
    ) {
        @Test
        fun `protected api returns unauthorized without oauth redirect`() {
            mvc
                .perform(get("/api/dashboard/guilds"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().doesNotExist("Location"))
        }

        @Test
        fun `nia web demo message api requires discord login`() {
            mvc
                .perform(
                    post("/api/nia-demo/messages")
                        .contentType("application/json")
                        .content("""{"conversationId":"11111111-1111-4111-8111-111111111111","message":"안녕"}"""),
                ).andExpect(status().isUnauthorized)
                .andExpect(header().doesNotExist("Location"))
        }

        @Test
        fun `discord login session can read nia web demo status`() {
            val principal =
                DefaultOAuth2User(
                    listOf(SimpleGrantedAuthority("ROLE_USER")),
                    mapOf("id" to "123456789012345678"),
                    "id",
                )
            val authentication = OAuth2AuthenticationToken(principal, principal.authorities, "discord")
            val session =
                MockHttpSession().apply {
                    setAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        SecurityContextImpl(authentication),
                    )
                }

            mvc
                .perform(get("/api/nia-demo/status").session(session))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.perUserWindowLimit").value(5))
        }

        @Test
        fun `protected admin page still redirects to oauth login`() {
            mvc
                .perform(get("/admin/console/"))
                .andExpect(status().is3xxRedirection)
                .andExpect(header().string("Location", containsString("/oauth2/authorization/discord")))
        }
    }
