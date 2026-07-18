package com.discordassistant.central.actionruntime.application.content

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpeechBurstContentCodecTest {
    @Test
    fun `multi bubble content round trips without losing embedded newlines`() {
        val bubbles = listOf("첫 번째 이야기", "둘째 줄\n안쪽 줄바꿈", "마지막 ㅋㅋ")

        val decoded = SpeechBurstContentCodec.decode(SpeechBurstContentCodec.encode(bubbles))

        assertThat(decoded).containsExactlyElementsOf(bubbles)
    }

    @Test
    fun `legacy plain content remains one bubble`() {
        assertThat(SpeechBurstContentCodec.decode("예전 단일 메시지\n줄바꿈 포함"))
            .containsExactly("예전 단일 메시지\n줄바꿈 포함")
    }

    @Test
    fun `damaged encoded content fails closed`() {
        assertThat(SpeechBurstContentCodec.decode("NEXA_BURST_V1\n%%%"))
            .isEmpty()
    }
}
