package com.discordassistant.central.participation.application.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 인간 다음 행동 matcher(NEXA-P09-T012)·counterfactual observation window(NEXA-P09-T013) acceptance 단위 테스트.
 */
class CounterfactualAndMatcherTest {
    private val t0 = Instant.parse("2026-06-22T00:00:00Z")

    // ── T013 counterfactual window ────────────────────────────────────────────

    @Test
    fun `T013 — 3·10·30·120초 네 창을 모두 기록한다`() {
        val obs = CounterfactualObservation.build(t0, emptyList())
        assertThat(obs.observations.map { it.window })
            .containsExactly(
                CounterfactualWindow.W3S,
                CounterfactualWindow.W10S,
                CounterfactualWindow.W30S,
                CounterfactualWindow.W120S,
            )
    }

    @Test
    fun `T013 — late action 과 never 를 구분한다`() {
        // 25초 뒤 응답: 3·10초 창은 침묵, 30·120초 창은 행동 → late action.
        val late =
            CounterfactualObservation.build(
                t0,
                listOf(HumanActionEvent("m-1", HumanAct.REPLY, t0.plusSeconds(25))),
            )
        assertThat(late.isNever).isFalse()
        assertThat(late.isLateAction).isTrue()
        assertThat(late.firstActionWindow).isEqualTo(CounterfactualWindow.W30S)
        assertThat(late.observations.first { it.window == CounterfactualWindow.W3S }.isSilent).isTrue()

        // 아무 행동 없음 → never.
        val never = CounterfactualObservation.build(t0, emptyList())
        assertThat(never.isNever).isTrue()
        assertThat(never.isLateAction).isFalse()
    }

    @Test
    fun `T013 — 미래 leakage 없음 - 창 deadline 밖 행동은 그 창에 안 들어간다`() {
        // 5초 뒤 행동: 3초 창엔 없고(미래), 10초 창엔 있음.
        val obs =
            CounterfactualObservation.build(
                t0,
                listOf(HumanActionEvent("m-1", HumanAct.REACT, t0.plusSeconds(5))),
            )
        assertThat(obs.observations.first { it.window == CounterfactualWindow.W3S }.isSilent).isTrue()
        val w10 = obs.observations.first { it.window == CounterfactualWindow.W10S }
        assertThat(w10.isSilent).isFalse()
        assertThat(w10.firstActionDelay).isEqualTo(java.time.Duration.ofSeconds(5))
        // 120초보다 늦은 행동은 어떤 창에도 안 들어간다(미래 — never 로 남음).
        val farFuture = CounterfactualObservation.build(t0, listOf(HumanActionEvent("m-1", HumanAct.REPLY, t0.plusSeconds(300))))
        assertThat(farFuture.isNever).isTrue()
    }

    // ── T012 matcher ──────────────────────────────────────────────────────────

    @Test
    fun `T012 — 여러 명이 응답하면 MULTIPLE_RESPONDERS 로 모호성을 기록한다`() {
        val events =
            listOf(
                HumanActionEvent("m-1", HumanAct.REPLY, t0.plusSeconds(2)),
                HumanActionEvent("m-2", HumanAct.REACT, t0.plusSeconds(2).plusMillis(500)),
            )
        val obs = CounterfactualObservation.build(t0, events)
        val outcome = HumanNextActionMatcher.match(obs, events)
        assertThat(outcome.ambiguity).isEqualTo(MatchAmbiguity.MULTIPLE_RESPONDERS)
        // 특정 한 명을 정답으로 단정하지 않고 모든 후보를 동등하게 담는다.
        assertThat(outcome.candidates.map { it.memberPseudonym }).containsExactlyInAnyOrder("m-1", "m-2")
    }

    @Test
    fun `T012 — 한 명이면 SINGLE_RESPONDER, 아무도 없으면 NO_RESPONSE`() {
        val single = listOf(HumanActionEvent("m-1", HumanAct.REPLY, t0.plusSeconds(2)))
        val singleOutcome = HumanNextActionMatcher.match(CounterfactualObservation.build(t0, single), single)
        assertThat(singleOutcome.ambiguity).isEqualTo(MatchAmbiguity.SINGLE_RESPONDER)
        assertThat(singleOutcome.candidates).hasSize(1)

        val none = HumanNextActionMatcher.match(CounterfactualObservation.build(t0, emptyList()), emptyList())
        assertThat(none.ambiguity).isEqualTo(MatchAmbiguity.NO_RESPONSE)
        assertThat(none.candidates).isEmpty()
    }

    @Test
    fun `T012 — late action 일 때 첫 행동 창 안의 후보만 담는다(미래 leakage 없음)`() {
        // 5초 응답(첫 행동 창=10초). 50초의 다른 사람은 첫 행동 창 밖이라 후보 아님.
        val events =
            listOf(
                HumanActionEvent("m-1", HumanAct.REPLY, t0.plusSeconds(5)),
                HumanActionEvent("m-2", HumanAct.REPLY, t0.plusSeconds(50)),
            )
        val obs = CounterfactualObservation.build(t0, events)
        val outcome = HumanNextActionMatcher.match(obs, events)
        assertThat(outcome.isLateAction).isTrue()
        assertThat(outcome.candidates.map { it.memberPseudonym }).containsExactly("m-1") // m-2 는 창 밖
        assertThat(outcome.ambiguity).isEqualTo(MatchAmbiguity.SINGLE_RESPONDER)
    }
}
