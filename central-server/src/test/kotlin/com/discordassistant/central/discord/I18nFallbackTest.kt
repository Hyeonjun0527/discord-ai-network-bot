package com.discordassistant.central.discord

import com.discordassistant.central.global.i18n.I18n
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * i18n-locale-fallback: 키에 요청 언어가 없을 때 **ko(기본)를 en 보다 먼저** 노출하는지 검증.
 * (한국어 중심 제품 — 번역 누락 시 영어보다 기본어를 보여 준다.)
 */
class I18nFallbackTest {
    @Test
    fun `exact language is preferred`() {
        val byLang = mapOf("ko" to "가", "en" to "A", "ja" to "あ")
        assertEquals("あ", I18n.pickText(byLang, "ja"))
        assertEquals("A", I18n.pickText(byLang, "en"))
        assertEquals("가", I18n.pickText(byLang, "ko"))
    }

    @Test
    fun `missing requested language falls back to ko default before en`() {
        val byLang = mapOf("ko" to "가", "en" to "A") // ja 없음
        assertEquals("가", I18n.pickText(byLang, "ja")) // en("A") 이 아니라 ko("가")
    }

    @Test
    fun `falls back to en only when ko default is also missing`() {
        val byLang = mapOf("en" to "A") // ko 없음
        assertEquals("A", I18n.pickText(byLang, "ja"))
    }

    @Test
    fun `returns null when none of requested ko en exist`() {
        val byLang = mapOf("fr" to "Bonjour")
        assertNull(I18n.pickText(byLang, "ja"))
    }
}
