package com.discordassistant.central.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 법적 페이지가 인증 없이 공개 200 으로 열리는지 검증(Discord 개발자포털용 개인정보/약관 URL).
 * 예전엔 legal 경로가 permitAll 에 없어 OAuth 로그인(302)으로 튕겼다 — 회귀 방지.
 */
@SpringBootTest(
    properties = [
        "central.oauth.enabled=true",
        "central.connect.discord-client-id=test-client",
        "central.connect.discord-client-secret=test-secret",
    ],
)
@AutoConfigureMockMvc
class LegalPagePublicTest
    @Autowired
    constructor(
        private val mvc: MockMvc,
    ) {
        // /legal.html 정적 페이지는 인증 없이 200 으로 열린다(예전엔 302 OAuth 로 튕김). MockMvc 는 정적
        // 리소스 본문을 렌더하지 않으므로 보안 통과(200·리다이렉트 없음)만 검증한다.
        @Test
        fun `legal html stays public 200 when oauth is enabled`() {
            mvc
                .perform(get("/legal.html"))
                .andExpect(status().isOk)
                .andExpect(header().doesNotExist("Location"))
        }

        // 별칭은 legal.html 로 서버 포워드(리다이렉트 아님) → 같은 요청에서 200. MockMvc 는 forward 를
        // 따라가 본문을 렌더하지 않으므로 forward 대상과 보안 통과(리다이렉트 없음)를 검증한다.
        @Test
        fun `privacy alias forwards to legal page as public 200`() {
            mvc
                .perform(get("/privacy"))
                .andExpect(status().isOk)
                .andExpect(header().doesNotExist("Location"))
                .andExpect(forwardedUrl("/legal.html"))
        }

        @Test
        fun `terms alias forwards to legal page as public 200`() {
            mvc
                .perform(get("/terms"))
                .andExpect(status().isOk)
                .andExpect(header().doesNotExist("Location"))
                .andExpect(forwardedUrl("/legal.html"))
        }

        @Test
        fun `legal alias forwards to legal page as public 200`() {
            mvc
                .perform(get("/legal"))
                .andExpect(status().isOk)
                .andExpect(header().doesNotExist("Location"))
                .andExpect(forwardedUrl("/legal.html"))
        }

        // 보호 경로는 그대로 보호(회귀 없음) — 법적 경로 공개가 대시보드까지 열지 않는다.
        @Test
        fun `dashboard still requires oauth`() {
            mvc
                .perform(get("/admin/dashboard/"))
                .andExpect(status().is3xxRedirection)
                .andExpect(header().string("Location", containsString("/oauth2/authorization/discord")))
        }
    }
