package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * DM(봇과의 1:1) 글로벌 풀 인프라(차수 19). 길드 없이 DM_SCOPE 로 provider-join(기여)이 자동 승인되는지 검증.
 * 단, DM /ask·/ask-long(AI 호출)은 멤버십 게이트로 비활성화됨 — 그 서버 멤버만 호출 가능(아래 NOTE).
 */
@SpringBootTest
@Transactional
class DmScopeRoutingTest
    @Autowired
    constructor(
        val commands: CommandService,
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

        // NOTE: DM /ask·/ask-long 은 의도적으로 비활성화됨(DiscordBot.DM_COMMANDS 에서 제외) —
        // AI 호출은 그 서버의 멤버만 가능해야 하고, DM 은 멤버 신원이 없기 때문. 따라서 'DM ask 라우팅'
        // 테스트는 제거. DM provider-join(기여) 자체는 글로벌 풀 인프라로 유지된다(위 테스트).
    }
