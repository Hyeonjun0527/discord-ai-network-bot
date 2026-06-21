package com.discordassistant.central.participation.application.evaluation

import java.time.Duration

/**
 * shadow 예측 시점 이후 정해진 창에서 인간 reply/react/silence 를 관찰해 **outcome 후보**를 만드는 matcher
 * (NEXA-P09-T012, application 레이어).
 *
 * shadow 예측은 "NEXA 가 무엇을 했을지"의 가설이다. 그 평가를 위해 "사람들은 실제로 무엇을 했나"를 outcome
 * 후보로 만든다 — 단, **특정 인간 하나를 NEXA 정답으로 단정하지 않는다**.
 *
 * **acceptance(T012) — 특정 인간 하나를 NEXA 정답으로 단정하지 않고 matching ambiguity 를 기록한다**:
 * - 첫 행동 창([CounterfactualObservation.firstActionWindow]) 안에 행동한 **모든** 사람을 동등한 후보로 담는다
 *   (한 명을 "그게 NEXA 였어야 했다"로 못박지 않는다).
 * - 여러 명이 응답했으면 [MatchOutcome.ambiguity] = MULTIPLE_RESPONDERS, 한 명이면 SINGLE_RESPONDER, 아무도
 *   없으면 NO_RESPONSE — ambiguity 를 1급으로 기록해 후속 평가가 단정하지 않게 한다.
 *
 * 순수성 경계: application 레이어 — 표준 타입·평가 값 객체만. Spring/JPA/JDA·GLM 미참조(순수 결정 — 같은 관찰=같은 결과).
 */
object HumanNextActionMatcher {
    /**
     * [observation] 에서 outcome 후보를 만든다. 첫 행동 창 안의 모든 행동을 후보로 삼고, 그 창에서 가장 이른
     * 행동의 지연을 대표 지연으로 둔다(never 면 후보 없음·SILENCE outcome).
     *
     * [events] 는 [observation] 을 만든 것과 같은 인간 행동들(후보별 가명·종류 식별에 필요).
     */
    fun match(
        observation: CounterfactualObservation,
        events: List<HumanActionEvent>,
    ): MatchOutcome {
        val firstWindow =
            observation.firstActionWindow
                ?: return MatchOutcome(
                    candidates = emptyList(),
                    ambiguity = MatchAmbiguity.NO_RESPONSE,
                    isLateAction = false,
                    representativeDelay = null,
                )

        val deadline = firstWindow.deadline(observation.predictedAt)
        // 첫 행동 창 deadline 이내 행동만 후보(미래 leakage 금지 — T013 과 같은 경계).
        val inWindow =
            events
                .filter { !it.at.isBefore(observation.predictedAt) && !it.at.isAfter(deadline) }
                .sortedBy { it.at }
        val candidates =
            inWindow.map { ev ->
                OutcomeCandidate(
                    memberPseudonym = ev.memberPseudonym,
                    act = ev.act,
                    delay = Duration.between(observation.predictedAt, ev.at),
                )
            }
        // 같은 사람이 여러 번 행동했을 수 있으니 distinct member 수로 ambiguity 판정.
        val distinctResponders = candidates.map { it.memberPseudonym }.distinct().size
        val ambiguity =
            when {
                distinctResponders == 0 -> MatchAmbiguity.NO_RESPONSE
                distinctResponders == 1 -> MatchAmbiguity.SINGLE_RESPONDER
                else -> MatchAmbiguity.MULTIPLE_RESPONDERS
            }
        return MatchOutcome(
            candidates = candidates,
            ambiguity = ambiguity,
            isLateAction = observation.isLateAction,
            representativeDelay = candidates.minByOrNull { it.delay }?.delay,
        )
    }
}

/**
 * matcher 결과(application 값 객체·불변). outcome 후보들과 **matching ambiguity** 를 담는다 — 단정하지 않는다(T012).
 */
data class MatchOutcome(
    /** 첫 행동 창 안에 행동한 모든 사람(동등 후보 — 한 명을 정답으로 못박지 않음). never 면 빈 리스트. */
    val candidates: List<OutcomeCandidate>,
    /** 매칭 모호성(응답자 0/1/다수). 후속 평가가 단정하지 않게 1급 기록. */
    val ambiguity: MatchAmbiguity,
    /** 늦은 행동(작은 창엔 침묵, 큰 창에 행동)인가 — counterfactual 창과 일치. */
    val isLateAction: Boolean,
    /** 대표 지연(가장 이른 후보의 지연, never 면 null). */
    val representativeDelay: Duration?,
)

/**
 * 한 outcome 후보(application 값 객체). 누가·무엇을·언제(지연) 했는지의 가명·안정 코드만(원문 비포함).
 */
data class OutcomeCandidate(
    val memberPseudonym: String,
    val act: HumanAct,
    val delay: Duration,
)

/**
 * 매칭 모호성(application enum). 특정 인간을 NEXA 정답으로 단정하지 않기 위해 ambiguity 를 1급으로 기록한다(T012).
 */
enum class MatchAmbiguity {
    /** 창 안에 아무도 응답하지 않음(never/silence). */
    NO_RESPONSE,

    /** 한 사람만 응답(그래도 그게 NEXA "정답"이라 단정하지는 않는다 — 단지 후보 1명). */
    SINGLE_RESPONDER,

    /** 여러 사람이 응답 — 누가 NEXA 자리였는지 본질적으로 모호(다수 후보 동등). */
    MULTIPLE_RESPONDERS,
}
