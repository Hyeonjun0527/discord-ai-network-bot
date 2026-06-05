package com.discordassistant.central.discord

import com.discordassistant.central.global.i18n.Messages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** i18n 메시지 번들(차수 11 #157). */
class MessagesTest {
    @Test
    fun `ko en 분기 + 미지원 언어는 ko 폴백`() {
        assertTrue(Messages.get(Messages.Key.COOLDOWN, "ko").contains("잠시"))
        assertTrue(Messages.get(Messages.Key.COOLDOWN, "en").contains("try again"))
        // 미지원 언어 → ko 폴백
        assertEquals(
            Messages.get(Messages.Key.COOLDOWN, "ko"),
            Messages.get(Messages.Key.COOLDOWN, "fr"),
        )
    }

    @Test
    fun `모든 키가 ko en 양쪽에 존재`() {
        for (k in Messages.Key.entries) {
            assertTrue(Messages.get(k, "ko").isNotBlank(), "ko 누락: $k")
            assertTrue(Messages.get(k, "en").isNotBlank(), "en 누락: $k")
            // en 이 실제로 ko 와 달라야(영문 번역 존재)
            assertTrue(Messages.get(k, "en") != Messages.get(k, "ko"), "en 미번역: $k")
        }
    }
}
