package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.CommandMetrics
import com.discordassistant.central.platform.discord.GatewayIntentPolicy
import com.discordassistant.central.platform.discord.Pagination
import com.discordassistant.central.platform.discord.Replies
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import net.dv8tion.jda.api.requests.GatewayIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 차수 13 Discord UX 유틸 — 표준응답(#184)·페이지네이션(#187)·명령통계(#190). */
class DiscordUxTest {
    @Test
    fun `Replies 표준 아이콘`() {
        assertTrue(Replies.reject("x").content.startsWith("⛔"))
        assertTrue(Replies.warn("x").content.startsWith("⚠️"))
        assertTrue(Replies.cooldown("x").content.startsWith("⏳"))
        assertTrue(Replies.ok("x").content.startsWith("✅"))
        assertTrue(Replies.adminDenied().content.contains("관리자만"))
    }

    @Test
    fun `짧은 텍스트는 한 페이지`() {
        assertEquals(listOf("hello"), Pagination.paginate("hello"))
    }

    @Test
    fun `줄 경계로 분할하고 한도를 넘지 않는다`() {
        val text = (1..500).joinToString("\n") { "line-$it" }
        val pages = Pagination.paginate(text, limit = 100)
        assertTrue(pages.size > 1)
        pages.forEach { assertTrue(it.length <= 100, "페이지 길이 ${it.length}") }
        // 재조립 시 원문과 동일(분할이 데이터를 잃지 않음)
        assertEquals(text, pages.joinToString("\n"))
    }

    @Test
    fun `한 줄이 한도를 넘으면 강제 분할(유실 없음)`() {
        val long = "a".repeat(250)
        val pages = Pagination.paginate(long, limit = 100)
        assertEquals(3, pages.size)
        assertEquals(long, pages.joinToString(""))
    }

    @Test
    fun `GatewayIntentPolicy — Message Content Intent 설정값으로 제어`() {
        val withMention = GatewayIntentPolicy.intents(messageContentIntentEnabled = true)
        assertTrue(withMention.contains(GatewayIntent.MESSAGE_CONTENT))
        assertTrue(withMention.contains(GatewayIntent.GUILD_MESSAGES))

        val slashOnly = GatewayIntentPolicy.intents(messageContentIntentEnabled = false)
        assertFalse(slashOnly.contains(GatewayIntent.MESSAGE_CONTENT))
        assertTrue(slashOnly.contains(GatewayIntent.GUILD_MESSAGES))
    }

    @Test
    fun `명령 통계 카운트`() {
        val m = CommandMetrics(SimpleMeterRegistry())
        m.record("ask")
        m.record("ask")
        m.record("help")
        assertEquals(2, m.count("ask"))
        assertEquals(1, m.count("help"))
        assertEquals(0, m.count("none"))
        assertEquals(mapOf("ask" to 2L, "help" to 1L), m.snapshot())
    }
}
