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
            mvc.perform(get("/presets/index.html")).andExpect(status().isOk)
            mvc.perform(get("/presets/app.js")).andExpect(status().isOk)
            mvc.perform(get("/presets/style.css")).andExpect(status().isOk)
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
            assertTrue(html.contains("""id="knowledgeQueueJob""""))
            assertTrue(html.contains("""id="knowledgeCompleteJob""""))
            assertTrue(html.contains("""id="knowledgeJobId""""))
            assertTrue(html.contains("""id="knowledgeReadiness""""))
            assertTrue(html.contains("""id="knowledgeIndexing""""))
            assertTrue(html.contains("""id="presetCreate""""))
            assertTrue(html.contains("""id="presetUpdate""""))
            assertTrue(html.contains("""id="presetPublish""""))
            assertTrue(html.contains("""id="presetDelete""""))
            assertTrue(html.contains("""id="presetLike""""))
            assertTrue(html.contains("""id="publishedPresetUpdate""""))
            assertTrue(html.contains("""id="publishedPresetUnlist""""))
            assertTrue(html.contains("""id="publishedPresetRepublish""""))
            assertTrue(html.contains("""id="publishedPresetDelete""""))
            assertTrue(html.contains("""id="publishedPresetReport""""))
            assertTrue(html.contains("""id="presetReportId""""))
            assertTrue(html.contains("""id="presetReportDecision""""))
            assertTrue(html.contains("""id="presetModerationRefresh""""))
            assertTrue(html.contains("""id="presetReportReview""""))
            assertTrue(html.contains("""id="presetModerationList""""))
            assertTrue(html.contains("""id="presetCatalogQuery""""))
            assertTrue(html.contains("""id="presetCatalogCategory""""))
            assertTrue(html.contains("""id="presetCatalogSort""""))
            assertTrue(html.contains("""id="presetCatalogLimit""""))
            assertTrue(html.contains("""id="presetTags""""))
            assertTrue(html.contains("""id="presetExampleQuestions""""))
            assertTrue(html.contains("""id="presetConfirmImport""""))
            assertTrue(html.contains("""id="presetImportPreview""""))
            assertTrue(html.contains("""id="localPresetList""""))
            assertTrue(html.contains("""id="publishedPresetList""""))
            assertTrue(html.contains("""id="multiSavePolicy""""))
            assertTrue(html.contains("""id="multiRefreshOps""""))
            assertTrue(html.contains("""id="pseudoStreamPlan""""))
            assertTrue(html.contains("""id="multiOps""""))
            assertTrue(html.contains("""id="multiProviderLoad""""))
            assertTrue(html.contains("""id="launchChecklist""""))
            assertTrue(html.contains("""id="launchChecklistRefresh""""))
            assertTrue(js.contains("X-Dashboard-Admin-Token"))
            assertTrue(js.contains("sessionStorage"))
            assertTrue(js.contains("/api/ai-network/${'$'}{gid}/dashboard?audience=admin"))
            assertTrue(js.contains("/api/ai-network/${'$'}{gid}/launch-checklist?audience=admin"))
            assertTrue(js.contains("/api/ai-network/channel-ai/wizard/options"))
            assertTrue(js.contains("/api/ai-network/channel-ai/wizard/draft"))
            assertTrue(js.contains("/api/ai-network/channel-ai/${'$'}{gid}/${'$'}{channelId}/wizard"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/readiness"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/quality-summary"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/indexing-operations"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/index-jobs"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/spaces"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/spaces/${'$'}{spaceId}/sources"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/spaces/${'$'}{spaceId}/index-jobs"))
            assertTrue(js.contains("/api/ai-network/knowledge/${'$'}{gid}/index-jobs/${'$'}{jobId}/complete"))
            assertTrue(js.contains("/api/ai-network/presets/guilds/${'$'}{gid}"))
            assertTrue(js.contains("/api/ai-network/presets/catalog?${'$'}{params.toString()}"))
            assertTrue(js.contains("/api/ai-network/presets/${'$'}{gid}"))
            assertTrue(js.contains("/api/ai-network/presets/${'$'}{presetId}/publish"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{publishedPresetId}/like"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{publishedPresetId}"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{publishedPresetId}/unlist"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{publishedPresetId}/republish"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{publishedPresetId}/report"))
            assertTrue(js.contains("/api/ai-network/presets/moderation/summary"))
            assertTrue(js.contains("/api/ai-network/presets/reports/open"))
            assertTrue(js.contains("/api/ai-network/presets/reports/${'$'}{reportId}/review"))
            assertTrue(js.contains("reviewerUserId"))
            assertTrue(js.contains("reviewedBy"))
            assertTrue(js.contains("/api/ai-network/multi-response/${'$'}{gid}/policy"))
            assertTrue(js.contains("/api/ai-network/multi-response/${'$'}{gid}/operations-summary"))
            assertTrue(js.contains("/api/ai-network/multi-response/pseudo-stream-plan"))
            assertTrue(js.contains("renderAiNetwork"))
            assertTrue(js.contains("refreshKnowledge"))
            assertTrue(js.contains("queueKnowledgeIndexJob"))
            assertTrue(js.contains("completeKnowledgeIndexJob"))
            assertTrue(js.contains("refreshPresets"))
            assertTrue(js.contains("publishedPresetPayload"))
            assertTrue(js.contains("updatePublishedPreset"))
            assertTrue(js.contains("unlistPublishedPreset"))
            assertTrue(js.contains("republishPublishedPreset"))
            assertTrue(js.contains("deletePublishedPreset"))
            assertTrue(js.contains("reportPublishedPreset"))
            assertTrue(js.contains("refreshPresetModeration"))
            assertTrue(js.contains("reviewPresetReport"))
            assertTrue(js.contains("refreshMultiOps"))
            assertTrue(js.contains("refreshLaunchChecklist"))
            assertTrue(js.contains("publishedPresets"))
            assertTrue(js.contains("presetCatalogUrl"))
            assertTrue(js.contains("exampleQuestions"))
            assertTrue(js.contains("tags"))
            assertTrue(js.contains("previewPresetImport"))
            assertTrue(js.contains("importPreset"))
            assertTrue(js.contains("sourceRevisionId"))
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
            mvc.perform(get("/presets/")).andExpect(status().isOk)
            mvc.perform(get("/presets")).andExpect(status().isOk)
        }

        @Test
        fun `공개 프리셋 웹 카탈로그가 목록 미리보기 가져오기를 제공한다`() {
            val html =
                mvc
                    .perform(get("/presets/index.html"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            val js =
                mvc
                    .perform(get("/presets/app.js"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString

            assertTrue(html.contains("nyassistant") || html.contains("confirmImport"))
            assertTrue(html.contains("""id="catalog""""))
            assertTrue(html.contains("""id="recommendations""""))
            assertTrue(html.contains("""id="facets""""))
            assertTrue(html.contains("""id="confirmImport""""))
            assertTrue(html.contains("""id="likePreset""""))
            assertTrue(html.contains("""id="reportPreset""""))
            assertTrue(html.contains("""id="adminToken""""))
            assertTrue(html.contains("""id="saveAdminToken""""))
            assertTrue(html.contains("""id="adminTokenStatus""""))
            assertTrue(js.contains("/api/ai-network/presets/catalog?"))
            assertTrue(js.contains("/api/ai-network/presets/catalog/recommended?"))
            assertTrue(js.contains("/api/ai-network/presets/catalog/facets"))
            assertTrue(js.contains("/api/ai-network/presets/catalog/${'$'}{presetLocator}"))
            assertTrue(js.contains("/api/ai-network/presets/catalog/slug/${'$'}{encodeURIComponent(presetLocator)}"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{selectedPresetId}/import-preview"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{pendingImport.publishedPresetId}/import"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{id}/like"))
            assertTrue(js.contains("/api/ai-network/presets/published/${'$'}{id}/report"))
            assertTrue(js.contains("new URLSearchParams(window.location.search).get(\"preset\")"))
            assertTrue(js.contains("nyassistantPresetDashboardAdminToken"))
            assertTrue(js.contains("X-Dashboard-Admin-Token"))
            assertTrue(js.contains("sessionStorage"))
            assertTrue(js.contains("admin: true"))
            assertTrue(js.contains("sourceRevisionId"))
            assertTrue(js.contains("normalizePresetDetail"))
            assertTrue(js.contains("refreshDiscovery"))
            assertTrue(js.contains("renderRecommendations"))
            assertTrue(js.contains("renderFacets"))
            assertTrue(js.contains("root.published"))
            assertTrue(js.contains("behavior.knowledgeSlotNames"))
            assertTrue(js.contains("behavior.knowledgeGuide"))
        }

        @Test
        fun `API 응답에 보안 헤더가 붙는다`() {
            val res = mvc.perform(get("/api/metrics/pool")).andExpect(status().isOk).andReturn()
            assertTrue(res.response.getHeader("X-Content-Type-Options") == "nosniff")
            assertTrue(res.response.getHeader("X-Frame-Options") == "DENY")
            assertTrue(res.response.getHeader("X-Request-Id") != null)
        }
    }
