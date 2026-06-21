package com.discordassistant.central.speech.generation

import com.discordassistant.central.speech.application.generation.ReasoningModeSelector
import com.discordassistant.central.speech.application.port.out.ReasoningMode
import com.discordassistant.central.speech.domain.model.MemoryRef
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T013: thinking mode 선택 — 정책이 central에서 결정(GLM에 위임 안 함). */
class ReasoningModeSelectorTest {
    private val selector = ReasoningModeSelector()

    @Test
    fun `short chit-chat uses non-thinking mode`() {
        val packet = SpeechGenerationFixtures.packet(socialAct = SpeechSocialAct.ACKNOWLEDGE)
        assertThat(selector.select(packet)).isEqualTo(ReasoningMode.NONE)
    }

    @Test
    fun `fact correction uses thinking mode`() {
        val packet = SpeechGenerationFixtures.packet(socialAct = SpeechSocialAct.CORRECT)
        assertThat(selector.select(packet)).isEqualTo(ReasoningMode.THINKING)
    }

    @Test
    fun `long single-bubble answer uses thinking mode`() {
        val packet =
            SpeechGenerationFixtures.packet(
                socialAct = SpeechSocialAct.SELF_DISCLOSE,
                burstShape = SpeechBurstShape(fragmentCount = 1, maxFragmentLength = 600, reactionOnly = false),
            )
        assertThat(selector.select(packet)).isEqualTo(ReasoningMode.THINKING)
    }

    @Test
    fun `memory-backed question uses thinking mode`() {
        val packet =
            SpeechGenerationFixtures.packet(
                socialAct = SpeechSocialAct.ASK,
                memoryRefs = listOf(MemoryRef("좋아하는 게임은 X", "stated", 0.8)),
            )
        assertThat(selector.select(packet)).isEqualTo(ReasoningMode.THINKING)
    }
}
