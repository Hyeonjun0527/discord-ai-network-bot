package com.discordassistant.central.discord

import com.discordassistant.central.channelai.application.AutoRespondChannelRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 자동응답 판정(순수): 비-`.` & 비공백 → 응답 / `.` 시작·빈 내용 → 스킵. */
class AutoRespondJudgmentTest {
    @Test
    fun `일반 내용은 응답한다`() {
        assertTrue(AutoRespondChannelRegistry.shouldRespond("오늘 회의 요약해줘"))
        assertTrue(AutoRespondChannelRegistry.shouldRespond("  공백 앞뒤 있어도 응답  "))
        assertTrue(AutoRespondChannelRegistry.shouldRespond("중간에 . 점 있어도 응답"))
    }

    @Test
    fun `점으로 시작하면 스킵한다`() {
        assertFalse(AutoRespondChannelRegistry.shouldRespond(".조용히 둘 메시지"))
        assertFalse(AutoRespondChannelRegistry.shouldRespond("   .앞 공백 뒤 점도 스킵"))
        assertFalse(AutoRespondChannelRegistry.shouldRespond("."))
    }

    @Test
    fun `빈 내용은 스킵한다`() {
        assertFalse(AutoRespondChannelRegistry.shouldRespond(""))
        assertFalse(AutoRespondChannelRegistry.shouldRespond("   "))
    }
}
