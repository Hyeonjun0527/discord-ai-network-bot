package com.discordassistant.central.speech.humanstyle

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleCopyGuard
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleMatch
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HumanSpeechStyleCopyGuardTest {
    @Test
    fun `사람 답변과 스무 글자 이상 연속으로 겹치는 후보는 버린다`() {
        val copied = "이문장은사람답변과길게연속으로겹치는표현입니다"
        val selection = HumanSpeechStyleSelection(listOf(HumanSpeechStyleMatch(example("human-style-000001", responseText = copied), 0.9)))
        val generated =
            SpeechGenerationResult(
                candidates =
                    listOf(
                        SpeechCandidate("copied", listOf("앞에 붙어도 $copied")),
                        SpeechCandidate("original", listOf("이건 새로 만든 반응이야")),
                    ),
            )

        val filtered = HumanSpeechStyleCopyGuard().removeCopiedCandidates(generated, selection)

        assertThat(filtered.candidates.map(SpeechCandidate::candidateId)).containsExactly("original")
    }
}
