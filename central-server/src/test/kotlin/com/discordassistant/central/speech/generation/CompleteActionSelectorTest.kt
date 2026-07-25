package com.discordassistant.central.speech.generation

import com.discordassistant.central.speech.application.generation.CompleteActionSelection
import com.discordassistant.central.speech.application.generation.CompleteActionSelector
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechResponseObligation
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompleteActionSelectorTest {
    private val optionalPacket = packet(SpeechResponseObligation.OPTIONAL)
    private val requiredPacket = packet(SpeechResponseObligation.REQUIRED)
    private val selector = CompleteActionSelector()

    @Test
    fun `Judge가 확정한 SPEAK는 OPTIONAL이어도 생존 후보를 다시 무시하지 않는다`() {
        val selected =
            selector.select(
                speechCandidates = listOf(SpeechCandidate("send_1", listOf("응, 그건 이렇게 보면 돼"))),
                packet = optionalPacket,
            )

        assertThat((selected as CompleteActionSelection.Send).candidate.candidateId).isEqualTo("send_1")
    }

    @Test
    fun `여러 생존 후보에서는 불확실성이 가장 낮은 후보를 결정적으로 고른다`() {
        val selected =
            selector.select(
                speechCandidates =
                    listOf(
                        SpeechCandidate("uncertain", listOf("아마 그럴 거야"), uncertainty = 0.7),
                        SpeechCandidate("certain", listOf("응, 그게 맞아"), uncertainty = 0.1),
                    ),
                packet = requiredPacket,
            )

        assertThat((selected as CompleteActionSelection.Send).candidate.candidateId).isEqualTo("certain")
    }

    @Test
    fun `같은 불확실성이면 생성 순서가 선택을 안정적으로 결정한다`() {
        val selected =
            selector.select(
                speechCandidates =
                    listOf(
                        SpeechCandidate("first", listOf("첫 후보"), uncertainty = 0.2),
                        SpeechCandidate("second", listOf("둘째 후보"), uncertainty = 0.2),
                    ),
                packet = optionalPacket,
            )

        assertThat((selected as CompleteActionSelection.Send).candidate.candidateId).isEqualTo("first")
    }

    @Test
    fun `OPTIONAL에서 생존 후보가 없고 리액션이 허용되면 리액션으로 낮춘다`() {
        val selected = selector.select(emptyList(), optionalPacket, offerReaction = true)

        assertThat(selected).isEqualTo(CompleteActionSelection.React("ack"))
    }

    @Test
    fun `REQUIRED에서 생존 후보가 없으면 리액션으로 대체하지 않고 침묵한다`() {
        val selected = selector.select(emptyList(), requiredPacket, offerReaction = true)

        assertThat(selected).isEqualTo(CompleteActionSelection.Ignore)
    }

    private fun packet(obligation: SpeechResponseObligation): SpeechScenePacket =
        SpeechScenePacket.of(
            focusThreadKey = "focus_1",
            target = SpeechTarget.member("user_1"),
            recentTurns = emptyList(),
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            burstShape = SpeechBurstShape(1, 280, false),
            identity = IdentityKernelSection.of("니아", "니아다", emptyList()),
            speechIntent = "현재 질문에 답한다",
            responseObligation = obligation,
        )
}
