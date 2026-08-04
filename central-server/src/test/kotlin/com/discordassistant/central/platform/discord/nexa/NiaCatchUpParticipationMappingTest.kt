package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.participation.application.SafeDecision
import com.discordassistant.central.participation.application.catchup.NiaCatchUpJudgeResult
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaCatchUpParticipationMappingTest {
    @Test
    fun `발화 emit 완료는 재시도가 아니라 cursor를 전진시키는 완료 결과다`() {
        val outcome =
            ParticipationEmitOutcome.Emitted(
                NexaSpeechEmitResult.notSpeaking(
                    SafeDecision(
                        finalAction = SocialActionKind.SPEAK,
                        safetyChanged = false,
                        removedKinds = emptySet(),
                        consumedGenerationQuota = false,
                    ),
                ),
            )

        assertThat(outcome.toNiaCatchUpJudgeResult()).isEqualTo(NiaCatchUpJudgeResult.NON_IGNORE)
    }

    @Test
    fun `발화 예약 거절과 규칙 보류는 같은 장면을 다시 Judge하지 않는다`() {
        val outcomes =
            listOf(
                ParticipationEmitOutcome.SchedulingRejected("10:20"),
                ParticipationEmitOutcome.RuleWait("RULE_INCOMPLETE_BURST"),
                ParticipationEmitOutcome.AttentionDeferred("10:20"),
                ParticipationEmitOutcome.Inactive,
            )

        outcomes.forEach { outcome ->
            assertThat(outcome.toNiaCatchUpJudgeResult()).isEqualTo(NiaCatchUpJudgeResult.NON_IGNORE)
        }
    }

    @Test
    fun `실패와 최신 장면으로의 교체만 재시도한다`() {
        val outcomes =
            listOf(
                ParticipationEmitOutcome.Failed,
                ParticipationEmitOutcome.Superseded(NiaTurnSupersessionStage.BEFORE_JUDGE),
            )

        outcomes.forEach { outcome ->
            assertThat(outcome.toNiaCatchUpJudgeResult()).isEqualTo(NiaCatchUpJudgeResult.UNPROCESSED)
        }
    }

    @Test
    fun `Judge IGNORE는 CATCH_UP 침묵 카운터에 반영한다`() {
        val outcome = ParticipationEmitOutcome.NotSpeaking(SocialActionKind.IGNORE)

        assertThat(outcome.toNiaCatchUpJudgeResult()).isEqualTo(NiaCatchUpJudgeResult.IGNORE)
    }
}
