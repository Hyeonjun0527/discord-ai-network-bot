package com.discordassistant.central.discord

import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 슬래시 명령 카탈로그 가드(god class 분해 2/N). all() 빌더를 실제로 실행해
 * 명령 정의가 깨지지 않았는지(중복 이름 없음, 최소 개수 충족) 검증한다.
 */
class SlashCommandCatalogTest {
    @Test
    fun `카탈로그는 중복 없는 명령들을 빌드한다`() {
        val cmds = SlashCommandCatalog.all()
        assertTrue(cmds.size >= 30, "명령 개수가 너무 적음(빌더 손상 의심): ${cmds.size}")

        val names = cmds.map { it.name }
        assertEquals(names.size, names.toSet().size, "명령 이름 중복: ${names.groupingBy { it }.eachCount().filter { it.value > 1 }}")

        // 핵심 명령은 슬래시 명령으로 존재해야 한다.
        val slashNames = cmds.filterIsInstance<SlashCommandData>().map { it.name }.toSet()
        assertTrue("ask" in slashNames, "ask 슬래시 명령이 카탈로그에 없음")
    }
}
