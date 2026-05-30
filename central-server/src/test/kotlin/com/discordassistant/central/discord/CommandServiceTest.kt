package com.discordassistant.central.discord

import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

private class EchoConn : AgentConnection {
    lateinit var session: ProviderSession
    override val remoteId = "echo"

    override fun sendFrame(frame: Frame) {
        if (frame is InferRequest) session.handleFrame(InferResult(frame.requestId, "echo:${frame.prompt}"))
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
            assertTrue(admin.contains("/approve-provider"))
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
        fun `ask — echo 프로바이더 연결 시 완료`() {
            val conn = EchoConn()
            val s = ProviderSession(conn, providerId = 77, guildId = 100)
            conn.session = s
            registry.register(s)
            try {
                val r = commands.ask(ctx(), "코드 설명")
                assertTrue(r.content.contains("echo:코드 설명"), r.content)
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
