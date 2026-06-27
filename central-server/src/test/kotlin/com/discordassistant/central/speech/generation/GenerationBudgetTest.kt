package com.discordassistant.central.speech.generation

import com.discordassistant.central.speech.application.generation.GenerationBudget
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T015: token·cost budget — 후보 과생성 차단. */
class GenerationBudgetTest {
    @Test
    fun `clampCandidateCount never exceeds budget or contract ceiling`() {
        val budget = GenerationBudget(maxCandidates = 4, maxOutputTokens = 256, maxContextTokens = 512)
        assertThat(budget.clampCandidateCount(10)).isEqualTo(1) // 운영 계약 cap
        assertThat(budget.clampCandidateCount(3)).isEqualTo(1)
        // 계약 상한(MAX_CANDIDATES)을 못 넘는다.
        val wide = GenerationBudget(maxCandidates = 99, maxOutputTokens = 256, maxContextTokens = 512)
        assertThat(wide.clampCandidateCount(99)).isEqualTo(SpeechGenerationRequest.MAX_CANDIDATES)
    }

    @Test
    fun `clampCandidateCount enforces minimum`() {
        val budget = GenerationBudget.DEFAULT
        assertThat(budget.clampCandidateCount(0)).isEqualTo(SpeechGenerationRequest.MIN_CANDIDATES)
    }

    @Test
    fun `clampOutputTokens respects ceiling`() {
        val budget = GenerationBudget(maxCandidates = 1, maxOutputTokens = 100, maxContextTokens = 200)
        assertThat(budget.clampOutputTokens(500)).isEqualTo(100)
        assertThat(budget.clampOutputTokens(50)).isEqualTo(50)
    }
}
