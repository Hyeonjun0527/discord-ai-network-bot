package com.discordassistant.central.discord

import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import com.discordassistant.central.usage.UsageService
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

@SpringBootTest
@Transactional // 공유 in-memory DB 오염 방지(테스트 후 롤백)
class CommandServiceTest
    @Autowired
    constructor(
        val commands: CommandService,
        val registry: ConnectionRegistry,
        val usage: UsageService,
    ) {
        private fun ctx(admin: Boolean = false) =
            CommandContext(guildId = 100, channelId = 200, userId = 5, roleIds = setOf(1L), isAdmin = admin)

        @Test
        fun `privacy 안내`() {
            assertTrue(commands.privacy(ctx()).content.contains("민감한 정보"))
        }

        @Test
        fun `help — 유저 섹션은 항상, 관리자 섹션은 관리자만`() {
            val user = commands.help(ctx(admin = false)).content
            assertTrue(user.contains("/ask"))
            assertTrue(!user.contains("__관리자__"))
            val admin = commands.help(ctx(admin = true)).content
            assertTrue(admin.contains("__관리자__"))
            assertTrue(admin.contains("/채널프로필"))
            assertTrue(admin.contains("/llm-channel-profile"))
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
            val r = commands.providerJoin(ctx())
            assertTrue(r.content.contains("승인을 기다려"))
        }

        @Test
        fun `관리자 가드 — 비관리자 채널 허용 거부`() {
            assertTrue(commands.allowChannel(ctx(admin = false), 200).content.contains("⛔"))
            assertFalse(commands.allowChannel(ctx(admin = true), 200).content.contains("⛔"))
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
            assertTrue(commands.setChannelAiProfile(ctx(admin = false), "냥시스턴트", null, false).content.contains("⛔"))

            val set = commands.setChannelAiProfile(admin, "냥시스턴트", null, false).content
            assertTrue(set.contains("냥시스턴트"))
            assertTrue(set.contains("웹후크 관리"))

            assertTrue(commands.setChannelAiProfile(admin, null, null, false).content.contains("냥시스턴트"))
            assertTrue(commands.setChannelAiProfile(admin, null, null, true).content.contains("기본 봇"))
            assertTrue(commands.setChannelAiProfile(admin, null, null, false).content.contains("설정되지 않았습니다"))
        }

        @Test
        fun `ask — echo 프로바이더 연결 시 완료`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 77, guildId = 100)
            conn.session = s
            registry.register(s)
            try {
                val r = commands.ask(ctx(), "코드 설명")
                assertEquals("echo:코드 설명", r.content)
                assertFalse(r.content.contains("커뮤니티 풀 처리"), r.content)
                assertFalse(r.content.contains("provider #"), r.content)
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
        fun `ask — 채널 AI 설정이 있으면 행동 설정을 프롬프트에 반영한다`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 78, guildId = 100)
            conn.session = s
            registry.register(s)
            try {
                commands.setChannelAiProfile(
                    ctx(admin = true),
                    name = "코드냥",
                    avatarUrl = null,
                    reset = false,
                    purpose = "Kotlin 개발 도우미",
                    tone = "짧고 명확하게",
                    answerLength = "짧게",
                    constitution = "코드는 실행 가능한 예시 위주로 답합니다.",
                )

                val r = commands.ask(ctx(), "코드 설명")

                assertTrue(r.content.contains("[채널 AI 행동 설정]"))
                assertTrue(r.content.contains("이름: 코드냥"))
                assertTrue(r.content.contains("역할: Kotlin 개발 도우미"))
                assertTrue(r.content.contains("[사용자 질문]"))
                assertTrue(r.content.endsWith("코드 설명"))
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
