package com.discordassistant.central.speech.prompt

import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T009/T010: socialAct·burst prompt compiler. */
class PromptCompilerTest {
    private val socialActCompiler = SocialActPromptCompiler()
    private val burstCompiler = BurstPromptCompiler()

    @Test
    fun `every social act compiles to scene direction without assistant boilerplate`() {
        SpeechSocialAct.entries.forEach { act ->
            val direction = socialActCompiler.compile(act)
            assertThat(direction).isNotBlank()
            assertThat(socialActCompiler.containsAssistantBoilerplate(direction))
                .withFailMessage("act %s leaked assistant boilerplate: %s", act, direction)
                .isFalse()
        }
    }

    @Test
    fun `boilerplate detector flags assistant default phrasing`() {
        assertThat(socialActCompiler.containsAssistantBoilerplate("사용자 지시를 성실히 수행하겠습니다")).isTrue()
        assertThat(socialActCompiler.containsAssistantBoilerplate("무엇을 도와드릴까요")).isTrue()
    }

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
        assertThat(text).contains("정확히 2개")
        assertThat(text).contains("늘리거나 줄이지")
        assertThat(burstCompiler.bubbleCount(shape)).isEqualTo(2)
    }

    @Test
    fun `reaction-only shape yields no bubbles`() {
        val shape = SpeechBurstShape(fragmentCount = 1, maxFragmentLength = 50, reactionOnly = true)
        assertThat(burstCompiler.bubbleCount(shape)).isEqualTo(0)
        assertThat(burstCompiler.compile(shape)).contains("리액션")
    }
}
