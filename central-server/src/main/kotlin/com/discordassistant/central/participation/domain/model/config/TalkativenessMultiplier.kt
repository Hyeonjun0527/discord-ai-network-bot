package com.discordassistant.central.participation.domain.model.config

/**
 * talkativeness multiplier 계약(NEXA-P08-T017, 순수 도메인 값 객체·불변).
 *
 * 서버(채널)별로 NEXA 가 **얼마나 말이 많은지** 를 조절하는 단일 스칼라다. 허용 범위는 [0.0, 2.0] 이고, **1.5 는
 * 기본값 후보**일 뿐 최종 기본값은 인간 승인(human gate)으로 둔다([DEFAULT_CANDIDATE], [defaultPendingApproval]).
 *
 * **acceptance(T017) — 단순 메시지 개수 곱이 아니라 speak hazard/logit 보정에만 쓰이도록 문서화된다**:
 * 이 값은 "최근 메시지 수 × multiplier" 같은 **카운트 곱이 아니다**. participation 의 SPEAK 결정 단계에서
 * **speak hazard(발화 위험률) 또는 logit 에 가산/배율 보정** 으로만 쓰인다([applyToSpeakLogit]). 즉 모델이 낸
 * SPEAK 의 로그-오즈를 보정해 발화 빈도를 미세 조정할 뿐, 발화 횟수를 직접 곱해 늘리지 않는다. 이 사용처 제약은
 * 타입으로 강제할 수 없어 [applyToSpeakLogit] 단일 진입점으로만 노출하고(다른 곱셈 헬퍼 미제공) 문서로 못박는다.
 *
 * - multiplier 1.0 = 보정 없음(모델 raw 그대로).
 * - multiplier > 1.0 = 발화 쪽으로 logit 가산(더 말이 많아짐), < 1.0 = 침묵 쪽(덜 말함).
 * - multiplier 0.0 = 강한 침묵 편향(하드 mute 가 아님 — 그건 [com.discordassistant.central.participation.application.feature.EligibilityMask]).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 kotlin.math 만 쓴다.
 */
@JvmInline
value class TalkativenessMultiplier(
    val value: Double,
) {
    init {
        require(value in MIN..MAX) { "talkativeness multiplier 는 [$MIN, $MAX] 범위여야 한다: $value" }
    }

    /**
     * SPEAK logit(로그-오즈) 보정. **메시지 개수 곱이 아니라** logit 에 가산 보정을 적용한다(acceptance T017).
     * multiplier 1.0 이면 [speakLogit] 그대로, >1 이면 발화 쪽으로(양수 가산), <1 이면 침묵 쪽으로(음수 가산).
     *
     * 보정량 = ln(multiplier) — multiplier 가 오즈 배율이 되도록(곱이 logit 가산으로 변환). multiplier 0 이면
     * 강한 음의 보정([MIN_LOGIT_ADJUSTMENT] 로 클램프)으로 침묵 편향(하드 차단은 mask 책임).
     */
    fun applyToSpeakLogit(speakLogit: Double): Double = speakLogit + logitAdjustment()

    /** 이 multiplier 의 logit 가산 보정량(ln 배율). 0 이면 강한 음수로 클램프. */
    fun logitAdjustment(): Double {
        if (value <= 0.0) return MIN_LOGIT_ADJUSTMENT
        return kotlin.math.ln(value).coerceAtLeast(MIN_LOGIT_ADJUSTMENT)
    }

    companion object {
        /** 허용 하한(강한 침묵 편향). */
        const val MIN: Double = 0.0

        /** 허용 상한. */
        const val MAX: Double = 2.0

        /** 보정 없음(모델 raw 그대로). */
        val NEUTRAL: TalkativenessMultiplier = TalkativenessMultiplier(1.0)

        /**
         * 기본값 **후보** 1.5(최종 기본값은 인간 승인으로 둔다 — T017 human gate). 코드 기본값으로 자동 채택하지 말 것.
         * 운영 기본은 [defaultPendingApproval] 로만 접근해, "아직 승인되지 않은 후보" 임을 호출부에 드러낸다.
         */
        const val DEFAULT_CANDIDATE: Double = 1.5

        /** multiplier 0 의 logit 보정 하한(강한 침묵 편향, -∞ 방지). */
        const val MIN_LOGIT_ADJUSTMENT: Double = -10.0

        /**
         * 인간 승인 전 잠정 기본값([DEFAULT_CANDIDATE]). 이름으로 "승인 대기" 임을 드러낸다 — 최종 기본값 확정 전까지
         * 운영 코드가 무심코 1.5 를 영구 기본으로 굳히지 않도록 한다(T017 human gate).
         */
        fun defaultPendingApproval(): TalkativenessMultiplier = TalkativenessMultiplier(DEFAULT_CANDIDATE)
    }
}
