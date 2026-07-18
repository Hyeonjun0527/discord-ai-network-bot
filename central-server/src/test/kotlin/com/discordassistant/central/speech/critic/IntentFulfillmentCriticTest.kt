package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.service.critic.CriticReason
import com.discordassistant.central.speech.domain.service.critic.IntentFulfillmentCritic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IntentFulfillmentCriticTest {
    private val critic = IntentFulfillmentCritic()

    @Test
    fun `rejects an empty future promise instead of the requested story`() {
        val packet = SpeechCriticFixtures.packet(speechIntent = "intent_summary=재밌는 이야기를 실제로 들려준다")

        val verdict = critic.evaluate(SpeechCriticFixtures.candidate("c1", "재밌는 이야기 준비해볼게"), packet)

        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.INTENT_NOT_FULFILLED)
    }

    @Test
    fun `accepts a story that is performed now`() {
        val packet = SpeechCriticFixtures.packet(speechIntent = "intent_summary=재밌는 이야기를 실제로 들려준다")

        val verdict =
            critic.evaluate(
                SpeechCriticFixtures.candidate("c1", "어제 고양이가 로봇청소기에 올라타더니 집 안을 순찰하는 대장이 됐거든 ㅋㅋ"),
                packet,
            )

        assertThat(verdict.accepted).isTrue()
    }

    @Test
    fun `rejects a long future promise even when it contains substantive words`() {
        val packet = SpeechCriticFixtures.packet(speechIntent = "intent_summary=다익스트라를 실제로 설명한다")

        val verdict =
            critic.evaluate(
                SpeechCriticFixtures.candidate(
                    "c1",
                    "다익스트라는 최단 경로 문제에 쓰는 알고리즘이고 우선순위 큐도 필요한데 자세한 설명은 나중에 해줄게",
                ),
                packet,
            )

        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.INTENT_NOT_FULFILLED)
    }

    @Test
    fun `rejects an empty deferral even when the intent marker is unknown`() {
        val packet = SpeechCriticFixtures.packet(speechIntent = "scene_direction=상대의 요청을 수행한다")

        val verdict = critic.evaluate(SpeechCriticFixtures.candidate("c1", "좋은 거 준비해볼게"), packet)

        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.INTENT_NOT_FULFILLED)
    }

    @Test
    fun `rejects an apology intent without an apology`() {
        val packet = SpeechCriticFixtures.packet(speechIntent = "scene_direction=방금 실수를 사과하고 수습한다")

        val verdict = critic.evaluate(SpeechCriticFixtures.candidate("c1", "그럴 수도 있지 뭐"), packet)

        assertThat(verdict.rejected).isTrue()
        assertThat(verdict.reason).isEqualTo(CriticReason.INTENT_NOT_FULFILLED)
    }

    @Test
    fun `accepts an immediate explanation even when it is concise`() {
        val packet = SpeechCriticFixtures.packet(speechIntent = "act_hint=다익스트라를 간단히 설명한다")

        val verdict =
            critic.evaluate(
                SpeechCriticFixtures.candidate("c1", "출발점에서 가장 가까운 미방문 정점을 하나씩 확정하는 최단경로 알고리즘이야"),
                packet,
            )

        assertThat(verdict.accepted).isTrue()
    }

    @Test
    fun `does not impose an act when intent has no performative marker`() {
        val packet = SpeechCriticFixtures.packet(speechIntent = "scene_direction=장난스럽게 받아친다")

        assertThat(critic.evaluate(SpeechCriticFixtures.candidate("c1", "너 일부러 그러지 ㅋㅋ"), packet).accepted).isTrue()
    }
}
