package com.discordassistant.central.socialmemory.domain.service.emotion

import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionState
import com.discordassistant.central.socialmemory.domain.model.emotion.ToneIntensity
import com.discordassistant.central.socialmemory.domain.model.emotion.ToneValence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Emotion Renderer 이식 검증 — core `tests/test_emotion_renderer.py`(게이트 G5)가 보증한 성질을 Kotlin 으로 재현.
 * 임계값·히스테리시스·정체성/길이 불변·하위호환을 결정론적으로 검증.
 */
class EmotionRendererTest {
    private fun mood(value: Double) = EmotionState(contextScope = "guild:1", mood = value)

    @Test
    fun `emotion 이 null 이면 미주입(평소 니아)`() {
        assertFalse(EmotionRenderer.renderToneHint(null).active)
    }

    @Test
    fun `임계 아래면 미주입 — 하위호환(회귀 0)`() {
        assertFalse(EmotionRenderer.renderToneHint(mood(0.020)).active)
        assertFalse(EmotionRenderer.renderToneHint(mood(-0.010)).active)
    }

    @Test
    fun `진입선 이상이면 SLIGHT 켜짐`() {
        val hint = EmotionRenderer.renderToneHint(mood(0.030))
        assertTrue(hint.active)
        assertEquals(ToneIntensity.SLIGHT, hint.intensity)
        assertEquals(ToneValence.WARM, hint.valence)
    }

    @Test
    fun `절제선(0_060) 이상이면 TEMPERED — 그래도 약간 상한`() {
        val hint = EmotionRenderer.renderToneHint(mood(-0.065))
        assertTrue(hint.active)
        assertEquals(ToneIntensity.TEMPERED, hint.intensity)
        assertEquals(ToneValence.COOL, hint.valence)
    }

    @Test
    fun `히스테리시스 — 켜진 뒤 해제선 사이값은 유지(채터링 방지, I12)`() {
        // 0.020 은 진입선(0.025) 아래지만 해제선(0.015) 위 — 켜져 있었으면 유지.
        assertTrue(EmotionRenderer.renderToneHint(mood(0.020), wasActive = true).active, "켜진 상태 유지")
        assertFalse(EmotionRenderer.renderToneHint(mood(0.020), wasActive = false).active, "꺼진 상태는 진입선까지 대기")
    }

    @Test
    fun `히스테리시스 — 해제선 미만이면 끈다`() {
        assertFalse(EmotionRenderer.renderToneHint(mood(0.010), wasActive = true).active)
    }

    @Test
    fun `mood 부호가 정확히 0 이면 미주입(방향 없음)`() {
        assertFalse(EmotionRenderer.renderToneHint(mood(0.0)).active)
    }

    @Test
    fun `부정 기분이어도 directive 가 공격 인격으로 안 바꾸고 길이 불변을 지시한다`() {
        val hint = EmotionRenderer.renderToneHint(mood(-0.030))
        assertTrue(hint.active && hint.valence == ToneValence.COOL)
        assertTrue(hint.directive.contains("공격적으로 변하지"), "공격 금지 지시 포함")
        assertTrue(hint.directive.contains("답변 길이"), "길이 불변 지시 포함")
        assertTrue(hint.directive.contains("과장하지"), "과장 금지 지시 포함")
    }

    @Test
    fun `결정적 — 같은 입력은 같은 톤 힌트(랜덤 0)`() {
        val a = EmotionRenderer.renderToneHint(mood(0.040), relationshipLabel = "친한 사이")
        val b = EmotionRenderer.renderToneHint(mood(0.040), relationshipLabel = "친한 사이")
        assertEquals(a, b)
    }

    @Test
    fun `관계 라벨은 맥락 한 줄로만 — 강도는 mood 가 정한다`() {
        val withLabel = EmotionRenderer.renderToneHint(mood(0.030), relationshipLabel = "처음 보는 사이")
        val withoutLabel = EmotionRenderer.renderToneHint(mood(0.030))
        // 강도·valence 는 동일(관계가 안 뒤집음), directive 만 맥락 한 줄 차이.
        assertEquals(withoutLabel.intensity, withLabel.intensity)
        assertEquals(withoutLabel.valence, withLabel.valence)
        assertTrue(withLabel.directive.contains("처음 보는 사이"))
    }
}
