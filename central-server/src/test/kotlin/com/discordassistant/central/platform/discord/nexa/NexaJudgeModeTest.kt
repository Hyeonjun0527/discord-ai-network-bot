package com.discordassistant.central.platform.discord.nexa

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NexaJudgeModeTest {
    @Test
    fun `parses supported judge modes`() {
        assertThat(NexaJudgeMode.parse("off")).isEqualTo(NexaJudgeMode.OFF)
        assertThat(NexaJudgeMode.parse("shadow")).isEqualTo(NexaJudgeMode.SHADOW)
        assertThat(NexaJudgeMode.parse("final")).isEqualTo(NexaJudgeMode.FINAL)
    }

    @Test
    fun `blank judge mode defaults to off`() {
        assertThat(NexaJudgeMode.parse(null)).isEqualTo(NexaJudgeMode.OFF)
        assertThat(NexaJudgeMode.parse("  ")).isEqualTo(NexaJudgeMode.OFF)
    }

    @Test
    fun `invalid judge mode fails fast`() {
        assertThatThrownBy { NexaJudgeMode.parse("vote") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("central.nexa.judge.mode")
            .hasMessageContaining("off, shadow, final")
    }
}
