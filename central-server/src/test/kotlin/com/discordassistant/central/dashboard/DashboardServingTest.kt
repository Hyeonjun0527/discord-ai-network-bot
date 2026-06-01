package com.discordassistant.central.dashboard

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 대시보드 정적 서빙 + 보안 헤더 UI 스모크(차수 14 #212/#209). 빌드/번들 없이 동일 jar 서빙(#208).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardServingTest
    @Autowired
    constructor(
        val mvc: MockMvc,
    ) {
        @Test
        fun `정적 대시보드 자원이 서빙된다`() {
            mvc.perform(get("/dashboard/index.html")).andExpect(status().isOk)
            mvc.perform(get("/dashboard/app.js")).andExpect(status().isOk)
            mvc.perform(get("/dashboard/style.css")).andExpect(status().isOk)
        }

        @Test
        fun `정적 대시보드가 AI 네트워크 섹션을 포함한다`() {
            val html =
                mvc
                    .perform(get("/dashboard/index.html"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            val js =
                mvc
                    .perform(get("/dashboard/app.js"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString

            assertTrue(html.contains("""id="aiNetwork""""))
            assertTrue(html.contains("""id="growthLevel""""))
            assertTrue(html.contains("""id="growthTimeline""""))
            assertTrue(html.contains("""id="presetCatalog""""))
            assertTrue(html.contains("""id="presetImportResult""""))
            assertTrue(html.contains("""id="changeApproval""""))
            assertTrue(html.contains("""id="qualityReview""""))
            assertTrue(html.contains("""id="wizardJob""""))
            assertTrue(html.contains("""id="wizardCreate""""))
            assertTrue(html.contains("""id="knowledgeChannelId""""))
            assertTrue(html.contains("""id="knowledgeCreateSpace""""))
            assertTrue(html.contains("""id="knowledgeAddSource""""))
            assertTrue(html.contains("""id="knowledgeReadiness""""))
            assertTrue(html.contains("""id="knowledgeIndexing""""))
            assertTrue(html.contains("""id="presetCreate""""))
            assertTrue(html.contains("""id="presetUpdate""""))
            assertTrue(html.contains("""id="presetPublish""""))
            assertTrue(html.contains("""id="presetDelete""""))
            assertTrue(html.contains("""id="presetLike""""))
            assertTrue(html.contains("""id="localPresetList""""))
            assertTrue(html.contains("""id="publishedPresetList""""))
            assertTrue(html.contains("""id="multiSavePolicy""""))
            assertTrue(html.contains("""id="multiRefreshOps""""))
            assertTrue(html.contains("""id="pseudoStreamPlan""""))
            assertTrue(html.contains("""id="multiOps""""))
            assertTrue(html.contains("""id="multiProviderLoad""""))
            assertTrue(html.contains("""id="launchChecklist""""))
            assertTrue(html.contains("""id="launchChecklistRefresh""""))
            assertTrue(js.contains("/api/ai-network/${'$'}{gid}/dashboard?audience=admin"))
            assertTrue(js.contains("/api/ai-network/${'$'}{gid}/launch-checklist?audience=admin"))
            assertTrue(js.contains("/api/ai-network/channel-ai/wizard/options"))
            assertTrue(js.contains("/api/ai-network/channel-ai/wizard/draft"))
            assertTrue(js.contains("/api/ai-network/channel-ai/${'$'}{gid}/${'$'}{channelId}/wizard"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/readiness"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/quality-summary"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/indexing-operations"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/spaces"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/spaces/${'$'}{spaceId}/sources"))
            assertTrue(js.contains("/api/ai-network/presets/guilds/${'$'}{gid}"))
            assertTrue(js.contains("/api/ai-network/presets/catalog?sort=popular&limit=20"))
            assertTrue(js.contains("/api/ai-network/presets/${'$'}{gid}"))
            assertTrue(js.contains("/api/ai-network/presets/${'$'}{presetId}/publish"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{publishedPresetId}/like"))
            assertTrue(js.contains("/api/ai-network/multi-response/${'$'}{gid}/policy"))
            assertTrue(js.contains("/api/ai-network/multi-response/${'$'}{gid}/operations-summary"))
            assertTrue(js.contains("/api/ai-network/multi-response/pseudo-stream-plan"))
            assertTrue(js.contains("renderAiNetwork"))
            assertTrue(js.contains("refreshKnowledge"))
            assertTrue(js.contains("refreshPresets"))
            assertTrue(js.contains("refreshMultiOps"))
            assertTrue(js.contains("refreshLaunchChecklist"))
            assertTrue(js.contains("publishedPresets"))
            assertTrue(js.contains("importPreset"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{publishedPresetId}/import-preview"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{publishedPresetId}/import"))
            assertTrue(js.contains("changeApproval"))
            assertTrue(js.contains("qualityReview"))
            assertTrue(js.contains("createChannelAi"))
        }

        @Test
        fun `디렉터리 URL 도 index 로 포워드된다`() {
            mvc.perform(get("/dashboard/")).andExpect(status().isOk)
            mvc.perform(get("/dashboard")).andExpect(status().isOk)
        }

        @Test
        fun `API 응답에 보안 헤더가 붙는다`() {
            val res = mvc.perform(get("/api/metrics/pool")).andExpect(status().isOk).andReturn()
            assertTrue(res.response.getHeader("X-Content-Type-Options") == "nosniff")
            assertTrue(res.response.getHeader("X-Frame-Options") == "DENY")
            assertTrue(res.response.getHeader("X-Request-Id") != null)
        }
    }
