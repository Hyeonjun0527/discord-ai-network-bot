package com.discordassistant.central.speech.generation

import com.discordassistant.central.speech.application.generation.CompleteActionSelection
import com.discordassistant.central.speech.application.generation.CompleteActionSelector
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluation
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluationPort
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
    private val packet =
        SpeechScenePacket.of(
            focusThreadKey = "focus_1",
            target = SpeechTarget.member("user_1"),
            recentTurns = emptyList(),
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            burstShape = SpeechBurstShape(1, 280, false),
            identity = IdentityKernelSection.of("니아", "니아다", emptyList()),
            speechIntent = "반복 안내 대신 장난을 알아챈다",
        )

    @Test
    fun `평가기 결과가 IGNORE면 실제 SEND 후보가 있어도 침묵을 고른다`() {
        val selector =
            CompleteActionSelector(
                CompleteActionEvaluationPort {
                    CompleteActionEvaluation("action_ignore", "반복 안내를 피한다", "AVOID_REPEAT", 0.91)
                },
            )

        val selected = selector.select(listOf(SpeechCandidate("send_1", listOf("기능채널로 가"))), packet)

        assertThat(selected).isEqualTo(CompleteActionSelection.Ignore)
    }

    @Test
    fun `알 수 없는 후보 ID는 실행하지 않고 침묵으로 닫힌다`() {
        val selector =
            CompleteActionSelector(
                CompleteActionEvaluationPort {
                    CompleteActionEvaluation("unknown", "잘못된 선택", "UNKNOWN", 0.9)
                },
            )

        assertThat(selector.select(listOf(SpeechCandidate("send_1", listOf("응"))), packet))
            .isEqualTo(CompleteActionSelection.Ignore)
    }

    @Test
    fun `평가기 신뢰도가 낮으면 SEND를 실행하지 않는다`() {
        val selector =
            CompleteActionSelector(
                CompleteActionEvaluationPort {
                    CompleteActionEvaluation("send_1", "응답을 시도한다", "LOW_CONFIDENCE", 0.54)
                },
            )

        assertThat(selector.select(listOf(SpeechCandidate("send_1", listOf("응"))), packet))
            .isEqualTo(CompleteActionSelection.Ignore)
    }

    @Test
    fun `평가기 사용 불가면 SEND 후보를 실행하지 않고 침묵한다`() {
        val selector = CompleteActionSelector(CompleteActionEvaluationPort.Noop)

        assertThat(selector.select(listOf(SpeechCandidate("send_1", listOf("응"))), packet))
            .isEqualTo(CompleteActionSelection.Ignore)
    }

    @Test
    fun `AI judge가 현재 응답을 REQUIRED로 정하면 후단 평가기에 IGNORE와 REACT를 제공하지 않는다`() {
        var offeredKinds = emptyList<com.discordassistant.central.speech.application.port.out.CompleteActionKind>()
        val selector =
            CompleteActionSelector(
                CompleteActionEvaluationPort { request ->
                    offeredKinds = request.candidates.map { it.kind }
                    CompleteActionEvaluation("send_1", "최신 질문에 답한다", "CURRENT_TURN_ANSWERED", 0.9)
                },
            )
        val required = packet.copy(responseObligation = SpeechResponseObligation.REQUIRED)

        val selected = selector.select(listOf(SpeechCandidate("send_1", listOf("지금 질문에 답할게"))), required)

        assertThat(offeredKinds).containsExactly(com.discordassistant.central.speech.application.port.out.CompleteActionKind.SEND)
        assertThat(selected).isInstanceOf(CompleteActionSelection.Send::class.java)
    }

    @Test
    fun `REQUIRED 응답은 평가기 장애로 다시 장기 침묵하지 않고 가장 확실한 생존 후보를 보낸다`() {
        val selector = CompleteActionSelector(CompleteActionEvaluationPort.Noop)
        val required = packet.copy(responseObligation = SpeechResponseObligation.REQUIRED)

        val selected =
            selector.select(
                listOf(
                    SpeechCandidate("uncertain", listOf("아마"), uncertainty = 0.7),
                    SpeechCandidate("certain", listOf("응답"), uncertainty = 0.1),
                ),
                required,
            )

        assertThat((selected as CompleteActionSelection.Send).candidate.candidateId).isEqualTo("certain")
    }
}
