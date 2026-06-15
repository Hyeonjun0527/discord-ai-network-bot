package com.discordassistant.central.discord

import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
import com.discordassistant.central.platform.discord.command.SettingsCommandHandler
import com.discordassistant.central.platform.discord.command.SharedCommandGuards
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * 공개 `/설정` 핸들러 순수 로직 단위테스트(JDA 없이).
 *
 * 검증:
 *  - 길드/채널이 있으면 "설정 홈" + "이 채널 설정" 두 링크 버튼이 publicBaseUrl 로 조립된다(채널 쿼리 인코딩 포함).
 *  - configured base 가 우선이고, 비면 relay public-url 의 wss→https 변환 + 끝 `/agent` 제거로 도출한다.
 *  - DM(길드 sentinel)에서는 "설정 홈" 버튼만 노출한다.
 *  - 베이스 URL 이 비어 있으면 빈 link 버튼을 만들 수 없으므로 안내 폴백(Unavailable)으로 떨어진다.
 *
 * lang 은 ctx.userLang("ko") 우선이라 PolicyService 를 타지 않는다 — 목은 호출되지 않는다(순수 검증).
 */
class SettingsCommandHandlerTest {
    private val guards = SharedCommandGuards(mock(PolicyService::class.java))

    private fun ctx(
        guildId: Long,
        channelId: Long = 555L,
    ) = CommandContext(guildId = guildId, channelId = channelId, userId = 7L, roleIds = emptySet(), isAdmin = false, userLang = "ko")

    @Test
    fun `configured base — 길드 채널이면 두 링크 버튼을 조립한다`() {
        val handler = SettingsCommandHandler(guards, configuredBase = "https://discord-ai.yeon.world/", relayPublicUrl = "")

        val links = handler.settings(ctx(guildId = 1234L, channelId = 9876L))

        assertTrue(links is SettingsCommandHandler.SettingsLinks.WithLinks)
        val withLinks = links as SettingsCommandHandler.SettingsLinks.WithLinks
        assertEquals(2, withLinks.buttons.size)
        assertEquals("https://discord-ai.yeon.world/admin/dashboard/", withLinks.buttons[0].url)
        assertEquals("https://discord-ai.yeon.world/admin/dashboard/?guild=1234&channel=9876", withLinks.buttons[1].url)
    }

    @Test
    fun `relay public-url 폴백 — wss를 https로 바꾸고 끝 agent 를 제거한다`() {
        val handler = SettingsCommandHandler(guards, configuredBase = "", relayPublicUrl = "wss://discord-ai.yeon.world/agent")

        val links = handler.settings(ctx(guildId = 42L, channelId = 100L)) as SettingsCommandHandler.SettingsLinks.WithLinks

        assertEquals("https://discord-ai.yeon.world/admin/dashboard/", links.buttons[0].url)
        assertEquals("https://discord-ai.yeon.world/admin/dashboard/?guild=42&channel=100", links.buttons[1].url)
    }

    @Test
    fun `DM 에서는 설정 홈 버튼만 노출한다`() {
        val handler = SettingsCommandHandler(guards, configuredBase = "https://discord-ai.yeon.world", relayPublicUrl = "")

        val links = handler.settings(ctx(guildId = CommandService.DM_SCOPE)) as SettingsCommandHandler.SettingsLinks.WithLinks

        assertEquals(1, links.buttons.size)
        assertEquals("https://discord-ai.yeon.world/admin/dashboard/", links.buttons[0].url)
    }

    @Test
    fun `베이스 URL 이 비면 빈 link 버튼 대신 안내 폴백을 반환한다`() {
        val handler = SettingsCommandHandler(guards, configuredBase = "", relayPublicUrl = "")

        val links = handler.settings(ctx(guildId = 1234L))

        assertTrue(links is SettingsCommandHandler.SettingsLinks.Unavailable)
    }
}
