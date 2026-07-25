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
    fun `AI judge가 현재 응답을 REQUIRED로 정했고 후보가 하나면 결과가 같은 후단 평가를 생략한다`() {
        var evaluationCalls = 0
        val selector =
            CompleteActionSelector(
                CompleteActionEvaluationPort {
                    evaluationCalls++
                    CompleteActionEvaluation("send_1", "최신 질문에 답한다", "CURRENT_TURN_ANSWERED", 0.9)
                },
            )
        val required = packet.copy(responseObligation = SpeechResponseObligation.REQUIRED)

        val selected = selector.select(listOf(SpeechCandidate("send_1", listOf("지금 질문에 답할게"))), required)

        assertThat(evaluationCalls).isZero()
        assertThat(selected).isInstanceOf(CompleteActionSelection.Send::class.java)
    }

    @Test
    fun `REQUIRED 응답의 완전히 같은 문구 후보는 하나로 줄여 평가기를 호출하지 않는다`() {
        var evaluationCalls = 0
        val selector =
            CompleteActionSelector(
                CompleteActionEvaluationPort {
                    evaluationCalls++
                    error("같은 문구 후보를 다시 평가하면 안 된다")
                },
            )
        val required = packet.copy(responseObligation = SpeechResponseObligation.REQUIRED)

        val selected =
            selector.select(
                listOf(
                    SpeechCandidate("duplicate_uncertain", listOf("응 같은 답"), uncertainty = 0.7),
                    SpeechCandidate("duplicate_certain", listOf("응 같은 답"), uncertainty = 0.1),
                ),
                required,
            )

        assertThat(evaluationCalls).isZero()
        assertThat((selected as CompleteActionSelection.Send).candidate.candidateId).isEqualTo("duplicate_certain")
    }

    @Test
    fun `REQUIRED 다중 SEND도 우회 플래그가 꺼져 있으면 평가기를 호출한다`() {
        var evaluationCalls = 0
        val selector =
            CompleteActionSelector(
                evaluator =
                    CompleteActionEvaluationPort {
                        evaluationCalls++
                        CompleteActionEvaluation("send_2", "장면에 더 자연스럽다", "BEST_COMPLETE_ACTION", 0.9)
                    },
                requiredBypassEnabled = false,
            )
        val required = packet.copy(responseObligation = SpeechResponseObligation.REQUIRED)

        val selected =
            selector.select(
                listOf(
                    SpeechCandidate("send_1", listOf("첫 후보")),
                    SpeechCandidate("send_2", listOf("둘째 후보")),
                ),
                required,
            )

        assertThat(evaluationCalls).isEqualTo(1)
        assertThat((selected as CompleteActionSelection.Send).candidate.candidateId).isEqualTo("send_2")
    }

    @Test
    fun `REQUIRED 다중 SEND는 우회 플래그와 임계 신뢰도를 만족하면 가장 확실한 후보를 고른다`() {
        var evaluationCalls = 0
        val selector =
            CompleteActionSelector(
                evaluator =
                    CompleteActionEvaluationPort {
                        evaluationCalls++
                        error("신뢰도 높은 REQUIRED 후보를 다시 평가하면 안 된다")
                    },
                requiredBypassEnabled = true,
                requiredBypassMinConfidence = 0.90,
            )
        val required = packet.copy(responseObligation = SpeechResponseObligation.REQUIRED)

        val selected =
            selector.select(
                speechCandidates =
                    listOf(
                        SpeechCandidate("uncertain", listOf("아마 그럴 거야"), uncertainty = 0.7),
                        SpeechCandidate("certain", listOf("응 그게 맞아"), uncertainty = 0.1),
                    ),
                packet = required,
                provisionalConfidence = 0.90,
            )

        assertThat(evaluationCalls).isZero()
        assertThat((selected as CompleteActionSelection.Send).candidate.candidateId).isEqualTo("certain")
    }

    @Test
    fun `REQUIRED 다중 SEND라도 잠정 신뢰도가 임계값보다 낮으면 평가기를 호출한다`() {
        var evaluationCalls = 0
        val selector =
            CompleteActionSelector(
                evaluator =
                    CompleteActionEvaluationPort {
                        evaluationCalls++
                        CompleteActionEvaluation("evaluated", "문맥상 더 낫다", "BEST_COMPLETE_ACTION", 0.9)
                    },
                requiredBypassEnabled = true,
                requiredBypassMinConfidence = 0.95,
            )
        val required = packet.copy(responseObligation = SpeechResponseObligation.REQUIRED)

        val selected =
            selector.select(
                speechCandidates =
                    listOf(
                        SpeechCandidate("fallback", listOf("확실한 후보"), uncertainty = 0.1),
                        SpeechCandidate("evaluated", listOf("평가기가 고를 후보"), uncertainty = 0.7),
                    ),
                packet = required,
                provisionalConfidence = 0.94,
            )

        assertThat(evaluationCalls).isEqualTo(1)
        assertThat((selected as CompleteActionSelection.Send).candidate.candidateId).isEqualTo("evaluated")
    }

    @Test
    fun `OPTIONAL 다중 SEND는 우회 조건의 신뢰도가 높아도 평가기를 호출한다`() {
        var evaluationCalls = 0
        val selector =
            CompleteActionSelector(
                evaluator =
                    CompleteActionEvaluationPort {
                        evaluationCalls++
                        CompleteActionEvaluation("evaluated", "선택적 응답을 비교한다", "BEST_COMPLETE_ACTION", 0.9)
                    },
                requiredBypassEnabled = true,
                requiredBypassMinConfidence = 0.90,
            )

        val selected =
            selector.select(
                speechCandidates =
                    listOf(
                        SpeechCandidate("fallback", listOf("확실한 후보"), uncertainty = 0.1),
                        SpeechCandidate("evaluated", listOf("평가기가 고를 후보"), uncertainty = 0.7),
                    ),
                packet = packet,
                provisionalConfidence = 1.0,
            )

        assertThat(evaluationCalls).isEqualTo(1)
        assertThat((selected as CompleteActionSelection.Send).candidate.candidateId).isEqualTo("evaluated")
    }

    @Test
    fun `생성 후보와 reaction이 없어서 IGNORE만 가능하면 평가기를 호출하지 않는다`() {
        var evaluationCalls = 0
        val selector =
            CompleteActionSelector(
                CompleteActionEvaluationPort {
                    evaluationCalls++
                    CompleteActionEvaluation("action_ignore", "침묵한다", "ONLY_ACTION", 1.0)
                },
            )

        val selected = selector.select(emptyList(), packet, offerReaction = false)

        assertThat(evaluationCalls).isZero()
        assertThat(selected).isEqualTo(CompleteActionSelection.Ignore)
    }

    @Test
    fun `REQUIRED인데 생성 후보가 없으면 빈 후보 평가를 호출하지 않고 안전하게 침묵한다`() {
        var evaluationCalls = 0
        val selector =
            CompleteActionSelector(
                CompleteActionEvaluationPort {
                    evaluationCalls++
                    error("빈 후보를 평가하면 안 된다")
                },
            )
        val required = packet.copy(responseObligation = SpeechResponseObligation.REQUIRED)

        val selected = selector.select(emptyList(), required)

        assertThat(evaluationCalls).isZero()
        assertThat(selected).isEqualTo(CompleteActionSelection.Ignore)
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
