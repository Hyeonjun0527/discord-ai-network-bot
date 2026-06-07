package com.discordassistant.central.discord

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiFeedbackRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingPolicyService
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.channelai.application.ChannelAiCustomizationService
import com.discordassistant.central.guild.adapter.outbound.persistence.AiAdminRoleRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.EmbeddingIndexJobRepository
import com.discordassistant.central.knowledge.application.KnowledgeIngestionService
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponseRunRepository
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.SynthesisResultRepository
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
import com.discordassistant.central.platform.discord.OnboardingStartOutcome
import com.discordassistant.central.preset.application.PresetBehaviorInput
import com.discordassistant.central.preset.application.PresetRegistryService
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import com.discordassistant.central.requestlog.application.UsageService
import com.discordassistant.central.routing.domain.service.ProviderRoutingStats
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.ModelQualityTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

private class EchoConn : AgentConnection {
    lateinit var session: ProviderSession
    var lastInfer: InferRequest? = null
    override val remoteId = "echo"

    override fun sendFrame(frame: Frame) {
        if (frame is InferRequest) {
            lastInfer = frame
            session.handleFrame(InferResult(frame.requestId, "echo:${frame.prompt}"))
        }
    }

    override fun close(reason: String) {}
}

@SpringBootTest(properties = ["central.relay.public-url=wss://discord-ai.yeon.world/agent"])
@Transactional // 공유 in-memory DB 오염 방지(테스트 후 롤백)
class CommandServiceTest
    @Autowired
    constructor(
        val commands: CommandService,
        val registry: ConnectionRegistry,
        val usage: UsageService,
        val knowledge: KnowledgeIngestionService,
        val channelAiCustomization: ChannelAiCustomizationService,
        val channelRoutingPolicies: ChannelAiRoutingPolicyService,
        val providerCapabilities: ProviderCapabilityProfileRepository,
        val presetRegistry: PresetRegistryService,
        val multiResponseRuns: MultiResponseRunRepository,
        val candidateAnswers: CandidateAnswerRepository,
        val synthesisResults: SynthesisResultRepository,
        val embeddingJobs: EmbeddingIndexJobRepository,
        val aiFeedbacks: AiFeedbackRepository,
        val aiAdminRoles: AiAdminRoleRepository,
        val aiLevel: com.discordassistant.central.ainetwork.application.AiLevelService,
        val onboardingOptOuts: com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingOptOutRepository,
        val channelAis: com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository,
        val routingStats: ProviderRoutingStats,
        val globalPromptSets: com.discordassistant.central.globalpromptset.application.GlobalPromptSetService,
    ) {
        private fun ctx(admin: Boolean = false) =
            CommandContext(guildId = 100, channelId = 200, userId = 5, roleIds = setOf(1L), isAdmin = admin)

        private fun markStableProvider(providerId: Long) {
            listOf(ModelBurden.LIGHT, ModelBurden.STANDARD, ModelBurden.HEAVY).forEach { burden ->
                repeat(4) {
                    routingStats.recordSuccess(providerId, burden, latencyMillis = 1_500, outputChars = 240)
                }
            }
        }

        @Test
        fun `privacy 안내`() {
            assertTrue(commands.privacy(ctx()).content.contains("민감한 정보"))
        }

        @Test
        fun `help — 유저 섹션은 항상, 관리자 섹션은 관리자만 (한국어 표기)`() {
            // 기본(default) 로케일은 한국어 — 슬래시 메뉴(/질문 등)와 일치하도록 한국어 명령명으로 표기.
            val user = commands.help(ctx(admin = false)).content
            assertTrue(user.contains("/질문"))
            assertTrue(!user.contains("__관리자__"))
            val admin = commands.help(ctx(admin = true)).content
            assertTrue(admin.contains("__관리자__"))
            assertTrue(admin.contains("/채널프로필"))
            assertTrue(admin.contains("/네트워크지도"))
            assertTrue(admin.contains("/지식목록"))
            assertTrue(admin.contains("/지식추가"))
            assertTrue(admin.contains("/지식검색"))
            assertTrue(admin.contains("/지식색인계획"))
            assertTrue(admin.contains("/지식승인"))
            assertTrue(admin.contains("/지식삭제"))
            assertTrue(admin.contains("/지식색인작업"))
            assertTrue(admin.contains("/지식색인완료"))
            assertTrue(admin.contains("/프리셋목록"))
            assertTrue(admin.contains("/프리셋가져오기"))
            assertTrue(admin.contains("/프리셋검수"))
            assertTrue(admin.contains("/프리셋신고처리"))
            assertTrue(admin.contains("/다중응답상태"))
            assertTrue(admin.contains("/다중응답설정"))
            assertTrue(admin.contains("/다중응답실험"))
            assertTrue(admin.contains("/네트워크점검"))
        }

        @Test
        fun `help — 영어 클라이언트는 영어 명령명으로 표기`() {
            val user =
                commands
                    .help(ctx(admin = false), net.dv8tion.jda.api.interactions.DiscordLocale.ENGLISH_US)
                    .content
            assertTrue(user.contains("/ask"))
            assertTrue(!user.contains("/질문"))
        }

        @Test
        fun `ask — 프로바이더 없으면 안내`() {
            val r = commands.ask(ctx(), "안녕")
            assertTrue(r.content.contains("⚠️"))
        }

        @Test
        fun `ask — 관리자는 쿨다운 우회(#150), 비관리자는 쿨다운 피드백(#191)`() {
            // 전용 키(다른 user)로 공유 RateLimiter 의 다른 테스트 키를 오염시키지 않음.
            val user = CommandContext(guildId = 100, channelId = 200, userId = 9991, roleIds = setOf(1L), isAdmin = false)
            val admin = user.copy(isAdmin = true)
            repeat(10) { commands.ask(user, "q") } // 분당 한도(기본 10) 소진
            val limited = commands.ask(user, "q")
            assertTrue(limited.content.startsWith("⏳"), "11번째는 쿨다운이어야 함")
            // 관리자: 쿨다운 우회(프로바이더 없으니 ⚠️, 단 ⏳ 아님)
            val adminReply = commands.ask(admin, "q")
            assertTrue(!adminReply.content.startsWith("⏳"), "관리자는 쿨다운 우회")
        }

        @Test
        fun `autocompleteModels — 풀 제공 모델 정렬·중복제거(#179)`() {
            val conn = EchoConn()
            val s =
                com.discordassistant.central.relay.ProviderSession(conn, providerId = 42, guildId = 9100).apply {
                    conn.session = this
                    capability =
                        com.discordassistant.central.relay
                            .ProviderCapability(models = listOf("mistral", "llama3", "mistral"))
                }
            registry.register(s)
            try {
                val gctx = CommandContext(guildId = 9100, channelId = 200, userId = 5, roleIds = setOf(1L), isAdmin = false)
                assertEquals(listOf("llama3", "mistral"), commands.autocompleteModels(gctx))
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ephemeral 일관화(#182) — 민감 응답은 비공개, 공개 통계만 공개`() {
            // 민감/개인: ephemeral=true
            assertTrue(commands.privacy(ctx()).ephemeral)
            assertTrue(commands.myUsage(ctx()).ephemeral)
            assertTrue(commands.help(ctx()).ephemeral)
            assertTrue(commands.models(ctx()).ephemeral)
            // 공개 통계: ephemeral=false
            assertFalse(commands.communityStats(ctx()).ephemeral)
        }

        @Test
        fun `community-stats — 익명 집계, 개별 식별정보 없음`() {
            val r = commands.communityStats(ctx())
            assertTrue(r.content.contains("익명 집계"))
            assertTrue(r.content.contains("활성 프로바이더"))
            assertTrue(!r.ephemeral) // 공개 통계
        }

        @Test
        fun `contributions — 오프라인이어도 한 번 기여한 사람은 영구 표시`() {
            val c = CommandContext(guildId = 9900, channelId = 200, userId = 5, roleIds = setOf(1L), isAdmin = false)
            usage.recordSuccess(guildId = c.guildId, userId = 1, providerId = 101, requestId = "pc1")
            usage.recordSuccess(guildId = c.guildId, userId = 2, providerId = 101, requestId = "pc2")
            usage.recordSuccess(guildId = c.guildId, userId = 3, providerId = 202, requestId = "pc3")

            val reply = commands.contributions(c)
            assertTrue(reply.content.contains("<@101> — 2건"))
            assertTrue(reply.content.contains("<@202> — 1건"))
            assertTrue(reply.content.contains("오프라인이어도 계속 기록"))
            assertFalse(reply.ephemeral)
        }

        @Test
        fun `toggleAutoApprove — 관리자만, 토글 동작(#147)`() {
            assertTrue(commands.toggleAutoApprove(ctx(admin = false)).content.contains("관리자만"))
            val g = CommandContext(guildId = 555, channelId = 200, userId = 5, roleIds = setOf(1L), isAdmin = true)
            val first = commands.toggleAutoApprove(g).content
            val second = commands.toggleAutoApprove(g).content
            assertTrue(first.contains("켜짐") || first.contains("꺼짐"))
            assertTrue(first != second) // 토글
        }

        @Test
        fun `setAutoApprove 명시 on off + 모든 채널 허용(패널)`() {
            val g = CommandContext(guildId = 777, channelId = 200, userId = 5, roleIds = setOf(1L), isAdmin = true)
            assertTrue(commands.setAutoApprove(g, enabled = true).content.contains("자동 승인"))
            assertTrue(commands.isAutoApprove(g))
            assertTrue(commands.setAutoApprove(g, enabled = false).content.contains("수동 승인"))
            assertFalse(commands.isAutoApprove(g))
            commands.allowChannel(g, 1111)
            assertTrue(commands.allowedChannelIds(g).contains(1111L))
            commands.allowAllChannels(g)
            assertTrue(commands.allowedChannelIds(g).isEmpty()) // 제한 해제 = 모두 허용
        }

        @Test
        fun `saveGuildSettings — 설정 패널 선택값을 저장 버튼 한 번으로 반영`() {
            val g = CommandContext(guildId = 778, channelId = 200, userId = 5, roleIds = setOf(1L), isAdmin = true)
            val saved =
                commands.saveGuildSettings(
                    g,
                    language = "en",
                    defaultModel = "llama3",
                    allowedChannelIds = listOf(1111L, 2222L),
                    autoApprove = true,
                )
            assertTrue(saved.content.contains("저장했습니다"))
            assertEquals("en", commands.guildLanguage(g))
            assertEquals("llama3", commands.guildDefaultModel(g))
            assertEquals(setOf(1111L, 2222L), commands.allowedChannelIds(g).toSet())
            assertTrue(commands.isAutoApprove(g))

            commands.saveGuildSettings(g, language = "ko", defaultModel = "__auto__", allowedChannelIds = emptyList(), autoApprove = false)
            assertEquals("ko", commands.guildLanguage(g))
            assertEquals(null, commands.guildDefaultModel(g))
            assertTrue(commands.allowedChannelIds(g).isEmpty())
            assertFalse(commands.isAutoApprove(g))
        }

        @Test
        fun `provider-join — 수동 승인이면 대기`() {
            commands.setAutoApprove(ctx(admin = true), enabled = false) // 기본이 자동 승인 → 수동으로 전환
            val r = commands.providerJoin(ctx())
            assertTrue(r.content.contains("승인을 기다려"))
        }

        @Test
        fun `관리자 가드 — 비관리자 채널 허용 거부`() {
            assertTrue(commands.allowChannel(ctx(admin = false), 200).content.contains("⛔"))
            assertFalse(commands.allowChannel(ctx(admin = true), 200).content.contains("⛔"))
        }

        @Test
        fun `preset catalog import like — Discord에서 공유 프리셋을 현재 채널에 적용한다`() {
            val g = CommandContext(guildId = 77992, channelId = 88992, userId = 5, roleIds = setOf(1L), isAdmin = true)
            val preset =
                presetRegistry.createPreset(
                    guildId = g.guildId,
                    ownerUserId = g.userId,
                    name = "코딩 튜터",
                    summary = "개발 질문과 코드 리뷰를 도와주는 프리셋",
                    category = "coding",
                    visibility = "guild_private",
                    behavior =
                        PresetBehaviorInput(
                            purpose = "Kotlin/Spring Boot 개발 질문을 돕습니다.",
                            tone = "정확하고 실용적으로",
                            answerLength = "balanced",
                            constitution = "모르면 모른다고 말하고 실행 가능한 예시를 먼저 제시하기",
                            responseMode = "balanced",
                            maxCandidates = 1,
                        ),
                )
            val published = presetRegistry.publishPreset(preset.id, g.userId, title = null, description = null)

            val catalog = commands.presetCatalog(g, query = "코딩", category = "coding")
            assertTrue(catalog.content.contains("AI 프리셋 공유 목록"))
            assertTrue(catalog.content.contains("코딩 튜터"))
            assertTrue(catalog.content.contains("`${published.id}`"))
            assertTrue(catalog.content.contains("https://discord-ai.yeon.world/presets"))
            val encodedSlug = java.net.URLEncoder.encode(published.slug, Charsets.UTF_8)
            assertTrue(catalog.content.contains("preset=$encodedSlug"))
            assertTrue(catalog.content.contains("웹에서 검색·미리보기·가져오기"))

            val liked = commands.likePreset(g.copy(isAdmin = false), published.id)
            assertTrue(liked.content.contains("좋아요"))

            val imported = commands.importPresetToCurrentChannel(g, published.id)
            assertTrue(imported.content.contains("프리셋을 현재 채널에 가져왔습니다"))
            assertTrue(imported.content.contains("상태: `applied`"))
            assertTrue(imported.content.contains("채널 AI:"))

            val reported = commands.reportPreset(g.copy(isAdmin = false), published.id, "프롬프트가 위험해 보여요 token=hidden")
            assertTrue(reported.content.contains("신고를 접수"))
            val moderation = commands.presetModeration(g).content
            assertTrue(moderation.contains("프리셋 신고/검수 큐"))
            assertTrue(moderation.contains("코딩 튜터"))
            assertTrue(moderation.contains("열린 신고 1"))
            assertTrue(moderation.contains("유형"))
            val report = presetRegistry.listReports().single()
            val reviewed = commands.reviewPresetReport(g, report.id, "dismiss")
            assertTrue(reviewed.content.contains("신고를 처리"))
        }

        @Test
        fun `multi response commands — Discord에서 정책 상태 드라이런을 관리한다`() {
            val g = CommandContext(guildId = 77995, channelId = 88995, userId = 5, roleIds = setOf(1L), isAdmin = true)
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = g.guildId,
                    providerUserId = 701,
                    providerState = ProviderAvailability.ONLINE,
                    modelCount = 1,
                    modelNames = "llama3.1:8b",
                    capabilityTags = "coding,multi-response",
                    qualityTier = ModelQualityTier.HIGH,
                    overloadRisk = OverloadRisk.NORMAL,
                ),
            )

            assertTrue(commands.multiResponseStatus(g.copy(isAdmin = false)).content.contains("관리자만"))

            val saved =
                commands.setMultiResponsePolicy(
                    g,
                    mode = "compare",
                    maxCandidates = 2,
                    synthesisEnabled = true,
                    requireDistinctModels = true,
                    timeoutSeconds = 90,
                )
            assertTrue(saved.content.contains("다중응답 정책을 저장했습니다"))
            assertTrue(saved.content.contains("mode: `compare`"))
            assertTrue(saved.content.contains("후보: `2`"))
            assertTrue(saved.content.contains("opt-in"))

            val dryRun = commands.multiResponseDryRun(g, prompt = "Kotlin Spring 설정을 비교해줘", responseMode = "deep")
            assertTrue(dryRun.content.contains("다중응답 드라이런"))
            assertTrue(dryRun.content.contains("status: `running`"), dryRun.content)
            assertTrue(dryRun.content.contains("후보: `1`"), dryRun.content)

            val status = commands.multiResponseStatus(g).content
            assertTrue(status.contains("다중응답 운영 상태"))
            assertTrue(status.contains("최근 실행: 1"), status)
            assertTrue(status.contains("Provider 부하"))

            val blocked = commands.multiResponseDryRun(g, prompt = "내 DISCORD_BOT_TOKEN=abc 를 여러 Provider로 비교해줘")
            assertTrue(blocked.content.contains("blocked_sensitive"), blocked.content)
            assertTrue(blocked.content.contains("fan-out을 차단"), blocked.content)
        }

        @Test
        fun `ask — 실제 질문 경로도 다중응답 관측 런과 선택 후보를 남긴다`() {
            val g = CommandContext(guildId = 77996, channelId = 88996, userId = 5, roleIds = setOf(1L), isAdmin = true)
            val conn = EchoConn()
            val session = ProviderSession(conn, providerId = 702, guildId = g.guildId)
            conn.session = session
            session.capability = session.capability.copy(models = listOf("llama3.1:8b"))
            registry.register(session)
            markStableProvider(702)
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = g.guildId,
                    providerUserId = 702,
                    providerState = ProviderAvailability.ONLINE,
                    modelCount = 1,
                    modelNames = "llama3.1:8b",
                    capabilityTags = "coding,multi-response",
                    qualityTier = ModelQualityTier.HIGH,
                    overloadRisk = OverloadRisk.NORMAL,
                ),
            )
            channelRoutingPolicies.save(
                guildId = g.guildId,
                channelId = g.channelId,
                responseMode = "deep",
                preferredModel = "llama3.1:8b",
                allowedModels = listOf("llama3.1:8b"),
                minQualityTier = "standard",
                maxCandidates = 2,
                providerTagFilter = listOf("coding"),
                costGuard = "provider_safe",
            )

            try {
                val reply = commands.ask(g, "Kotlin 설정 비교해줘", requestedResponseMode = "deep")

                assertTrue(reply.content.startsWith("echo:"), reply.content)
                val run = multiResponseRuns.findTop20ByGuildIdOrderByStartedAtDesc(g.guildId).single()
                assertEquals("completed", run.status.wire)
                assertEquals(1, run.candidateCount)
                val candidate = candidateAnswers.findByRunId(run.id).single()
                assertEquals(702, candidate.providerUserId)
                assertEquals("completed", candidate.status.wire)
                assertEquals("single_route", candidate.safetyFlags)
                val synthesis = synthesisResults.findByRunId(run.id)!!
                assertEquals("completed", synthesis.status.wire)
                assertEquals("single_route_runtime", synthesis.strategy)
            } finally {
                registry.unregister(session)
            }
        }

        @Test
        fun `knowledge commands — Discord에서 채널 RAG 지식을 추가 목록 검색한다`() {
            val g = CommandContext(guildId = 77993, channelId = 88993, userId = 5, roleIds = setOf(1L), isAdmin = true)

            val added =
                commands.addKnowledge(
                    g,
                    title = "운영 규칙 README",
                    sourceType = "link",
                    sourceUri = "https://example.com/rules",
                    contentPreview = null,
                )
            assertTrue(added.content.contains("지식 소스를 추가했습니다"))
            assertTrue(added.content.contains("status: `pending`"))

            val readiness = knowledge.guildReadiness(g.guildId)
            val spaceId = readiness.spaces.single().knowledgeSpaceId
            val source = knowledge.listSources(g.guildId, spaceId).single()
            val list = commands.knowledgeList(g, spaceId)
            assertTrue(list.content.contains("채널 지식공간 상세"))
            assertTrue(list.content.contains("운영 규칙 README"))

            knowledge.markSourceIndexed(g.guildId, spaceId, source.id, chunkCount = 1)
            val search = commands.searchKnowledge(g, query = "운영 규칙", limit = 3)
            assertTrue(search.content.contains("채널 지식 검색"))
            assertTrue(search.content.contains("운영 규칙 README"))

            val plan = commands.knowledgeIndexPlan(g, spaceId, force = true)
            assertTrue(plan.content.contains("RAG 색인 계획"))
            assertTrue(plan.content.contains("scripts/rag.sh"))

            val deleted = commands.deleteKnowledge(g, spaceId, source.id, reason = "테스트 삭제")
            assertTrue(deleted.content.contains("지식 소스를 삭제했습니다"))
            assertTrue(deleted.content.contains("재색인 작업"))
            val latestJob =
                embeddingJobs
                    .findTop10ByGuildIdAndKnowledgeSpaceIdOrderByQueuedAtDesc(g.guildId, spaceId)
                    .first()
            assertEquals("delete_source", latestJob.jobType)
        }

        @Test
        fun `knowledge add — 텍스트 지식은 즉시 색인되어 검색 가능하다`() {
            val g = CommandContext(guildId = 77997, channelId = 88997, userId = 5, roleIds = setOf(1L), isAdmin = true)

            val added =
                commands.addKnowledge(
                    g,
                    title = "Kotlin Spring 운영 규칙",
                    sourceType = "text",
                    sourceUri = null,
                    contentPreview = "Kotlin Spring 운영은 actuator health 확인 후 rollback plan을 점검합니다.",
                )

            assertTrue(added.content.contains("status: `indexed`"), added.content)
            assertTrue(added.content.contains("즉시 검색 가능"), added.content)
            val search = commands.searchKnowledge(g, query = "actuator", limit = 3)
            assertTrue(search.content.contains("Kotlin Spring 운영 규칙"), search.content)
            assertTrue(search.content.contains("actuator health"), search.content)
        }

        @Test
        fun `knowledge jobs — Discord에서 색인 작업을 조회하고 완료 처리한다`() {
            val g = CommandContext(guildId = 77998, channelId = 88998, userId = 5, roleIds = setOf(1L), isAdmin = true)
            commands.addKnowledge(
                g,
                title = "FAQ",
                sourceType = "text",
                sourceUri = null,
                contentPreview = "자주 묻는 질문과 답변입니다.",
            )
            val job = embeddingJobs.findTop20ByGuildIdOrderByQueuedAtDesc(g.guildId).single()

            val listed = commands.knowledgeIndexJobs(g, limit = 5)
            assertTrue(listed.content.contains("RAG 색인 작업 큐"))
            assertTrue(listed.content.contains("`${job.id}`"))
            assertTrue(listed.content.contains("queued"))

            val completed = commands.completeKnowledgeIndexJob(g, job.id, status = "completed", reason = "qdrant rebuild ok")
            assertTrue(completed.content.contains("status: `completed`"), completed.content)
            val relisted = commands.knowledgeIndexJobs(g, spaceId = job.knowledgeSpaceId, limit = 5)
            assertTrue(relisted.content.contains("completed"), relisted.content)
        }

        @Test
        fun `knowledge approve — 검토 소스를 승인해 색인 대기로 전환한다`() {
            val g = CommandContext(guildId = 77994, channelId = 88994, userId = 5, roleIds = setOf(1L), isAdmin = true)

            val added =
                commands.addKnowledge(
                    g,
                    title = "검토 필요한 HTTP 문서",
                    sourceType = "link",
                    sourceUri = "http://example.com/manual",
                    contentPreview = null,
                )
            assertTrue(added.content.contains("risk: `review`"))

            val spaceId =
                knowledge
                    .guildReadiness(g.guildId)
                    .spaces
                    .single()
                    .knowledgeSpaceId
            val source = knowledge.listSources(g.guildId, spaceId).single()
            val approved = commands.approveKnowledge(g, spaceId, source.id, reason = "공개 문서 확인")
            assertTrue(approved.content.contains("색인 대기 상태로 승인"))
            assertTrue(approved.content.contains("status: `pending`"))
        }

        @Test
        fun `aiNetworkMap — 관리자에게 Provider 모델 채널AI 지도를 보여준다`() {
            val g = CommandContext(guildId = 77991, channelId = 88991, userId = 5, roleIds = setOf(1L), isAdmin = true)
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = g.guildId,
                    providerUserId = 1234,
                    providerState = ProviderAvailability.ONLINE,
                    modelCount = 2,
                    modelNames = "llama3.1:8b,codellama:latest",
                    capabilityTags = "coding,long-context",
                    qualityTier = ModelQualityTier.SPECIALIZED,
                    maxBurden = ModelBurden.STANDARD,
                    overloadRisk = OverloadRisk.NORMAL,
                ),
            )
            commands.setChannelAiProfile(
                g,
                name = "코드 니아",
                avatarUrl = null,
                reset = false,
                purpose = "Kotlin 코드 리뷰",
                tone = "실용적으로",
                answerLength = "balanced",
                constitution = "확실하지 않으면 추측하지 않기",
            )

            val reply = commands.aiNetworkMap(g)

            assertTrue(reply.content.contains("AI 네트워크 지도"))
            assertTrue(reply.content.contains("llama3.1:8b"))
            assertTrue(reply.content.contains("codellama:latest"))
            assertTrue(reply.content.contains("코드 니아"))
            assertTrue(reply.content.contains("Provider: 온라인 1"))
            assertTrue(reply.content.contains("능력 태그"))
        }

        @Test
        fun `level — 서버 니아의 활동 레벨과 경험치를 누구나 본다`() {
            val g = CommandContext(guildId = 55501, channelId = 66601, userId = 9, roleIds = setOf(1L), isAdmin = false)
            // 12회 적립(=120xp) → L2, 구간 (20, 200)
            repeat(12) { aiLevel.awardAskXp(g.guildId) }

            val reply = commands.aiLevel(g)
            assertTrue(reply.content.contains("활동 레벨"))
            assertTrue(reply.content.contains("**2**"), "레벨 2 표시: ${reply.content}")
            assertTrue(reply.content.contains("120 XP"), "누적 XP 표시: ${reply.content}")
            assertFalse(reply.ephemeral, "공개(public) 응답이어야 함")
        }

        @Test
        fun `bot-permissions — 관리자가 봇 권한과 Message Content Intent 안내를 본다`() {
            assertTrue(commands.botPermissions(ctx(admin = false)).content.contains("관리자만"))
            val reply = commands.botPermissions(ctx(admin = true)).content
            assertTrue(reply.contains("Message Content Intent"))
            assertTrue(reply.contains("웹후크 관리"))
            assertTrue(reply.contains("2684734528"))
        }

        @Test
        fun `llm-channel-profile — 관리자가 채널별 AI 응답 프로필을 설정 조회 초기화한다`() {
            val admin = ctx(admin = true)
            assertTrue(commands.setChannelAiProfile(ctx(admin = false), "니아", null, false).content.contains("⛔"))

            val set = commands.setChannelAiProfile(admin, "니아", null, false).content
            assertTrue(set.contains("니아"))
            assertTrue(set.contains("웹후크 관리"))

            assertTrue(commands.setChannelAiProfile(ctx(admin = false), null, null, reset = true).content.contains("⛔"))
            assertTrue(commands.setChannelAiProfile(ctx(admin = false), null, null, reset = false, rollback = true).content.contains("⛔"))
            assertTrue(commands.setChannelAiProfile(admin, null, null, false).content.contains("니아"))
            assertTrue(commands.setChannelAiProfile(admin, null, null, true).content.contains("기본 봇"))
            assertTrue(commands.setChannelAiProfile(admin, null, null, false).content.contains("설정되지 않았습니다"))
        }

        @Test
        fun `llm-channel-profile — AI 관리자 역할이 설정되면 일반 서버 관리자는 변경할 수 없다`() {
            val guildId = 88100L
            val channelId = 88200L
            channelAiCustomization.replaceAiAdminRoles(
                guildId = guildId,
                roleIds = setOf(9001L),
                actorUserId = 5,
                actorIsGuildAdmin = true,
            )
            val ordinaryAdmin = CommandContext(guildId, channelId, userId = 5, roleIds = setOf(1L), isAdmin = true)
            val aiAdmin = ordinaryAdmin.copy(roleIds = setOf(9001L))

            val denied = commands.setChannelAiProfile(ordinaryAdmin, "무단냥", null, false)

            assertTrue(denied.content.contains("AI 관리자 역할"), denied.content)
            val allowed = commands.setChannelAiProfile(aiAdmin, "권한냥", null, false)
            assertTrue(allowed.content.contains("권한냥"), allowed.content)
        }

        // REQ-ONBOARD-003: 비관리자는 자동 온보딩 시작/승인/거절이 모두 거부된다.
        @Test
        fun `ai-onboard — 비관리자는 자동 온보딩 시작 승인 거절이 모두 거부된다`() {
            val user = CommandContext(guildId = 100, channelId = 70300, userId = 5, roleIds = setOf(1L), isAdmin = false)

            val startOutcome = commands.startAutoOnboarding(user, channelName = "dev-help")
            assertTrue(startOutcome is OnboardingStartOutcome.Rejected)
            assertTrue((startOutcome as OnboardingStartOutcome.Rejected).reply.content.contains("⛔"))

            assertTrue(commands.approveOnboarding(user, proposalId = 1L).content.contains("⛔"))
            assertTrue(commands.rejectOnboarding(user, proposalId = 1L).content.contains("⛔"))
        }

        // REQ-ONBOARD-001/002: 관리자는 자동 온보딩으로 PENDING draft 를 만들고 승인해 적용한다.
        @Test
        fun `ai-onboard — 관리자가 휴리스틱 draft PENDING 제안을 만들고 승인한다`() {
            val admin = CommandContext(guildId = 100, channelId = 70301, userId = 5, roleIds = setOf(1L), isAdmin = true)

            val outcome = commands.startAutoOnboarding(admin, channelName = "dev-talk")
            assertTrue(outcome is OnboardingStartOutcome.Started)
            val result = (outcome as OnboardingStartOutcome.Started).result
            assertEquals("pending", result.status)
            assertEquals("코드 니아", result.name)

            val approved = commands.approveOnboarding(admin, result.proposalId)
            assertTrue(approved.content.contains("승인"), approved.content)
        }

        // REQ-ONBOARD-006: 누구나 본인 opt-out 을 등록/해제할 수 있고(관리자 권한 불필요), DB 에 반영된다.
        @Test
        fun `ai-onboard-optout — 본인 opt-out 등록과 해제가 DB 에 반영된다`() {
            val user = CommandContext(guildId = 100, channelId = 70400, userId = 4242, roleIds = setOf(1L), isAdmin = false)
            assertFalse(onboardingOptOuts.existsByGuildIdAndUserId(100, 4242))

            // 등록(enable=true) — 관리자 아님에도 허용.
            val on = commands.setOnboardingOptOut(user, enable = true)
            assertTrue(on.content.contains("제외"), on.content)
            assertTrue(onboardingOptOuts.existsByGuildIdAndUserId(100, 4242))

            // 멱등: 다시 enable=true 면 이미 적용됨 안내(중복 row 안 생김).
            commands.setOnboardingOptOut(user, enable = true)
            assertEquals(1, onboardingOptOuts.findByGuildId(100).count { it.userId == 4242L })

            // 해제(enable=false).
            val off = commands.setOnboardingOptOut(user, enable = false)
            assertTrue(off.content.contains("해제"), off.content)
            assertFalse(onboardingOptOuts.existsByGuildIdAndUserId(100, 4242))

            // 토글(enable=null) — 없으면 등록.
            commands.setOnboardingOptOut(user, enable = null)
            assertTrue(onboardingOptOuts.existsByGuildIdAndUserId(100, 4242))
        }

        // REQ-INSTRUCTION-002: 비관리자의 /ai-instruction 자유 지침 추가는 거부된다.
        @Test
        fun `ai-instruction — 비관리자는 자유 지침을 추가할 수 없다`() {
            val user = CommandContext(guildId = 100, channelId = 70400, userId = 5, roleIds = setOf(1L), isAdmin = false)

            val reply = commands.setChannelAiInstruction(user, "너는 우리 길드 공대장 냥대장이야")
            assertTrue(reply.content.contains("⛔"), reply.content)
        }

        // REQ-INSTRUCTION-001: 관리자의 /ai-instruction 자유 지침은 즉시 적용되지 않고 항상 사람 검토 대기열로 간다(#5).
        @Test
        fun `ai-instruction — 관리자 자유 지침은 즉시 적용이 아니라 검토 대기열로 간다`() {
            val admin = CommandContext(guildId = 100, channelId = 70401, userId = 5, roleIds = setOf(1L), isAdmin = true)
            val created =
                channelAiCustomization.createFromWizard(
                    guildId = 100,
                    channelId = 70401,
                    actorUserId = 5,
                    name = "코드 니아",
                    avatarUrl = null,
                    job = "개발 질문",
                    tone = "친근하게",
                    answerLength = "balanced",
                    constitution = null,
                    requireApproval = false,
                )
            val baseActive = created.behaviorVersionId

            val empty = commands.setChannelAiInstruction(admin, "   ")
            assertTrue(empty.content.contains("자유 지침이 없"), empty.content)

            val applied = commands.setChannelAiInstruction(admin, "너는 우리 길드 공대장 냥대장이야. 반말 쓰고 트수 드립 좋아함")
            // 즉시 적용 문구가 아니라 검토 대기열 안내여야 한다.
            assertTrue(applied.content.contains("검토 대기열"), applied.content)

            // 제안은 PENDING 으로만 만들어지고, 활성 behavior 는 여전히 온보딩 때의 버전이어야 한다(즉시 active 아님).
            assertTrue(channelAiCustomization.pendingProposals(100).any { it.channelId == 70401L })
            val active = channelAis.findByGuildIdAndChannelId(100, 70401)!!.activeBehaviorVersionId
            assertEquals(baseActive, active)
        }

        @Test
        fun `ask — echo 프로바이더 연결 시 완료`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 77, guildId = 100)
            conn.session = s
            registry.register(s)
            try {
                val r = commands.ask(ctx(), "코드 설명")
                // 설정 없는 기본 서버도 NEXA 가드레일 + 기본 정체성(니아)이 항상 주입되고, 사용자 질문은 끝에 전달된다.
                assertTrue(r.content.startsWith("echo:"), r.content)
                assertTrue(r.content.endsWith("코드 설명"), r.content)
                assertTrue(r.content.contains("[우선순위 1: 안전]"), r.content)
                assertTrue(r.content.contains("무관용으로 거부"), r.content)
                assertTrue(r.content.contains("니아"), r.content)
                assertFalse(r.content.contains("커뮤니티 풀 처리"), r.content)
                assertFalse(r.content.contains("provider #"), r.content)
                assertTrue(r.feedback?.requestId?.isNotBlank() == true)
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 길드 전역 프롬프트셋을 기본으로 지정하면 그 페르소나가 ask 에 주입된다`() {
            // 다른 테스트(특히 @DataJpaTest)와 공유 H2 를 오염시키지 않도록 전용 길드로 격리한다.
            val g = 9_100L
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 77, guildId = g)
            conn.session = s
            registry.register(s)
            try {
                // 길드 전역 프롬프트셋을 추가하고 기본으로 지정한다(쿨다운 회피 위해 ask 는 1회만 호출).
                val added = globalPromptSets.add(g, "우리길드 봇", "당신은 우리 길드의 든든한 도우미 「토리」입니다.", 5)
                globalPromptSets.setDefault(g, added.id)

                val gctx = CommandContext(guildId = g, channelId = 200, userId = 5, roleIds = setOf(1L), isAdmin = false)
                val r = commands.ask(gctx, "코드 설명")
                // 가드레일은 여전히 항상 주입되고, 정체성은 니아 대신 지정한 전역 프롬프트셋으로 바뀐다.
                assertTrue(r.content.contains("[우선순위 1: 안전]"), r.content)
                assertTrue(r.content.contains("토리"), r.content)
                assertFalse(r.content.contains("니아"), r.content)
                assertTrue(r.content.endsWith("코드 설명"), r.content)
            } finally {
                registry.unregister(s)
                globalPromptSets.list(g).filter { !it.builtin }.forEach { globalPromptSets.delete(g, it.id) }
            }
        }

        @Test
        fun `ask feedback — 답변 request id로 품질 피드백을 저장하고 중복을 막는다`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 771, guildId = 100)
            conn.session = s
            registry.register(s)
            try {
                val reply = commands.ask(ctx(), "품질 확인")
                val requestId = reply.feedback?.requestId

                assertTrue(requestId?.isNotBlank() == true)
                val first = commands.submitAskFeedback(ctx(), requestId!!, rating = 1, feedbackType = "positive")
                val duplicate = commands.submitAskFeedback(ctx(), requestId, rating = -1, feedbackType = "negative")

                val saved = aiFeedbacks.findTop20ByGuildIdAndChannelIdOrderByCreatedAtDesc(100, 200).single()
                assertTrue(first.content.contains("고마워요"))
                assertTrue(duplicate.content.contains("고마워요"))
                assertEquals(requestId, saved.requestId)
                assertEquals(1, saved.rating)
                assertEquals("positive", saved.feedbackType)
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 긴 공개 답변은 Discord 수정용 의사 스트리밍 스냅샷을 포함한다`() {
            val g = CommandContext(guildId = 77997, channelId = 88997, userId = 5, roleIds = setOf(1L), isAdmin = true)
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 703, guildId = g.guildId)
            conn.session = s
            registry.register(s)
            markStableProvider(703)
            try {
                val longPrompt = "긴 답변이 필요한 질문입니다. ".repeat(40)
                val reply = commands.ask(g, longPrompt, requestedResponseMode = "deep")

                assertFalse(reply.ephemeral)
                assertTrue(reply.content.startsWith("echo:"), reply.content)
                val stream = reply.pseudoStream
                assertTrue(stream != null, "긴 공개 답변에는 의사 스트리밍 계획이 있어야 함")
                assertEquals(3, stream!!.snapshots.size)
                assertTrue(stream.snapshots.first().length < reply.content.length)
                assertEquals(reply.content, stream.snapshots.last())
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 원하는 모델과 응답 모드를 요청에 반영한다`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 79, guildId = 100)
            conn.session = s
            s.capability = s.capability.copy(models = listOf("llama3.1:8b", "qwen-coder"))
            registry.register(s)
            markStableProvider(79)
            try {
                val r = commands.ask(ctx(admin = true), "깊게 봐줘", requestedModel = "qwen-coder", requestedResponseMode = "deep")

                assertTrue(r.content.startsWith("echo:"))
                val sent = conn.lastInfer!!
                assertEquals("qwen-coder", sent.model)
                assertEquals(2048, sent.options["num_predict"])
                assertEquals(0.5, sent.options["temperature"])
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 요청 모델을 못 쓰면 대체 모델과 이유를 유저에게 알려준다`() {
            val guildId = 9300L
            val channelId = 2300L
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 80, guildId = guildId)
            conn.session = s
            s.capability = s.capability.copy(models = listOf("llama3.1:8b"))
            registry.register(s)
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = guildId,
                    providerUserId = 80,
                    providerState = ProviderAvailability.ONLINE,
                    modelNames = "llama3.1:8b",
                    qualityTier = ModelQualityTier.STANDARD,
                    overloadRisk = OverloadRisk.NORMAL,
                ),
            )
            channelRoutingPolicies.save(
                guildId = guildId,
                channelId = channelId,
                responseMode = "balanced",
                preferredModel = "llama3.1:8b",
                allowedModels = listOf("llama3.1:8b", "qwen-coder"),
                minQualityTier = "standard",
                maxCandidates = 1,
                providerTagFilter = emptyList(),
                costGuard = "provider_safe",
            )
            try {
                val r =
                    commands.ask(
                        CommandContext(guildId = guildId, channelId = channelId, userId = 5, roleIds = setOf(1L), isAdmin = true),
                        "깊게 봐줘",
                        requestedModel = "qwen-coder",
                    )

                assertTrue(r.content.contains("↪️ 모델 대체"))
                assertTrue(r.content.contains("요청한 모델을 처리할 온라인 Provider가 없어"))
                assertEquals("llama3.1:8b", conn.lastInfer!!.model)
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 채널 모델 정책을 만족하는 안전 provider가 없으면 요청 모델로 우회 전송하지 않는다`() {
            val guildId = 9301L
            val channelId = 2301L
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 81, guildId = guildId)
            conn.session = s
            s.capability = s.capability.copy(models = listOf("qwen-coder"))
            registry.register(s)
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = guildId,
                    providerUserId = 81,
                    providerState = ProviderAvailability.ONLINE,
                    modelNames = "qwen-coder",
                    qualityTier = ModelQualityTier.SPECIALIZED,
                    overloadRisk = OverloadRisk.CRITICAL,
                ),
            )
            channelRoutingPolicies.save(
                guildId = guildId,
                channelId = channelId,
                responseMode = "balanced",
                preferredModel = null,
                allowedModels = listOf("qwen-coder"),
                minQualityTier = "standard",
                maxCandidates = 1,
                providerTagFilter = emptyList(),
                costGuard = "provider_safe",
            )
            try {
                val r =
                    commands.ask(
                        CommandContext(guildId = guildId, channelId = channelId, userId = 5, roleIds = setOf(1L), isAdmin = true),
                        "깊게 봐줘",
                        requestedModel = "qwen-coder",
                    )

                assertTrue(r.content.contains("요청을 보내지 않았습니다"))
                assertTrue(r.content.contains("Provider 보호"))
                assertEquals(null, conn.lastInfer)
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 채널 AI 설정이 있으면 행동 설정을 프롬프트에 반영한다`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 78, guildId = 100)
            conn.session = s
            registry.register(s)
            try {
                commands.setChannelAiProfile(
                    ctx(admin = true),
                    name = "코드 니아",
                    avatarUrl = null,
                    reset = false,
                    purpose = "Kotlin 개발 도우미",
                    tone = "짧고 명확하게",
                    answerLength = "짧게",
                    constitution = "코드는 실행 가능한 예시 위주로 답합니다.",
                )

                val r = commands.ask(ctx(), "코드 설명")

                assertTrue(r.content.contains("[우선순위 2: 채널 AI 정체성]"), r.content)
                assertTrue(r.content.contains("이름: 코드 니아"), r.content)
                assertTrue(r.content.contains("역할: Kotlin 개발 도우미"), r.content)
                assertTrue(r.content.contains("[우선순위 3: AI 헌법]"), r.content)
                assertTrue(r.content.contains("코드는 실행 가능한 예시 위주로 답합니다."), r.content)
                assertTrue(r.content.contains("[사용자 질문]"), r.content)
                assertTrue(r.content.endsWith("코드 설명"), r.content)
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 새 채널 AI 미리보기 renderer 와 실제 실행 prompt 가 일치한다`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 83, guildId = 100)
            conn.session = s
            registry.register(s)
            try {
                channelAiCustomization.createFromWizard(
                    guildId = 100,
                    channelId = 200,
                    actorUserId = 77,
                    name = "코드 니아",
                    avatarUrl = null,
                    job = "개발 질문",
                    tone = "짧고 명확하게",
                    answerLength = "short",
                    constitution = "코드는 검증 방법을 먼저 제안합니다.",
                    requireApproval = false,
                )
                val preview =
                    channelAiCustomization.promptPreview(
                        guildId = 100,
                        channelId = 200,
                        userQuestion = "Kotlin Spring 설정 알려줘",
                    )

                val reply = commands.ask(ctx(admin = true), "Kotlin Spring 설정 알려줘")

                assertEquals(
                    "echo:${preview.systemPrompt}\n\n${preview.userPrompt}",
                    reply.content,
                    "Discord ask runtime must reuse the same Channel AI prompt renderer as preview",
                )
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 채널 지식 컨텍스트가 있으면 안전하게 프롬프트에 합성한다`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 80, guildId = 100)
            conn.session = s
            registry.register(s)
            try {
                val space = knowledge.createSpace(100, 200, null, "개발 지식", 77, null, null)
                val source =
                    knowledge.addSource(
                        guildId = 100,
                        spaceId = space.id,
                        sourceType = "link",
                        title = "Kotlin Spring 운영 가이드",
                        sourceUri = "https://example.com/kotlin-spring-guide.md",
                        contentPreview = "운영",
                        addedBy = 77,
                    )
                knowledge.markSourceIndexed(100, space.id, source.id, chunkCount = 1)

                val r = commands.ask(ctx(admin = true), "Kotlin Spring 설정 알려줘")

                assertTrue(r.content.contains("[채널 지식 컨텍스트]"))
                assertTrue(r.content.contains("Kotlin Spring 운영 가이드"))
                assertTrue(r.content.contains("[질문 실행 입력]"))
                assertTrue(r.content.endsWith("Kotlin Spring 설정 알려줘"))
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 새 채널 AI 행동 버전과 RAG 컨텍스트를 런타임 프롬프트에 반영한다`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 82, guildId = 100)
            conn.session = s
            registry.register(s)
            markStableProvider(82)
            try {
                channelAiCustomization.createFromWizard(
                    guildId = 100,
                    channelId = 200,
                    actorUserId = 77,
                    name = "코드 니아",
                    avatarUrl = null,
                    job = "개발 질문",
                    tone = "짧고 명확하게",
                    answerLength = "short",
                    constitution = "코드는 검증 방법을 먼저 제안합니다.",
                    requireApproval = false,
                )
                val space = knowledge.createSpace(100, 200, null, "개발 지식", 77, null, null)
                val source =
                    knowledge.addSource(
                        guildId = 100,
                        spaceId = space.id,
                        sourceType = "text",
                        title = "Kotlin Spring 운영 가이드",
                        sourceUri = null,
                        contentPreview = "Kotlin Spring 운영에서는 profile 별 설정을 분리합니다.",
                        addedBy = 77,
                    )
                knowledge.markSourceIndexed(100, space.id, source.id, chunkCount = 1)

                val r = commands.ask(ctx(admin = true), "Kotlin Spring 설정 알려줘", requestedResponseMode = "deep")

                assertTrue(r.content.contains("[우선순위 2: 채널 AI 정체성]"), r.content)
                assertTrue(r.content.contains("이름: 코드 니아"), r.content)
                assertTrue(r.content.contains("코드는 검증 방법을 먼저 제안합니다."), r.content)
                assertTrue(r.content.contains("[우선순위 4: 채널 지식/RAG]"), r.content)
                assertTrue(r.content.contains("Kotlin Spring 운영 가이드"), r.content)
                assertTrue(r.content.contains("[사용자 질문]"), r.content)
                assertTrue(r.content.endsWith("Kotlin Spring 설정 알려줘"), r.content)
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `ask — 응답 모드별 RAG 예산으로 지식 컨텍스트를 제한한다`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 81, guildId = 100)
            conn.session = s
            registry.register(s)
            markStableProvider(81)
            try {
                val space = knowledge.createSpace(100, 200, null, "긴 개발 지식", 77, null, null)
                listOf("A", "B", "C").forEach { prefix ->
                    val source =
                        knowledge.addSource(
                            guildId = 100,
                            spaceId = space.id,
                            sourceType = "link",
                            title = "$prefix Kotlin ${"설정".repeat(50)}",
                            sourceUri = "https://example.com/$prefix/${"very-long-path".repeat(12)}",
                            contentPreview = "운영",
                            addedBy = 77,
                        )
                    knowledge.markSourceIndexed(100, space.id, source.id, chunkCount = 1)
                }

                val saving = commands.ask(ctx(admin = true), "Kotlin 설정", requestedResponseMode = "saving").content
                assertTrue(saving.contains("A Kotlin"))
                assertFalse(saving.contains("C Kotlin"), saving)

                val deep = commands.ask(ctx(admin = true).copy(userId = 6), "Kotlin 설정", requestedResponseMode = "deep").content
                assertTrue(deep.contains("A Kotlin"))
                assertTrue(deep.contains("B Kotlin"))
                assertTrue(deep.contains("C Kotlin"))
            } finally {
                registry.unregister(s)
            }
        }

        @Test
        fun `rate limit — 분당 초과 차단`() {
            val c = CommandContext(guildId = 100, channelId = 200, userId = 8888, roleIds = setOf(1), isAdmin = false)
            repeat(10) { commands.ask(c, "q") }
            assertTrue(commands.ask(c, "q").content.contains("너무 잦"))
        }
    }
