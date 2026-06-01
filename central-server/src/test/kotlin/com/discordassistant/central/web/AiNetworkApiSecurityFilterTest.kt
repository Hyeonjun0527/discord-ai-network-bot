package com.discordassistant.central.web

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["central.dashboard.admin-token=test-token"])
class AiNetworkApiSecurityFilterTest
    @Autowired
    constructor(
        private val mvc: MockMvc,
    ) {
        @Test
        fun `public preset catalog stays open`() {
            mvc
                .perform(get("/api/ai-network/presets/catalog"))
                .andExpect(status().isOk)
        }

        @Test
        fun `admin audience is rejected without dashboard token`() {
            val response =
                mvc
                    .perform(get("/api/dashboard/provider/123/history?audience=admin"))
                    .andExpect(status().isForbidden)
                    .andReturn()
                    .response
                    .contentAsString

            assertTrue(response.contains("dashboard_admin_required"))
        }

        @Test
        fun `admin audience is allowed with dashboard token`() {
            mvc
                .perform(
                    get("/api/dashboard/provider/123/history?audience=admin")
                        .header(AiNetworkApiSecurityFilter.ADMIN_TOKEN_HEADER, "test-token"),
                ).andExpect(status().isOk)
        }

        @Test
        fun `guild dashboard reads are rejected without dashboard token`() {
            mvc
                .perform(get("/api/ai-network/100/dashboard"))
                .andExpect(status().isForbidden)

            mvc
                .perform(get("/api/ai-network/100/presets"))
                .andExpect(status().isForbidden)

            mvc
                .perform(get("/api/ai-network/growth/100/timeline"))
                .andExpect(status().isForbidden)
        }

        @Test
        fun `guild dashboard reads are allowed with dashboard token`() {
            mvc
                .perform(
                    get("/api/ai-network/100/dashboard")
                        .header(AiNetworkApiSecurityFilter.ADMIN_TOKEN_HEADER, "test-token"),
                ).andExpect(status().isOk)
        }

        @Test
        fun `ai network writes are rejected without dashboard token`() {
            mvc
                .perform(
                    post("/api/ai-network/presets/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"actorUserId":1,"name":"테스트"}"""),
                ).andExpect(status().isForbidden)
        }

        @Test
        fun `launch checklist is admin only because it defaults to admin view`() {
            mvc
                .perform(get("/api/ai-network/100/launch-checklist"))
                .andExpect(status().isForbidden)
        }
    }
