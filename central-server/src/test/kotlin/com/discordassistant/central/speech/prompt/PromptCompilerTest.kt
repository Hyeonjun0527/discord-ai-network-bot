package com.discordassistant.central.speech.prompt

import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T010: burst prompt compiler. */
class PromptCompilerTest {
    private val burstCompiler = BurstPromptCompiler()

    @Test
    fun `burst compiler enforces exactly one bubble when policy picked one`() {
        val shape = SpeechBurstShape(fragmentCount = 1, maxFragmentLength = 200, reactionOnly = false)
        val text = burstCompiler.compile(shape)
        assertThat(text).contains("정확히 1개")
        assertThat(burstCompiler.bubbleCount(shape)).isEqualTo(1)
        // 4개를 강제하는 문구가 없어야 한다.
        assertThat(text).doesNotContain("4개")
    }

    @Test
    fun `burst compiler enforces exact n bubbles (model cannot force more)`() {
        val shape = SpeechBurstShape(fragmentCount = 2, maxFragmentLength = 120, reactionOnly = false)
        val text = burstCompiler.compile(shape)
        assertThat(text).contains("정확히 2개", "각 메시지는 120자 이내")
        assertThat(burstCompiler.bubbleCount(shape)).isEqualTo(2)
    }

    @Test
    fun `reaction-only shape yields no bubbles`() {
        val shape = SpeechBurstShape(fragmentCount = 1, maxFragmentLength = 50, reactionOnly = true)
        assertThat(burstCompiler.bubbleCount(shape)).isEqualTo(0)
        assertThat(burstCompiler.compile(shape)).isEqualTo("짧은 반응 하나만 만든다.")
    }
}
