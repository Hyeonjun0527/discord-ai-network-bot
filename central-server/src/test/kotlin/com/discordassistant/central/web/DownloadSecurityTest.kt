package com.discordassistant.central.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
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
class DownloadSecurityTest
    @Autowired
    constructor(
        private val mvc: MockMvc,
    ) {
        @Test
        fun `download assets stay public when oauth is enabled`() {
            mvc
                .perform(get("/download/nexa-windows.exe"))
                .andExpect(status().isNotFound)
                .andExpect(header().doesNotExist("Location"))
        }

        @Test
        fun `brand assets stay public when oauth is enabled`() {
            mvc
                .perform(get("/assets/nexa-menu-hero.png"))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", containsString("image/png")))
                .andExpect(header().doesNotExist("Location"))
        }

        @Test
        fun `dashboard still requires oauth when oauth is enabled`() {
            mvc
                .perform(get("/admin/dashboard/"))
                .andExpect(status().is3xxRedirection)
                .andExpect(header().string("Location", containsString("/oauth2/authorization/discord")))
        }

        // 데스크톱 앱 관리 API 는 바디의 durable 토큰으로 컨트롤러가 직접 인증한다 → 보안 계층은 permitAll 이어야
        // 한다. permitAll 이 빠지면 OAuth 로그인(302)으로 가서 앱 관리 흐름이 전부 막힌다(실측 버그 회귀 방지).
        @Test
        fun `provider admin manage reaches controller not oauth redirect`() {
            mvc
                .perform(
                    post("/provider/admin/manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"durableToken":"bad","guildId":1,"targetProviderId":0}"""),
                ).andExpect(status().isOk) // 컨트롤러 도달(302 아님)
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.ok").value(false)) // 잘못된 토큰 → 관리자 아님(거부는 컨트롤러가)
        }
    }
