package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.participation.domain.model.state.ChannelCultureState
import com.discordassistant.central.participation.domain.model.state.ChannelScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P06-T022 관리자용 상태 설명 DTO 테스트.
 *
 * acceptance: 숫자의 의미와 표본 부족 상태가 UI 에 드러난다 — 각 지표가 의미 설명(meaning)과 표본 충분 여부를
 * 함께 담고, 표본 부족 시 안내 문구가 채워진다.
 */
class SocialStateDescriptionDtoTest {
    private val scope = ChannelScope("g1", "c1")

    @Test
    fun `표본이 충분하면 의미 설명과 함께 sufficient 가 true 다`() {
        val culture =
            ChannelCultureState(
                scope = scope,
                humanBurstCount = 12,
                humanBurstsPerMinute = 3.5,
                averageBurstSize = 2.0,
                reactionRatio = 0.4,
                mentionResponseRatio = 0.6,
            )
        val dto = SocialStateDescriptionDto.of("g1", "c1", culture, nexaBurstCount = 4)

        assertTrue(dto.hasSufficientSample)
        assertNull(dto.sampleNotice)
        assertEquals(4, dto.cultureMetrics.size)
        dto.cultureMetrics.forEach {
            assertTrue(it.meaning.isNotBlank(), "각 지표는 의미 설명을 가진다")
            assertTrue(it.hasSufficientSample)
        }
        assertTrue(dto.nexaSaturation.value in 0.0..1.0)
        assertTrue(dto.nexaSaturation.meaning.isNotBlank())
    }

    @Test
    fun `표본이 부족하면 표본 부족 상태가 드러난다 (acceptance)`() {
        val empty = ChannelCultureState.empty(scope)
        val dto = SocialStateDescriptionDto.of("g1", "c1", empty, nexaBurstCount = 0)

        assertFalse(dto.hasSufficientSample, "사람 burst 가 없으면 표본 부족")
        assertNotNull(dto.sampleNotice, "표본 부족 안내 문구가 드러난다")
        dto.cultureMetrics.forEach { assertFalse(it.hasSufficientSample) }
        assertFalse(dto.nexaSaturation.hasSufficientSample, "NEXA·사람 burst 모두 0 이면 포화도도 표본 부족")
    }

    @Test
    fun `NEXA 만 발화한 채널은 포화도 표본은 있으나 문화 표본은 부족하다`() {
        val empty = ChannelCultureState.empty(scope)
        val dto = SocialStateDescriptionDto.of("g1", "c1", empty, nexaBurstCount = 3)
        assertFalse(dto.hasSufficientSample, "사람 burst 0 → 문화 표본 부족")
        assertTrue(dto.nexaSaturation.hasSufficientSample, "NEXA burst 가 있으면 포화도 표본은 있다")
    }

    @Test
    fun `원문이나 금지 추론 필드 없이 가명·관찰 지표만 담는다`() {
        val culture = ChannelCultureState(scope = scope, humanBurstCount = 1, humanBurstsPerMinute = 1.0)
        val dto = SocialStateDescriptionDto.of("g1", "c1", culture, nexaBurstCount = 1)
        assertEquals("g1", dto.guildPseudonym)
        assertEquals("c1", dto.channelPseudonym)
        // 금지 추론 라벨(기분·성격 등)이 없음을 의미 설명이 관찰 사실로 환원됨으로 증명(자유 텍스트 추론 부재).
        dto.cultureMetrics.forEach { assertTrue(it.meaning.contains("관찰") || it.meaning.contains("비율") || it.meaning.contains("수")) }
    }
}
