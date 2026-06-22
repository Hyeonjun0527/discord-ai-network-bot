package com.discordassistant.central.participation.domain.service

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.config.TalkativenessMultiplier
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution
import kotlin.math.exp
import kotlin.math.ln

/**
 * calibration modifier 인터페이스(NEXA-P08-T020, 순수 도메인 서비스). **서버별 multiplier** 와 **model calibration**
 * 을 분리해 모델 raw 분포([ActionDistribution.actionWeights])에 순서대로 적용한다.
 *
 * - **model calibration**(엔진 보정): temperature 로 분포를 평탄/첨예화한다(모델 over/under-confidence 교정).
 * - **서버 multiplier**(운영 보정): [TalkativenessMultiplier] 로 SPEAK 의 logit 만 보정한다(메시지 수 곱 아님 —
 *   T017 경계). 두 보정은 서로 다른 modifier 라 독립 적용·독립 기록된다.
 *
 * **acceptance(T020) — 하드 규칙이 모델 raw probability 를 덮어쓴 사실이 decision log 에 남는다**:
 * [calibrate] 는 보정된 분포뿐 아니라 **어떤 modifier 가 raw 를 어떻게 바꿨는지**([CalibrationRecord])를 함께
 * 돌려준다 — modelTemperature, talkativeness logit 보정량, raw vs calibrated SPEAK 확률. participation 결정 로그
 * (T022/T023)가 이 record 를 근거로 남겨, raw 모델 확률이 운영 규칙으로 덮였음을 사후 추적·재현할 수 있다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 kotlin.math 만 쓴다.
 */
object PolicyCalibration {
    /** temperature 가 1 에 충분히 가까우면 보정 없음으로 본다(부동소수 noise 무시). */
    private const val TEMPERATURE_EPSILON = 1e-9

    /**
     * model calibration(temperature)과 서버 multiplier 를 순서대로 적용해 보정된 분포와 [CalibrationRecord] 를
     * 돌려준다. [modelTemperature] > 0 필수(>1 평탄화, <1 첨예화, =1 보정 없음). [talkativeness] 는 SPEAK logit 만 보정.
     */
    fun calibrate(
        raw: ActionDistribution,
        modelTemperature: Double,
        talkativeness: TalkativenessMultiplier,
    ): CalibrationResult {
        require(modelTemperature > 0.0) { "modelTemperature 는 양수여야 한다: $modelTemperature" }

        val rawSpeak = raw.actionWeights[SocialActionKind.SPEAK] ?: 0.0

        // 1) model calibration: temperature 로 logit 평탄/첨예화.
        val tempered = applyTemperature(raw.actionWeights, modelTemperature)
        // 2) 서버 multiplier: SPEAK logit 만 보정(메시지 수 곱 아님).
        val adjusted = applyTalkativeness(tempered, talkativeness)

        val calibratedSpeak = adjusted[SocialActionKind.SPEAK] ?: 0.0
        val record =
            CalibrationRecord(
                modelTemperature = modelTemperature,
                talkativenessLogitAdjustment = talkativeness.logitAdjustment(),
                rawSpeakProbability = rawSpeak,
                calibratedSpeakProbability = calibratedSpeak,
            )
        return CalibrationResult(
            calibrated = raw.withActionWeights(adjusted),
            record = record,
        )
    }

    /** temperature scaling: logit /= T 후 softmax 재정규화(=1 이면 그대로). */
    private fun applyTemperature(
        weights: Map<SocialActionKind, Double>,
        temperature: Double,
    ): Map<SocialActionKind, Double> {
        if (kotlin.math.abs(temperature - 1.0) <= TEMPERATURE_EPSILON) return weights
        val logits = weights.mapValues { (_, p) -> safeLogit(p) / temperature }
        return softmax(logits)
    }

    /** SPEAK logit 만 talkativeness 로 보정 후 softmax 재정규화(다른 행동은 logit 불변). */
    private fun applyTalkativeness(
        weights: Map<SocialActionKind, Double>,
        talkativeness: TalkativenessMultiplier,
    ): Map<SocialActionKind, Double> {
        if (talkativeness == TalkativenessMultiplier.NEUTRAL) return weights
        val logits =
            weights.mapValues { (kind, p) ->
                val logit = safeLogit(p)
                if (kind == SocialActionKind.SPEAK) talkativeness.applyToSpeakLogit(logit) else logit
            }
        return softmax(logits)
    }

    /** 확률 → 로그(클램프로 ±∞ 방지). softmax 의 입력 logit 으로 쓴다(상대값이라 절대 0 점은 무관). */
    private fun safeLogit(p: Double): Double = ln(p.coerceIn(1e-12, 1.0))

    /** logit 맵 → softmax 확률(합 1.0). max 차감으로 overflow 방지. */
    private fun softmax(logits: Map<SocialActionKind, Double>): Map<SocialActionKind, Double> {
        val max = logits.values.maxOrNull() ?: 0.0
        val exps = logits.mapValues { (_, l) -> exp(l - max) }
        val sum = exps.values.sum()
        if (sum <= 0.0) return logits.mapValues { 1.0 / logits.size }
        return exps.mapValues { (_, e) -> e / sum }
    }
}

/**
 * calibration 결과(순수 도메인 값 객체). 보정된 분포와 그 근거 record 를 함께 운반한다(acceptance T020:
 * 덮어쓴 사실을 decision log 에 남기기 위함).
 */
data class CalibrationResult(
    /** 보정된 결정 분포(actionWeights 가 calibrate 됨). */
    val calibrated: ActionDistribution,
    /** 어떤 modifier 가 raw 를 어떻게 바꿨는지의 근거(decision log 용). */
    val record: CalibrationRecord,
)

/**
 * calibration 근거 record(순수 도메인 값 객체). raw 모델 확률이 운영 규칙으로 덮였음을 사후 추적·재현하게 한다
 * (acceptance T020). 원문 없음 — 수치 근거만.
 */
data class CalibrationRecord(
    /** 적용된 model temperature(=1 이면 보정 없음). */
    val modelTemperature: Double,
    /** 적용된 talkativeness 의 SPEAK logit 가산 보정량(0 이면 보정 없음). */
    val talkativenessLogitAdjustment: Double,
    /** 보정 전 raw SPEAK 확률. */
    val rawSpeakProbability: Double,
    /** 보정 후 SPEAK 확률(raw 와 다르면 운영 규칙이 덮어썼다는 증거). */
    val calibratedSpeakProbability: Double,
) {
    /** raw 와 calibrated SPEAK 확률이 유의미하게 다른가 — 덮어쓰기 발생 여부의 가드. */
    val overrodeRawProbability: Boolean
        get() = kotlin.math.abs(rawSpeakProbability - calibratedSpeakProbability) > 1e-9
}
