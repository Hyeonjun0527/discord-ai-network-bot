package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.CommandLoc
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 슬래시 명령 로컬라이제이션(클라이언트 언어별 이름/설명). */
class CommandLocTest {
    @Test
    fun `ask — 한국어 이름·러시아어 이름 + 영어·러시아어 설명`() {
        val cmd = Commands.slash("ask", "커뮤니티 로컬 AI 에게 질문합니다")
        CommandLoc.localize(cmd)
        assertEquals("질문", cmd.nameLocalizations.toMap()[DiscordLocale.KOREAN])
        assertEquals("спросить", cmd.nameLocalizations.toMap()[DiscordLocale.RUSSIAN])
        assertEquals(
            "Ask AI (free cloud by default, local when a community provider is connected)",
            cmd.descriptionLocalizations.toMap()[DiscordLocale.ENGLISH_US],
        )
        // 기본 이름은 영어(ascii) 유지 — dispatch 안정
        assertEquals("ask", cmd.name)
    }

    @Test
    fun `표에 없는 명령은 변경 없음`() {
        val cmd = Commands.slash("unknown-x", "설명")
        CommandLoc.localize(cmd)
        assertEquals(0, cmd.nameLocalizations.toMap().size)
    }
}
