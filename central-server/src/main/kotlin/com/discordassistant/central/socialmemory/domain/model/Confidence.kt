package com.discordassistant.central.socialmemory.domain.model

import java.time.Duration
import java.time.Instant

/**
 * 한 기억이 얼마나 확실한가를 [0,1] 로 나타내는 **신뢰 모델**(NEXA-P07-T010, 순수 도메인 값 객체·불변).
 *
 * 출처 종류([MemoryEvidence])별 **기본 신뢰가 다르다** — 명시적 Discord 이벤트(본인이 직접 한 행동) > 반복 언급 >
 * GLM 단일 추출(약한 근거). 시간이 지나면 감쇠한다(영구 낙인 금지, observable-state-policy 불변식 3). 반복 관찰은
 * 신뢰를 높이되 1.0 으로 단정하지 않는다(점근).
 *
 * **acceptance(T010) — GLM 한 번의 추출이 확정 사실이 되지 않는다**: [MemoryEvidence.GLM_EXTRACTION] 의 기본 신뢰는
 * 낮고([CERTAIN] 임계 미만), [isCertain] 이 false 다. 확정으로 올리려면 추가 명시 이벤트 관찰이 필요하다([reinforced]).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다.
 */
data class Confidence(
    /** [0,1] 신뢰 값. 1.0 은 단정이 아니라 상한일 뿐 — GLM 단일 추출로는 도달 불가. */
    val value: Double,
) {
    init {
        require(value in 0.0..1.0) { "confidence value 는 [0,1] 범위여야 한다: $value" }
    }

    /** 확정 사실로 취급할 만큼 충분히 높은가(임계 [CERTAIN_THRESHOLD] 이상). GLM 단일 추출은 false. */
    val isCertain: Boolean
        get() = value >= CERTAIN_THRESHOLD

    /**
     * 동일 사실을 다시 관찰했을 때 신뢰를 끌어올린 값(1.0 으로 점근 — 단정 금지). 남은 거리의 [reinforceFactor]
     * 만큼만 좁힌다. 반복해도 1.0 에 정확히 도달하지 않는다.
     */
    fun reinforced(reinforceFactor: Double = DEFAULT_REINFORCE_FACTOR): Confidence {
        require(reinforceFactor in 0.0..1.0) { "reinforceFactor 는 [0,1] 범위여야 한다" }
        val raised = value + (1.0 - value) * reinforceFactor
        return Confidence(raised.coerceIn(0.0, 1.0))
    }

    /**
     * 마지막 관찰([observedAt]) 이후 [now] 까지 경과로 감쇠한 신뢰(반감기 [halfLife]). 영구 진리로 굳지 않게 한다
     * (observable-state-policy 불변식 3). 음수 경과(시계 역행)는 감쇠하지 않는다.
     */
    fun decayed(
        observedAt: Instant,
        now: Instant,
        halfLife: Duration = DEFAULT_HALF_LIFE,
    ): Confidence {
        require(!halfLife.isZero && !halfLife.isNegative) { "halfLife 는 양수여야 한다" }
        val elapsed = Duration.between(observedAt, now)
        if (elapsed.isNegative || elapsed.isZero) return this
        val ratio = elapsed.toMillis().toDouble() / halfLife.toMillis().toDouble()
        val factor = Math.pow(0.5, ratio)
        return Confidence((value * factor).coerceIn(0.0, 1.0))
    }

    companion object {
        /** 확정으로 취급하는 신뢰 임계. GLM 단일 추출 기본값은 이 미만이라 확정이 되지 않는다(acceptance T010). */
        const val CERTAIN_THRESHOLD = 0.75
        private const val DEFAULT_REINFORCE_FACTOR = 0.3
        private val DEFAULT_HALF_LIFE: Duration = Duration.ofDays(30)

        /** 출처 종류의 기본 신뢰로 시작하는 [Confidence]. */
        fun forEvidence(evidence: MemoryEvidence): Confidence = Confidence(evidence.baseConfidence)
    }
}

/**
 * 기억의 **출처 종류**별 기본 신뢰(NEXA-P07-T010). 본인이 직접 한 명시적 행동일수록 높고, 모델 추론은 낮다
 * (observable-state-policy: 관찰 사실 > 추론). GLM 단일 추출은 [Confidence.CERTAIN_THRESHOLD] 미만이라 확정이 아니다.
 */
enum class MemoryEvidence(
    /** 이 출처 한 번 관찰의 기본 신뢰([0,1]). */
    val baseConfidence: Double,
) {
    /** 명시적 Discord 이벤트(본인이 직접 한 발화·반응·약속 등 관찰된 행동). 가장 강함. */
    EXPLICIT_DISCORD_EVENT(0.8),

    /** 여러 차례 반복 언급된 정황(누적 관찰). 명시 단일보다는 낮게 시작해 reinforced 로 누적. */
    REPEATED_MENTION(0.6),

    /** GLM(LLM) 한 번의 추출. 약한 근거 — 단독으로 확정 사실이 되지 않는다(acceptance T010). */
    GLM_EXTRACTION(0.4),
}
