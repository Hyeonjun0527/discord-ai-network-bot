package com.discordassistant.central.participation.application.evaluation

import kotlin.math.sqrt

/**
 * SPEAK probability 의 **Brier score 와 calibration(ECE)** 계산기(NEXA-P09-T017, application 레이어).
 *
 * 각 표본은 정책이 낸 SPEAK 확률([CalibrationSample.predictedSpeakProbability])과 관찰 label(weak SPEAK/SILENT —
 * [SpeakLabel])의 쌍이다. 이 둘로 예측의 **정확도(Brier)** 와 **보정(ECE)** 를 잰다.
 *
 * **acceptance(T017) — 표본 수와 bin confidence interval 이 함께 나온다**:
 * - [CalibrationReport.sampleCount] 가 전체 표본 수.
 * - 각 [CalibrationBin] 은 그 bin 의 표본 수와 관찰 발화율의 **Wald 95% 신뢰구간**([CalibrationBin.confidenceLow]
 *   /[CalibrationBin.confidenceHigh])을 함께 가진다 — 한 점 추정에 속지 않게 표본 적은 bin 의 불확실성을 드러낸다.
 *
 * **결정론·재현(제약)**: 같은 표본이면 항상 같은 수치(순수 함수·랜덤 없음). 도메인 순수성: application — 표준
 * kotlin.math 만. Spring/JPA/JDA 미참조.
 */
object CalibrationMetrics {
    /** 기본 calibration bin 수(10 bins = [0,0.1),…,[0.9,1.0]). */
    const val DEFAULT_BINS: Int = 10

    /** Wald 95% 신뢰구간 z 값(정규근사). */
    private const val Z_95: Double = 1.96

    /**
     * 표본들로 Brier score·ECE·bin 별 신뢰구간을 계산한다. [bins] 는 calibration bin 수(기본 10).
     * 표본이 비면 sampleCount=0, brierScore=null, ece=null, bins=빈 리스트(분모 없음 — 단정 금지).
     */
    fun compute(
        samples: List<CalibrationSample>,
        bins: Int = DEFAULT_BINS,
    ): CalibrationReport {
        require(bins >= 1) { "bins 는 1 이상이어야 한다: $bins" }
        if (samples.isEmpty()) {
            return CalibrationReport(sampleCount = 0, brierScore = null, ece = null, bins = emptyList())
        }
        val n = samples.size
        // Brier = mean((p - y)^2), y = SPEAK?1:0.
        val brier = samples.sumOf { s -> square(s.predictedSpeakProbability - s.outcome) } / n

        val binResults = buildBins(samples, bins)
        // ECE = Σ (bin 표본수/N) · |bin 평균확률 − bin 관찰발화율|.
        val ece = binResults.sumOf { b -> (b.sampleCount.toDouble() / n) * kotlin.math.abs(b.meanPredicted - b.observedRate) }
        return CalibrationReport(sampleCount = n, brierScore = brier, ece = ece, bins = binResults)
    }

    private fun buildBins(
        samples: List<CalibrationSample>,
        bins: Int,
    ): List<CalibrationBin> {
        val grouped = samples.groupBy { binIndexOf(it.predictedSpeakProbability, bins) }
        return (0 until bins).mapNotNull { idx ->
            val members = grouped[idx] ?: return@mapNotNull null
            val count = members.size
            val meanPredicted = members.sumOf { it.predictedSpeakProbability } / count
            val observedRate = members.sumOf { it.outcome } / count
            val (lo, hi) = waldInterval(observedRate, count)
            CalibrationBin(
                lowerBound = idx.toDouble() / bins,
                upperBound = (idx + 1).toDouble() / bins,
                sampleCount = count,
                meanPredicted = meanPredicted,
                observedRate = observedRate,
                confidenceLow = lo,
                confidenceHigh = hi,
            )
        }
    }

    /** 확률 p 가 속한 bin index(상한 1.0 은 마지막 bin 에 포함). */
    private fun binIndexOf(
        p: Double,
        bins: Int,
    ): Int {
        val clamped = p.coerceIn(0.0, 1.0)
        val idx = (clamped * bins).toInt()
        return if (idx >= bins) bins - 1 else idx
    }

    /** 관찰 발화율 [rate] 의 Wald 95% 신뢰구간([0,1] 로 클램프). 표본 1 이어도 정의(폭이 넓게 나옴). */
    private fun waldInterval(
        rate: Double,
        count: Int,
    ): Pair<Double, Double> {
        val se = sqrt(rate * (1 - rate) / count)
        val half = Z_95 * se
        return (rate - half).coerceIn(0.0, 1.0) to (rate + half).coerceIn(0.0, 1.0)
    }

    private fun square(x: Double): Double = x * x

    /** SPEAK label → outcome 수치(SPEAK=1.0, SILENT=0.0). */
    private val CalibrationSample.outcome: Double
        get() = if (label == SpeakLabel.SPEAK) 1.0 else 0.0
}

/**
 * calibration 표본(application 값 객체). 정책이 낸 SPEAK 확률과 관찰 label 의 쌍. weak label([SpeakLabel])을
 * 정답이 아니라 약한 관찰 신호로 쓴다(T014 와 일관).
 */
data class CalibrationSample(
    /** 정책이 이 장면에서 낸 SPEAK 확률 [0,1]. */
    val predictedSpeakProbability: Double,
    /** 관찰된 weak label(SPEAK/SILENT). */
    val label: SpeakLabel,
) {
    init {
        require(predictedSpeakProbability in 0.0..1.0) {
            "predictedSpeakProbability 는 [0,1] 이어야 한다: $predictedSpeakProbability"
        }
    }
}

/**
 * calibration 리포트(application 값 객체). Brier·ECE 와 bin 별 신뢰구간·표본 수(acceptance T017). 집계 수치만 —
 * 원문/개별 사용자 비포함.
 */
data class CalibrationReport(
    /** 전체 표본 수(분모). */
    val sampleCount: Int,
    /** Brier score = mean((p−y)^2). 낮을수록 정확. 표본 0 이면 null. */
    val brierScore: Double?,
    /** Expected Calibration Error. 0 에 가까울수록 잘 보정됨. 표본 0 이면 null. */
    val ece: Double?,
    /** calibration bin 별 결과(표본 있는 bin 만). */
    val bins: List<CalibrationBin>,
)

/**
 * calibration bin(application 값 객체). 예측 확률 구간 [lowerBound, upperBound) 의 평균 예측 확률 vs 관찰 발화율을
 * **표본 수·95% 신뢰구간**과 함께 담는다(acceptance T017: bin confidence interval).
 */
data class CalibrationBin(
    val lowerBound: Double,
    val upperBound: Double,
    /** 이 bin 의 표본 수. */
    val sampleCount: Int,
    /** 이 bin 표본들의 평균 예측 SPEAK 확률. */
    val meanPredicted: Double,
    /** 이 bin 표본들의 관찰 발화율(SPEAK label 비율). */
    val observedRate: Double,
    /** 관찰 발화율의 Wald 95% 신뢰구간 하한. */
    val confidenceLow: Double,
    /** 관찰 발화율의 Wald 95% 신뢰구간 상한. */
    val confidenceHigh: Double,
)
