package com.discordassistant.central.socialmemory.domain.service.relationship

/**
 * interaction reciprocity(상호성) 지표(NEXA-P06-T006, 순수 도메인 값 객체·무상태 계산).
 *
 * reciprocity 를 **두 방향으로 분리**한다(observable-state-policy 허용: reciprocity = 주고받음의 균형 관찰):
 * - [memberResponseRate]: NEXA 가 말한 뒤 **상대가 반응한** 비율.
 * - [nexaResponseRate]: 상대가 NEXA 를 호출한 뒤 **NEXA 가 반응한** 비율.
 *
 * 어느 쪽도 관계 감정으로 해석하지 않는다 — 관찰된 응답 빈도일 뿐이다.
 *
 * **acceptance(T006) — 분모가 작은 초기 상태에 smoothing 이 적용된다**:
 * 각 비율은 Laplace/additive smoothing (responses + [priorResponses]) / (opportunities + [priorTotal]) 로
 * 계산한다. 표본이 0 이어도 0/0 이 아니라 prior 기반 [priorResponses]/[priorTotal] 로 수렴해 초기 과신을 막는다
 * (낮은 표본에서 극단값 금지, observable-state-policy 체크리스트와 정합).
 */
data class InteractionReciprocity(
    /** NEXA 발화 횟수(상대가 반응할 기회). */
    val nexaInitiations: Int,
    /** 그 중 상대가 실제로 반응한 횟수. */
    val memberResponses: Int,
    /** 상대가 NEXA 를 호출한 횟수(NEXA 가 반응할 기회). */
    val memberInitiations: Int,
    /** 그 중 NEXA 가 실제로 반응한 횟수. */
    val nexaResponses: Int,
) {
    init {
        require(nexaInitiations >= 0) { "nexaInitiations 는 음수일 수 없다" }
        require(memberInitiations >= 0) { "memberInitiations 는 음수일 수 없다" }
        require(memberResponses in 0..nexaInitiations) {
            "memberResponses 는 [0, nexaInitiations] 범위여야 한다"
        }
        require(nexaResponses in 0..memberInitiations) {
            "nexaResponses 는 [0, memberInitiations] 범위여야 한다"
        }
    }

    /**
     * NEXA 가 말한 뒤 상대가 반응한 smoothing 된 비율 [0,1]. 표본 0 이면 prior 로 수렴.
     */
    fun memberResponseRate(
        priorResponses: Double = DEFAULT_PRIOR_RESPONSES,
        priorTotal: Double = DEFAULT_PRIOR_TOTAL,
    ): Double = smoothedRate(memberResponses, nexaInitiations, priorResponses, priorTotal)

    /**
     * 상대 호출에 NEXA 가 반응한 smoothing 된 비율 [0,1]. 표본 0 이면 prior 로 수렴.
     */
    fun nexaResponseRate(
        priorResponses: Double = DEFAULT_PRIOR_RESPONSES,
        priorTotal: Double = DEFAULT_PRIOR_TOTAL,
    ): Double = smoothedRate(nexaResponses, memberInitiations, priorResponses, priorTotal)

    companion object {
        /** smoothing prior: 사전 반응 수(분자). */
        const val DEFAULT_PRIOR_RESPONSES = 1.0

        /** smoothing prior: 사전 기회 수(분모). prior 비율 = priorResponses/priorTotal. */
        const val DEFAULT_PRIOR_TOTAL = 2.0

        private fun smoothedRate(
            responses: Int,
            opportunities: Int,
            priorResponses: Double,
            priorTotal: Double,
        ): Double {
            require(priorTotal > 0.0) { "priorTotal 은 양수여야 한다" }
            require(priorResponses in 0.0..priorTotal) { "priorResponses 는 [0, priorTotal] 범위여야 한다" }
            return ((responses + priorResponses) / (opportunities + priorTotal)).coerceIn(0.0, 1.0)
        }
    }
}
