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

            val readiness =
                mvc
                    .perform(get("/api/ai-network/presets/web-readiness"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString

            assertTrue(readiness.contains("preview_import"))
            assertTrue(readiness.contains("requiresAdminToken"))
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
        fun `phase2 admin reads are rejected without dashboard token`() {
            mvc
                .perform(get("/api/ai-network/100/channel-usage"))
                .andExpect(status().isForbidden)

            mvc
                .perform(get("/api/ai-network/100/users"))
                .andExpect(status().isForbidden)

            mvc
                .perform(get("/api/ai-network/100/provider-history"))
                .andExpect(status().isForbidden)
        }

        @Test
        fun `phase2 admin reads are allowed with dashboard token`() {
            mvc
                .perform(
                    get("/api/ai-network/100/channel-usage")
                        .header(AiNetworkApiSecurityFilter.ADMIN_TOKEN_HEADER, "test-token"),
                ).andExpect(status().isOk)

            mvc
                .perform(
                    get("/api/ai-network/100/users")
                        .header(AiNetworkApiSecurityFilter.ADMIN_TOKEN_HEADER, "test-token"),
                ).andExpect(status().isOk)

            mvc
                .perform(
                    get("/api/ai-network/100/provider-history")
                        .header(AiNetworkApiSecurityFilter.ADMIN_TOKEN_HEADER, "test-token"),
                ).andExpect(status().isOk)
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
        fun `channel ai reads and writes are admin token protected`() {
            mvc
                .perform(get("/api/ai-network/channel-ai/100/200/history"))
                .andExpect(status().isForbidden)

            mvc
                .perform(
                    post("/api/ai-network/channel-ai/100/200/wizard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"actorUserId":1,"name":"코드냥","job":"개발 질문","tone":"짧고 명확하게"}"""),
                ).andExpect(status().isForbidden)

            mvc
                .perform(
                    get("/api/ai-network/channel-ai/wizard/options")
                        .header(AiNetworkApiSecurityFilter.ADMIN_TOKEN_HEADER, "test-token"),
                ).andExpect(status().isOk)
        }

        @Test
        fun `preset import preview and import are rejected without dashboard token`() {
            val body = """{"targetGuildId":100,"targetChannelId":200,"actorUserId":1}"""
            mvc
                .perform(
                    post("/api/ai-network/presets/published/1/import-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isForbidden)

            mvc
                .perform(
                    post("/api/ai-network/presets/published/1/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isForbidden)
        }

        @Test
        fun `preset publishing visibility changes are rejected without dashboard token`() {
            mvc
                .perform(post("/api/ai-network/presets/published/1/unlist"))
                .andExpect(status().isForbidden)

            mvc
                .perform(post("/api/ai-network/presets/published/1/republish"))
                .andExpect(status().isForbidden)
        }

        @Test
        fun `launch checklist is admin only because it defaults to admin view`() {
            mvc
                .perform(get("/api/ai-network/100/launch-checklist"))
                .andExpect(status().isForbidden)
        }
    }
