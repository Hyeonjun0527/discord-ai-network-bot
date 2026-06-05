package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
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

private class DmEcho : AgentConnection {
    lateinit var session: ProviderSession
    override val remoteId = "dm-echo"

    override fun sendFrame(frame: Frame) {
        if (frame is InferRequest) session.handleFrame(InferResult(frame.requestId, "echo:${frame.prompt}"))
    }

    override fun close(reason: String) {}
}

/**
 * DM(봇과의 1:1) 글로벌 풀 라우팅(차수 19). 길드 없이 DM_SCOPE 로 provider-join 이 자동 승인되고,
 * /ask 가 DM_SCOPE 풀의 프로바이더로 라우팅되는지 검증한다.
 */
@SpringBootTest
@Transactional
class DmScopeRoutingTest
    @Autowired
    constructor(
        val commands: CommandService,
        val registry: ConnectionRegistry,
    ) {
        private fun dm(userId: Long) =
            CommandContext(
                guildId = CommandService.DM_SCOPE,
                channelId = 1L,
                userId = userId,
                roleIds = emptySet(),
                isAdmin = false,
            )

        @Test
        fun `DM provider-join 은 관리자 없이 자동 승인된다`() {
            val r = commands.providerJoin(dm(610_001L))
            assertFalse(r.content.contains("승인을 기다려"), r.content) // 대기 아님 = 자동승인(토큰 온보딩) 경로
            assertTrue(r.ephemeral)
        }

        @Test
        fun `DM provider 설치 가이드 — OS 선택 시 GUI 앱 설치 명령 + 재클릭 재발급`() {
            val ctx = dm(610_010L)
            val mac = commands.providerInstallGuide(ctx, "mac")
            assertTrue(mac.content.contains("brew install ollama"), mac.content)
            assertTrue(mac.content.contains("brew install --cask"), mac.content) // 맥 데스크톱 GUI 앱
            assertTrue(mac.content.contains("NEXA"), mac.content)
            assertTrue(mac.ephemeral)
            // 다른 OS 재클릭도 새 토큰으로 동작(reissueToken 경로) — winget 으로 같은 GUI 앱 설치.
            val win = commands.providerInstallGuide(ctx, "windows")
            assertTrue(win.content.contains("winget install"), win.content)
            assertTrue(win.content.contains("Nexa.Nexa"), win.content)
        }

        @Test
        fun `DM ask 는 글로벌 풀(DM_SCOPE) 프로바이더로 라우팅된다`() {
            val conn = DmEcho()
            val session = ProviderSession(conn, providerId = 610_002L, guildId = CommandService.DM_SCOPE)
            conn.session = session
            registry.register(session)
            try {
                val r = commands.ask(dm(610_003L), "코드 설명")
                assertTrue(r.content.contains("echo:코드 설명"), r.content)
            } finally {
                registry.unregister(session)
            }
        }
    }
