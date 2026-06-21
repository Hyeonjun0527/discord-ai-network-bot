package com.discordassistant.central.participation.application.evaluation

import java.time.Duration
import java.time.Instant

/**
 * counterfactual 관찰 창(NEXA-P09-T013, application 레이어). shadow 예측 시점 이후 정해진 창 안에서 **실제로 무슨
 * 일이 있었는지**를 창 단위로 본다. NEXA 는 shadow 라 발화하지 않았으므로 이는 "만약 NEXA 가 가만히 있었다면"의
 * counterfactual 관찰이다.
 *
 * **acceptance(T013) — 3초/10초/30초/120초 window 별 실제 행동을 기록한다**: [Window] 가 정확히 그 네 창이다.
 */
enum class CounterfactualWindow(
    val upperBound: Duration,
) {
    W3S(Duration.ofSeconds(3)),
    W10S(Duration.ofSeconds(10)),
    W30S(Duration.ofSeconds(30)),
    W120S(Duration.ofSeconds(120)),
    ;

    /** [predictedAt] 기준 이 창의 마지막 시각(이 시각 이후 행동은 이 창에 없음). */
    fun deadline(predictedAt: Instant): Instant = predictedAt.plus(upperBound)

    companion object {
        /** 작은 창부터 큰 창 순(중첩 — 작은 창 행동은 큰 창에도 포함). */
        val ascending: List<CounterfactualWindow> = entries.sortedBy { it.upperBound }
    }
}

/**
 * 한 창의 관찰 결과(application 값 객체·불변). 그 창 안에서 **첫 인간 행동**이 무엇이었는지와 그 지연을 기록한다.
 *
 * **acceptance(T013) — late action 과 never 를 구분한다**:
 * - [observedAct] 가 SILENCE 면, 그 창 안에 행동이 없었다는 뜻이다. 더 큰 창에서 행동이 나오면 "late action"
 *   (작은 창엔 침묵, 큰 창엔 행동)이고, 가장 큰 창까지 침묵이면 "never"다 — [CounterfactualObservation] 이 창들을
 *   비교해 둘을 구분한다.
 *
 * **acceptance(T013) — 미래 feature leakage 가 없다**: 이 창의 관찰은 [CounterfactualWindow.deadline] **이내**
 * 행동만 본다. 창 밖(미래) 정보를 이 창의 결과로 끌어오지 않는다(빌더가 deadline 으로 자른다).
 */
data class WindowObservation(
    val window: CounterfactualWindow,
    /** 이 창 안에서 관찰된 첫 인간 행동(없으면 SILENCE). */
    val observedAct: HumanAct,
    /** 첫 행동까지의 지연(SILENCE 면 null). */
    val firstActionDelay: Duration?,
) {
    init {
        require(observedAct == HumanAct.SILENCE || firstActionDelay != null) {
            "행동이 있으면 firstActionDelay 가 있어야 한다"
        }
        require(firstActionDelay == null || !firstActionDelay.isNegative) { "firstActionDelay 는 음수일 수 없다" }
        require(firstActionDelay == null || firstActionDelay <= window.upperBound) {
            "firstActionDelay 는 창 상한 이내여야 한다(미래 leakage 금지)"
        }
    }

    val isSilent: Boolean
        get() = observedAct == HumanAct.SILENCE
}

/**
 * 관찰된 인간 행동 종류(application enum). shadow 예측 후 인간이 실제로 한 행동의 안정 코드(원문 비포함).
 */
enum class HumanAct {
    /** 답장/발화. */
    REPLY,

    /** 리액션(이모지 등). */
    REACT,

    /** 아무 행동 없음(이 창 안에서 침묵). */
    SILENCE,
}
