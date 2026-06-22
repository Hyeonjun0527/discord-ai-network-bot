package com.discordassistant.central.global.observability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P18-T011 acceptance: 점유율·연속 burst·mention spike·queue backlog 기준 alert 를 만들고, alert 발생 시
 * **자동 downgrade/kill 조건과 사람 확인이 구분**된다.
 */
class NexaOverTalkAlertEvaluatorTest {
    private val evaluator = NexaOverTalkAlertEvaluator()

    @Test
    fun `no alert when all signals are under warn thresholds`() {
        val alerts =
            evaluator.evaluate(
                NexaOverTalkSignals(shareRatio = 0.1, consecutiveBursts = 1, mentionResponsesPerMin = 2.0, queueBacklog = 2),
            )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `warn threshold yields human-confirm response`() {
        // share 0.4 ≥ warn(0.35) < critical(0.5) → 사람 확인.
        val alerts =
            evaluator.evaluate(
                NexaOverTalkSignals(shareRatio = 0.4, consecutiveBursts = 0, mentionResponsesPerMin = 0.0, queueBacklog = 0),
            )
        assertEquals(1, alerts.size)
        assertEquals(NexaOverTalkSignalKind.SHARE_RATIO, alerts[0].signal)
        assertEquals(NexaAlertResponse.HUMAN_CONFIRM, alerts[0].response)
    }

    @Test
    fun `critical threshold yields auto-downgrade response distinct from human-confirm`() {
        // share 0.6 ≥ critical(0.5) → 자동 강등. consecutiveBursts 5 ≥ critical → 자동 강등.
        val alerts =
            evaluator.evaluate(
                NexaOverTalkSignals(shareRatio = 0.6, consecutiveBursts = 5, mentionResponsesPerMin = 0.0, queueBacklog = 0),
            )
        val share = alerts.first { it.signal == NexaOverTalkSignalKind.SHARE_RATIO }
        val bursts = alerts.first { it.signal == NexaOverTalkSignalKind.CONSECUTIVE_BURSTS }
        assertEquals(NexaAlertResponse.AUTO_DOWNGRADE, share.response)
        assertEquals(NexaAlertResponse.AUTO_DOWNGRADE, bursts.response)
    }

    @Test
    fun `all four signal kinds can fire`() {
        val alerts =
            evaluator.evaluate(
                NexaOverTalkSignals(shareRatio = 0.6, consecutiveBursts = 6, mentionResponsesPerMin = 25.0, queueBacklog = 60),
            )
        assertEquals(
            setOf(
                NexaOverTalkSignalKind.SHARE_RATIO,
                NexaOverTalkSignalKind.CONSECUTIVE_BURSTS,
                NexaOverTalkSignalKind.MENTION_RESPONSE_SPIKE,
                NexaOverTalkSignalKind.QUEUE_BACKLOG,
            ),
            alerts.map { it.signal }.toSet(),
        )
        assertTrue(alerts.all { it.response == NexaAlertResponse.AUTO_DOWNGRADE })
    }
}
