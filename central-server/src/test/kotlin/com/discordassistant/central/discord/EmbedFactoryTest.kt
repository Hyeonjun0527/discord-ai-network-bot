package com.discordassistant.central.discord

import com.discordassistant.central.domain.ProviderState
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.DiscordLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

/** Embed 고도화(차수 13 #156) — 상태 색상/필드. */
class EmbedFactoryTest {
    @Test
    fun `상태별 색상 badge`() {
        assertEquals(Color(0x57F287), EmbedFactory.stateColor(ProviderState.ONLINE_IDLE))
        assertEquals(Color(0xFEE75C), EmbedFactory.stateColor(ProviderState.PAUSED))
        assertEquals(Color(0xED4245), EmbedFactory.stateColor(ProviderState.UNHEALTHY))
        assertEquals(Color(0x5865F2), EmbedFactory.stateColor(ProviderState.APPROVED))
    }

    @Test
    fun `프로바이더 상태 embed 필드`() {
        val e = EmbedFactory.providerStatus(providerId = 7, state = ProviderState.ONLINE_IDLE, inFlight = 2, failures = 1)
        assertEquals("프로바이더 상태", e.title)
        assertEquals(Color(0x57F287), e.color)
        assertEquals("2", e.fields.first { it.name == "처리중" }.value)
        assertNotNull(e.footer)
    }

    @Test
    fun `풀 요약 embed — 0명이면 적색`() {
        assertEquals(Color(0xED4245), EmbedFactory.poolSummary(active = 0, models = 0, inFlight = 0).color)
        assertEquals(Color(0x57F287), EmbedFactory.poolSummary(active = 3, models = 2, inFlight = 1).color)
    }

    @Test
    fun `시작 메뉴 embed는 브랜드 이미지와 메뉴 항목을 포함한다`() {
        val e = EmbedFactory.mainMenuEmbed(isAdmin = true)
        assertEquals("냥시스턴트 메뉴", e.title)
        assertTrue(e.description!!.contains("내 컴퓨터의 AI"))
        assertEquals(EmbedFactory.MENU_HERO_IMAGE_URL, e.image!!.url)
        assertTrue(e.fields.any { it.name == "${MenuSymbols.ASK} 질문하기" })
        assertTrue(e.fields.any { it.name == "${MenuSymbols.SETTINGS} 설정" })
        assertNotNull(e.footer)
    }

    @Test
    fun `도움말 패널 embed — 관리자만 관리자 섹션`() {
        val user = EmbedFactory.helpEmbed(isAdmin = false)
        assertEquals("${MenuSymbols.ASK} 커뮤니티 로컬 AI Provider Pool", user.title)
        assertTrue(user.fields.any { it.name?.contains("유저") == true })
        assertFalse(user.fields.any { it.name?.contains("관리자") == true })
        assertTrue(EmbedFactory.helpEmbed(isAdmin = true).fields.any { it.name?.contains("관리자") == true })
        assertNotNull(user.footer)
    }

    private fun helpText(e: MessageEmbed) = e.description.orEmpty() + "\n" + e.fields.joinToString("\n") { it.value.orEmpty() }

    @Test
    fun `도움말 명령 이름은 보는 사람 로케일로 표시(ko=한국어 이름, en=ascii)`() {
        val ko = helpText(EmbedFactory.helpEmbed(isAdmin = true, locale = DiscordLocale.KOREAN))
        assertTrue(ko.contains("/질문"), ko) // ask
        assertTrue(ko.contains("/메뉴")) // menu
        assertTrue(ko.contains("/설정")) // llm-settings
        assertTrue(ko.contains("/프로바이더승인")) // provider-approve(기존 /approve-provider 오타 교정 포함)
        assertFalse(ko.contains("/ask"))

        val en = helpText(EmbedFactory.helpEmbed(isAdmin = true, locale = DiscordLocale.ENGLISH_US))
        assertTrue(en.contains("/ask"))
        assertTrue(en.contains("/menu"))
        assertFalse(en.contains("/질문"))
    }

    @Test
    fun `설정 패널 embed — 현재 상태 필드`() {
        val e = EmbedFactory.settingsEmbed("ko", null, 0, 0, false)
        assertEquals("⚙️ 서버 설정", e.title)
        assertEquals("한국어", e.fields.first { it.name?.contains("언어") == true }.value)
        assertEquals("모든 채널 허용", e.fields.first { it.name?.contains("채널") == true }.value)
        assertTrue(
            e.fields
                .first { it.name?.contains("자동 승인") == true }
                .value!!
                .contains("꺼짐"),
        )
        val en = EmbedFactory.settingsEmbed("en", "llama3", 2, 1, true)
        assertEquals("English", en.fields.first { it.name?.contains("언어") == true }.value)
        assertEquals("llama3", en.fields.first { it.name?.contains("모델") == true }.value)
    }
}
