package com.discordassistant.central.speech.humanstyle

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStylePromptRenderer
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleMatch
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HumanSpeechStylePromptRendererTest {
    @Test
    fun `실제 Speech payload에는 참고 예시를 넣되 debug trace에는 원문 예시를 넣지 않는다`() {
        val responseText = "private-style-response-marker-12345"
        val selection =
            HumanSpeechStyleSelection(
                listOf(
                    HumanSpeechStyleMatch(
                        example("human-style-000001", responseText = responseText),
                        0.9,
                    ),
                ),
            )

        val payload = HumanSpeechStylePromptRenderer().appendTo("현재 장면", selection)

        assertThat(payload.providerUserPrompt).contains("사람 말투 참고 예시", responseText, "반응 순서·길이·말풍선 리듬")
        assertThat(payload.traceUserPrompt).contains("private human-style examples omitted")
        assertThat(payload.traceUserPrompt).doesNotContain(responseText, "오늘 좀 답답하네")
    }
}
