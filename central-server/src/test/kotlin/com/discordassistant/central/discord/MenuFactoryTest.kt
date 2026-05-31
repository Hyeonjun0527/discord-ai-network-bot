package com.discordassistant.central.discord

import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 온보딩/설정 패널 컴포넌트 빌더(차수 13 UX) 검증. */
class MenuFactoryTest {
    @Test
    fun `메인 버튼 — 일반은 4개, 관리자는 설정 추가 5개`() {
        val user = MenuFactory.mainButtons(isAdmin = false)
        assertEquals(4, user.size)
        assertFalse(user.any { it.id == MenuFactory.SETTINGS })
        val admin = MenuFactory.mainButtons(isAdmin = true)
        assertEquals(5, admin.size)
        assertTrue(admin.any { it.id == MenuFactory.SETTINGS })
        // 핵심 버튼 ID 존재
        assertTrue(user.any { it.id == MenuFactory.ASK })
        assertTrue(user.any { it.id == MenuFactory.PROVIDER })
        assertTrue(user.any { it.label == "${MenuSymbols.PROVIDER} 함께 도와주기" })
        assertTrue(user.any { it.label == "${MenuSymbols.STATUS} 내 상태" })
        assertFalse(user.any { it.label == "AI 일꾼 되기" })
        assertTrue(admin.any { it.label == "${MenuSymbols.SETTINGS} 설정" })
        assertEquals(ButtonStyle.PRIMARY, user.first { it.id == MenuFactory.ASK }.style)
        assertEquals(ButtonStyle.SECONDARY, admin.first { it.id == MenuFactory.SETTINGS }.style)
    }

    @Test
    fun `OS 선택 버튼은 플랫폼 아이콘을 유지한다`() {
        val buttons = MenuFactory.osButtons()
        assertEquals("macOS", buttons[0].label)
        assertEquals("🍎", buttons[0].emoji?.name)
        assertEquals("Windows", buttons[1].label)
        assertEquals("🪟", buttons[1].emoji?.name)
        assertEquals("Linux", buttons[2].label)
        assertEquals("🐧", buttons[2].emoji?.name)
    }

    @Test
    fun `언어 드롭다운 — ko en 옵션`() {
        val sel = MenuFactory.languageSelect("ko")
        assertEquals(MenuFactory.LANG, sel.id)
        assertEquals(setOf("ko", "en"), sel.options.map { it.value }.toSet())
    }

    @Test
    fun `모델 드롭다운 — 자동 + 풀 모델, 25개 한도`() {
        val sel = MenuFactory.modelSelect(listOf("llama3", "mistral", "llama3"))
        assertEquals("__auto__", sel.options.first().value) // 자동이 맨 앞
        assertTrue(sel.options.any { it.value == "llama3" })
        assertTrue(sel.options.size <= 25)
        // 중복 제거
        assertEquals(
            sel.options.size,
            sel.options
                .map { it.value }
                .toSet()
                .size,
        )
    }

    @Test
    fun `설정 안내 텍스트 — 상태별 친절 설명`() {
        // 프로바이더 0 → 자동선택만 설명, 채널 0 → 모든 채널 허용 상태
        val empty = MenuFactory.settingsText(autoApprove = false, poolModels = emptyList(), allowedChannelCount = 0)
        assertTrue(empty.contains("자동 선택"))
        assertTrue(empty.contains("프로바이더가 없어") || empty.contains("연결된 프로바이더가 없"))
        assertTrue(empty.contains("모든 채널 허용"))
        assertTrue(empty.contains("꺼짐"))
        // 모델 있고 채널 제한 + 자동승인 켜짐
        val full = MenuFactory.settingsText(autoApprove = true, poolModels = listOf("llama3"), allowedChannelCount = 2)
        assertTrue(full.contains("1종") || full.contains("모델"))
        assertTrue(full.contains("2 개") || full.contains("2개"))
        assertTrue(full.contains("켜짐"))
    }

    @Test
    fun `슬림 도움말 — 핵심만, 관리자만 설정 언급`() {
        val user = MenuFactory.slimHelp(isAdmin = false)
        assertTrue(user.contains("/ask"))
        assertTrue(user.contains("함께 도와주기"))
        assertTrue(user.contains("내 상태"))
        assertTrue(user.contains("/menu"))
        assertFalse(user.contains(MenuSymbols.SETTINGS))
        assertTrue(MenuFactory.slimHelp(isAdmin = true).contains(MenuSymbols.SETTINGS))
    }
}
