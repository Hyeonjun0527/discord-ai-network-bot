package com.discordassistant.central.discord

import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
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
class CommandServiceTest @Autowired constructor(
    val commands: CommandService,
    val registry: ConnectionRegistry,
) {
    private fun ctx(admin: Boolean = false) = CommandContext(guildId = 100, channelId = 200, userId = 5, roleIds = setOf(1L), isAdmin = admin)

    @Test
    fun `privacy 안내`() {
        assertTrue(commands.privacy().content.contains("민감한 정보"))
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
    fun `community-stats — 익명 집계, 개별 식별정보 없음`() {
        val r = commands.communityStats(ctx())
        assertTrue(r.content.contains("익명 집계"))
        assertTrue(r.content.contains("활성 프로바이더"))
        assertTrue(!r.ephemeral) // 공개 통계
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
