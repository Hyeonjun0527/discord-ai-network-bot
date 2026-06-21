package com.discordassistant.central.participation.application.evaluation

import java.time.Duration
import java.time.Instant

/**
 * 한 shadow 예측에 대한 창별 counterfactual 관찰 묶음(NEXA-P09-T013, application 값 객체·불변).
 *
 * 예측 시점([predictedAt]) 이후 [CounterfactualWindow] 네 창(3/10/30/120초) 각각의 관찰을 담고, **late action 과
 * never 를 구분**한다.
 *
 * **acceptance(T013)**:
 * - **window 별 기록**: [observations] 가 네 창 각각의 [WindowObservation].
 * - **late vs never**: [firstActionWindow] 가 처음 행동이 나온 창(없으면 never). [isNever] / [isLateAction] 로 구분.
 * - **미래 leakage 없음**: 각 창 관찰은 그 창 deadline 이내 행동만 본다([build] 가 deadline 으로 자른다).
 */
data class CounterfactualObservation(
    /** shadow 예측 시각(관찰 기준점). */
    val predictedAt: Instant,
    /** 창별 관찰(작은 창→큰 창). 네 창 모두 존재한다. */
    val observations: List<WindowObservation>,
) {
    init {
        require(observations.map { it.window } == CounterfactualWindow.ascending) {
            "observations 는 3/10/30/120초 네 창을 오름차순으로 모두 가져야 한다"
        }
    }

    /** 처음으로 인간 행동이 관찰된 창(없으면 null = never). */
    val firstActionWindow: CounterfactualWindow?
        get() = observations.firstOrNull { !it.isSilent }?.window

    /** 가장 큰 창까지 인간 행동이 없었는가(never). */
    val isNever: Boolean
        get() = firstActionWindow == null

    /** 작은 창엔 없었지만 더 큰 창에서 행동이 나왔는가(late action — 즉시 응답이 아님). */
    val isLateAction: Boolean
        get() = firstActionWindow != null && firstActionWindow != CounterfactualWindow.ascending.first()

    companion object {
        /**
         * [predictedAt] 이후 인간 행동들([events])로 창별 관찰을 만든다. 각 창은 deadline 이내 행동만 보고(미래
         * leakage 금지), 그 창의 **첫** 행동을 관찰로 삼는다. 행동이 없으면 SILENCE.
         *
         * [events] 는 예측 이후 같은 채널의 인간 행동들이다(시각 오름차순일 필요는 없음 — 내부에서 정렬).
         */
        fun build(
            predictedAt: Instant,
            events: List<HumanActionEvent>,
        ): CounterfactualObservation {
            val sorted = events.filter { !it.at.isBefore(predictedAt) }.sortedBy { it.at }
            val observations =
                CounterfactualWindow.ascending.map { window ->
                    val deadline = window.deadline(predictedAt)
                    val first = sorted.firstOrNull { !it.at.isAfter(deadline) }
                    if (first == null) {
                        WindowObservation(window = window, observedAct = HumanAct.SILENCE, firstActionDelay = null)
                    } else {
                        WindowObservation(
                            window = window,
                            observedAct = first.act,
                            firstActionDelay = Duration.between(predictedAt, first.at),
                        )
                    }
                }
            return CounterfactualObservation(predictedAt = predictedAt, observations = observations)
        }
    }
}

/**
 * 예측 이후 관찰된 한 인간 행동(application 값 객체). 누가([memberPseudonym])·언제([at])·무엇([act])을 했는지의
 * 가명·안정 코드만(원문 비포함).
 */
data class HumanActionEvent(
    /** 행동한 사람의 가명(원본 snowflake 아님). matching ambiguity 기록에 쓰인다(T012). */
    val memberPseudonym: String,
    /** 행동 종류(REPLY/REACT — SILENCE 는 이벤트가 아니라 부재). */
    val act: HumanAct,
    /** 행동 시각. */
    val at: Instant,
) {
    init {
        require(memberPseudonym.isNotBlank()) { "memberPseudonym 은 비어 있을 수 없다" }
        require(act != HumanAct.SILENCE) { "SILENCE 는 이벤트로 표현하지 않는다(행동의 부재)" }
    }
}
