package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.CommandLoc
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 슬래시 명령의 일본어 완전 지원 가드. CommandLoc 표의 모든 명령에 일본어 이름·설명이 있어야 하고,
 * `localize` 가 JAPANESE 로컬라이제이션을 실제로 설정해야 한다(일본 사용자에게 일본어 메뉴 보장).
 */
class CommandLocJaCoverageTest {
    @Test
    fun `모든 명령이 일본어 이름·설명을 가진다`() {
        assertTrue(CommandLoc.commands.size >= 50, "명령 표 로드 실패: ${CommandLoc.commands.size}")
        val missing = CommandLoc.commands.filterNot { CommandLoc.hasFullJa(it) }
        assertEquals(emptyList<String>(), missing, "일본어 이름/설명 누락")
    }

    @Test
    fun `localize 가 일본어 이름·설명 로컬라이제이션을 설정한다`() {
        val cmd = Commands.slash("ask", "커뮤니티 로컬 AI 에게 질문합니다")
        CommandLoc.localize(cmd)
        assertEquals("質問", cmd.nameLocalizations.toMap()[DiscordLocale.JAPANESE])
        assertEquals(
            "AIに質問します(既定は無料クラウド・ローカル接続時はローカル)",
            cmd.descriptionLocalizations.toMap()[DiscordLocale.JAPANESE],
        )
        // 일본어 클라이언트가 보는 명령 이름과 localName 일치(도움말 내 `/명령` 표기용)
        assertEquals("質問", CommandLoc.localName("ask", DiscordLocale.JAPANESE))
    }
}
