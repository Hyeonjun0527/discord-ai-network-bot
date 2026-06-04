package com.discordassistant.central.discord

import net.dv8tion.jda.api.interactions.DiscordLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 문구 SSOT(`i18n/messages.json`)와 i18n 조회의 드리프트 가드. 완전 지원 언어(ko/en/ja)가 모든 키에
 * 빠짐없이 있어야 새 언어를 "제대로" 지원한다고 볼 수 있다.
 */
class I18nMessagesDriftTest {
    @Test
    fun `모든 문구 키가 ko en ja 를 모두 가지며 ko 와 다르게 번역됨`() {
        assertTrue(I18n.keys.isNotEmpty(), "messages.json 로드 실패")
        for (key in I18n.keys) {
            for (lang in listOf("ko", "en", "ja")) {
                assertTrue(!I18n.rawOrNull(key, lang).isNullOrBlank(), "$lang 누락: $key")
            }
            // en/ja 가 실제로 ko 와 달라야(미번역 방지)
            assertTrue(I18n.rawOrNull(key, "en") != I18n.rawOrNull(key, "ko"), "en 미번역: $key")
            assertTrue(I18n.rawOrNull(key, "ja") != I18n.rawOrNull(key, "ko"), "ja 미번역: $key")
        }
    }

    @Test
    fun `미지원 로케일·키는 폴백`() {
        val anyKey = I18n.keys.first()
        // 미지원 언어(fr) → 기본 언어(ko)로 정규화되어 ko 텍스트
        assertEquals(I18n.get(anyKey, "ko"), I18n.get(anyKey, "fr"))
        // 없는 키 → 키 자체 반환
        assertEquals("no.such.key", I18n.get("no.such.key", "ko"))
    }

    @Test
    fun `자리표시자 치환`() {
        // 키가 없어도 폴백 텍스트(키)엔 자리표시자가 없으니, 동작만 직접 검증.
        // 임시로 알려진 키에 인자를 줘도 자리표시자 없으면 원문 유지.
        val k = I18n.keys.first()
        assertEquals(I18n.get(k, "ko"), I18n.get(k, "ko", "unused"))
    }

    @Test
    fun `Discord 로케일 해석 — ko ja en 만 매핑, 그 외 null`() {
        assertEquals("ko", I18n.resolveOrNull(DiscordLocale.KOREAN))
        assertEquals("ja", I18n.resolveOrNull(DiscordLocale.JAPANESE))
        assertEquals("en", I18n.resolveOrNull(DiscordLocale.ENGLISH_US))
        assertNull(I18n.resolveOrNull(DiscordLocale.RUSSIAN)) // 미완전지원 → 상위(길드 기본) 폴백
        assertNull(I18n.resolveOrNull(null))
    }
}
