package com.discordassistant.central.socialmemory.domain.service.relationship

import com.discordassistant.central.socialmemory.domain.model.relationship.InteractionOutcome

/**
 * 관계 상태 온라인 update 안정화기(NEXA-P19-T008, 순수 함수·무상태, risk high).
 *
 * 새 [InteractionOutcome] 가 관찰될 때 bounded 관계 신호([0,1], 예: rapport)를 **온라인으로 갱신**하되, 한 번의
 * outcome 이 장기 관계를 뒤집지 못하도록 **최대 변화량(step cap)** 과 **최소 표본(min sample)** 을 강제한다.
 * EMA(지수이동평균) 형태로, 표본이 적을수록·step cap 때문에 천천히만 움직인다(폭주·발산 없음 — observable-state-policy
 * 불변식, ADR 0014 의 실시간 가능한 calibration 경계).
 *
 * **acceptance(T008) — 한 번의 부정/긍정 반응이 장기 관계를 뒤집지 않는다**:
 * - 각 update 의 변화량 절댓값 ≤ [Config.maxStep](step cap). 한 outcome 이 0↔1 로 뒤집을 수 없다.
 * - [Config.minSampleForFullRate] 미만의 표본에서는 학습률을 더 줄인다(초기 과신 방지 — 적은 표본에서 폭주 금지).
 * - 결과는 항상 [0,1] 로 clamp 된다(발산 없음). update 는 결정론(같은 입력=같은 출력).
 *
 * outcome → 목표 신호 매핑은 **관찰된 행동 사실**의 부호만 쓴다(심리 추론 아님): CONTINUED/REACTED=긍정 방향,
 * IGNORED/CORRECTED/COMPLAINED/DELETED=부정 방향. 매핑은 [targetSignal] 에 명시한다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 라이브러리만 쓴다(시스템 시각 미접근 — 표본 수는 인자로 받음).
 */
object RelationshipOnlineUpdate {
    /**
     * 온라인 update 안정화 파라미터(타입 안전 불변식).
     *
     * @param baseLearningRate 충분한 표본에서의 기본 학습률 [0,1].
     * @param maxStep 한 update 의 최대 변화량 [0,1](step cap — 단일 outcome 의 영향 상한).
     * @param minSampleForFullRate 이 표본 수 이상이면 baseLearningRate 를 그대로, 미만이면 비례 축소.
     */
    data class Config(
        val baseLearningRate: Double = DEFAULT_BASE_LEARNING_RATE,
        val maxStep: Double = DEFAULT_MAX_STEP,
        val minSampleForFullRate: Int = DEFAULT_MIN_SAMPLE,
    ) {
        init {
            require(baseLearningRate in 0.0..1.0) { "baseLearningRate 는 [0,1] 범위여야 한다: $baseLearningRate" }
            require(maxStep in 0.0..1.0) { "maxStep 은 [0,1] 범위여야 한다: $maxStep" }
            require(minSampleForFullRate >= 1) { "minSampleForFullRate 는 1 이상이어야 한다: $minSampleForFullRate" }
        }
    }

    /**
     * 현재 관계 신호 [current] 에 새 [outcome] 한 건을 반영한 갱신값을 돌려준다([0,1], step cap 적용).
     *
     * @param current 현재 bounded 관계 신호 [0,1].
     * @param outcome 새로 관찰된 상호작용 결과(닫힌 코드).
     * @param priorSampleCount 이 관계에서 지금까지 반영한 outcome 수(>=0). 적으면 학습률을 줄인다.
     * @param config 안정화 파라미터.
     */
    fun update(
        current: Double,
        outcome: InteractionOutcome,
        priorSampleCount: Int,
        config: Config = Config(),
    ): Double {
        require(current in 0.0..1.0) { "current 는 [0,1] 범위여야 한다: $current" }
        require(priorSampleCount >= 0) { "priorSampleCount 는 음수일 수 없다: $priorSampleCount" }

        val target = targetSignal(outcome)
        // 표본이 적으면 학습률을 비례 축소(초기 과신 방지). 충분하면 baseLearningRate.
        val sampleFactor =
            (priorSampleCount.toDouble() / config.minSampleForFullRate).coerceIn(0.0, 1.0)
        val effectiveRate = config.baseLearningRate * sampleFactor
        // EMA 제안 변화량 → step cap 으로 클램프(단일 outcome 영향 상한). 한 번에 뒤집기 불가.
        val proposedDelta = effectiveRate * (target - current)
        val cappedDelta = proposedDelta.coerceIn(-config.maxStep, config.maxStep)
        return (current + cappedDelta).coerceIn(0.0, 1.0)
    }

    /**
     * outcome → 목표 신호 [0,1]. 관찰된 행동 부호만 쓴다(심리 추론 없음):
     * 이어감/reaction = 1.0(긍정 방향), 무응답/정정/불만/삭제 = 0.0(부정 방향).
     */
    fun targetSignal(outcome: InteractionOutcome): Double =
        when (outcome) {
            InteractionOutcome.CONTINUED, InteractionOutcome.REACTED -> 1.0
            InteractionOutcome.IGNORED,
            InteractionOutcome.CORRECTED,
            InteractionOutcome.COMPLAINED,
            InteractionOutcome.DELETED,
            -> 0.0
        }

    /** 기본 학습률(충분한 표본에서). 작게 둬 천천히 움직인다. */
    const val DEFAULT_BASE_LEARNING_RATE = 0.2

    /** 한 update 최대 변화량(step cap). 단일 outcome 으로 0↔1 뒤집기 방지. */
    const val DEFAULT_MAX_STEP = 0.1

    /** 이 표본 수 이상이면 학습률을 전부 적용(미만이면 비례 축소). */
    const val DEFAULT_MIN_SAMPLE = 5
}
