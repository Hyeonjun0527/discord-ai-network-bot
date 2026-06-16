package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.DiscordBot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 온보딩 배너 채널 선택(순수 로직) — 시스템 채널이 꺼져 있거나 봇이 못 쓰는 서버에서도 안내 0 이 되지 않게,
 * 쓰기 가능한 첫 텍스트 채널로 폴백하는지 검증한다(JDA 없이 제네릭으로).
 */
class OnboardingChannelSelectionTest {
    @Test
    fun `쓰기 가능한 시스템 채널이 있으면 그 채널을 쓴다`() {
        assertEquals("system", DiscordBot.Listener.selectOnboardingChannel("system", listOf("a", "b")))
    }

    @Test
    fun `시스템 채널이 없으면 쓰기 가능한 첫 텍스트 채널로 폴백한다`() {
        assertEquals("a", DiscordBot.Listener.selectOnboardingChannel<String>(null, listOf("a", "b")))
    }

    @Test
    fun `시스템 채널도 쓰기 가능한 채널도 없으면 null(graceful 무시)`() {
        assertNull(DiscordBot.Listener.selectOnboardingChannel<String>(null, emptyList()))
    }
}
