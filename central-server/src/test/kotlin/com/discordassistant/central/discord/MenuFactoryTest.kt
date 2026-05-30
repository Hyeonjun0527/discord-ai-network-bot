package com.discordassistant.central.discord

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
    fun `슬림 도움말 — 핵심만, 관리자만 설정 언급`() {
        val user = MenuFactory.slimHelp(isAdmin = false)
        assertTrue(user.contains("/ask"))
        assertTrue(user.contains("/menu"))
        assertFalse(user.contains("⚙️"))
        assertTrue(MenuFactory.slimHelp(isAdmin = true).contains("⚙️"))
    }
}
