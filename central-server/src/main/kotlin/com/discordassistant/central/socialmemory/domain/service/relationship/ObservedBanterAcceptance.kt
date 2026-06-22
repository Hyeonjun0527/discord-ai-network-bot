package com.discordassistant.central.socialmemory.domain.service.relationship

/**
 * observed banter acceptance(관찰된 농담 수용) 지표(NEXA-P06-T007, 순수 도메인 값 객체·무상태).
 *
 * NEXA 의 **장난성 social act 이후 관찰된 행동**만 누적한다(observable-state-policy 허용: "농담에 농담으로
 * 반응했는가 — 행동, 추론 아님"):
 * - [positiveSignals]: 긍정 reply/reaction(되받은 농담·웃음 reaction 등) 횟수.
 * - [stopSignals]: 중단 신호(무응답·주제 전환·삭제 등) 횟수.
 *
 * **윤리 가드(acceptance T007, risk high)**:
 * - "이를 사용자의 **성격으로 명명하지 않는다**": 이 객체는 성격/유머감각/MBTI 같은 라벨을 갖지 않는다 — 관찰된
 *   positive/stop 카운트뿐이다([accepted/playful 같은 성격 라벨 부재]). observable-state-policy 금지 목록(성격 추론) 준수.
 * - "**낮은 표본에서는 정책에 강하게 쓰지 않는다**": [acceptanceConfidence] 가 표본 수로 0→1 포화 곡선을 만들어,
 *   표본이 적으면 confidence 가 낮다. 정책은 [acceptanceRate] 를 [acceptanceConfidence] 로 가중해 써야 한다
 *   ([weightedSignedAcceptance] 제공).
 *
 * 순수성: Spring/JPA/JDA 미참조. kotlin.math 만 쓴다.
 */
data class ObservedBanterAcceptance(
    /** 장난 이후 관찰된 긍정 reply/reaction 횟수(되받음). */
    val positiveSignals: Int,
    /** 장난 이후 관찰된 중단/회피 신호 횟수(무응답·주제 전환·삭제). */
    val stopSignals: Int,
) {
    init {
        require(positiveSignals >= 0) { "positiveSignals 는 음수일 수 없다" }
        require(stopSignals >= 0) { "stopSignals 는 음수일 수 없다" }
    }

    /** 관찰 총 표본 수(긍정 + 중단). */
    val sampleCount: Int
        get() = positiveSignals + stopSignals

    /**
     * 긍정 비율 [0,1] (positive / sample). 표본 0 이면 0.5(중립 — 모른다). 성격이 아니라 관찰 비율이다.
     */
    val acceptanceRate: Double
        get() = if (sampleCount == 0) 0.5 else positiveSignals.toDouble() / sampleCount.toDouble()

    /**
     * 표본 수 기반 신뢰도 [0,1) — sample / (sample + [CONFIDENCE_SCALE]). 표본이 적으면 0 에 가깝다.
     * "낮은 표본에서 정책에 강하게 쓰지 않는다"(acceptance T007) 를 수치로 강제하는 게이트다.
     */
    val acceptanceConfidence: Double
        get() = sampleCount.toDouble() / (sampleCount + CONFIDENCE_SCALE).toDouble()

    /**
     * 정책 입력용 가중 부호값 [-conf, +conf]. (acceptanceRate 를 [-1,1] 로 옮긴 뒤 confidence 로 곱한다.)
     * 표본이 적으면 0 근처라 정책에 약하게만 반영된다(저표본 안전장치).
     */
    val weightedSignedAcceptance: Double
        get() = (acceptanceRate * 2.0 - 1.0) * acceptanceConfidence

    /** 긍정 신호 1건을 더한 새 관찰값. */
    fun observePositive(): ObservedBanterAcceptance = copy(positiveSignals = positiveSignals + 1)

    /** 중단 신호 1건을 더한 새 관찰값. */
    fun observeStop(): ObservedBanterAcceptance = copy(stopSignals = stopSignals + 1)

    companion object {
        /** confidence 포화 척도 — 이 표본 수에서 confidence 가 0.5 가 된다. */
        const val CONFIDENCE_SCALE = 8

        /** 관찰 없는 초기 상태(rate 0.5·confidence 0 — 모른다). */
        val EMPTY: ObservedBanterAcceptance = ObservedBanterAcceptance(positiveSignals = 0, stopSignals = 0)
    }
}
