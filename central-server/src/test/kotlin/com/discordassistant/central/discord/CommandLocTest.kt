package com.discordassistant.central.discord

import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 슬래시 명령 로컬라이제이션(클라이언트 언어별 이름/설명). */
class CommandLocTest {
    @Test
    fun `ask — 한국어 이름·러시아어 이름 + 영어·러시아어 설명`() {
        val cmd = Commands.slash("ask", "커뮤니티 로컬 AI 에게 질문합니다")
        CommandLoc.localize(cmd)
        assertEquals("질문", cmd.nameLocalizations.toMap()[DiscordLocale.KOREAN])
        assertEquals("спросить", cmd.nameLocalizations.toMap()[DiscordLocale.RUSSIAN])
        assertEquals("Ask the community local AI", cmd.descriptionLocalizations.toMap()[DiscordLocale.ENGLISH_US])
        assertEquals("커뮤니티 로컬 AI 에게 질문합니다", cmd.descriptionLocalizations.toMap()[DiscordLocale.KOREAN])
        // 기본 이름은 영어(ascii) 유지 — dispatch 안정
        assertEquals("ask", cmd.name)
    }

    @Test
    fun `러시아어 이름 없는 명령은 ko 이름·en·ru 설명만`() {
        val cmd = Commands.slash("fairness", "공정성 리포트를 봅니다(관리자)")
        CommandLoc.localize(cmd)
        assertEquals("공정성", cmd.nameLocalizations.toMap()[DiscordLocale.KOREAN])
        assertNull(cmd.nameLocalizations.toMap()[DiscordLocale.RUSSIAN]) // ru 이름 미지정
        assertEquals("Fairness report (admin)", cmd.descriptionLocalizations.toMap()[DiscordLocale.ENGLISH_US])
    }

    @Test
    fun `표에 없는 명령은 변경 없음`() {
        val cmd = Commands.slash("unknown-x", "설명")
        CommandLoc.localize(cmd)
        assertEquals(0, cmd.nameLocalizations.toMap().size)
    }
}
