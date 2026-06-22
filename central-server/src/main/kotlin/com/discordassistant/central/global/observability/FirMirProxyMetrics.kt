package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * FIR/MIR proxy 운영 metric(NEXA-P18-T008). shadow/canary outcome 에서 **지연 집계한** false interruption(과반응)·
 * missed intervention(과침묵) proxy 를 운영 경보용 집계로 노출한다. 입력은 participation evaluation 의
 * [com.discordassistant.central.participation.application.evaluation.InterventionProxyRates](counterfactual 관찰로
 * 계산된 집계 비율)를 원시 카운트/비율로 푼 값이다.
 *
 * **acceptance(T008) — proxy 임을 dashboard 에 명시하고 사용자 심리를 사실로 표시하지 않는다**:
 *  - metric 이름에 `proxy` 를 명시한다(`nexa_fir_proxy_*`/`nexa_mir_proxy_*`) — 사실(fact)이 아니라 약한 안전
 *    신호임을 이름으로 드러낸다. dashboard(T010)는 이 metric 을 "proxy(추정)" 배지로 표시한다.
 *  - **집계만** 노출한다 — 개별 사용자 심리·개별 표본을 metric 으로 내보내지 않는다(원시 비율·카운트뿐).
 *
 * proxy 비율은 마지막 지연 집계 batch 값을 들고 있는 gauge(AtomicReference), 누적 카운트는 counter 다.
 */
@Component
class FirMirProxyMetrics(
    private val meter: MeterRegistry,
) {
    private val firRate = AtomicReference(0.0)
    private val mirRate = AtomicReference(0.0)

    init {
        meter.gauge("nexa_fir_proxy_rate", firRate) { it.get() }
        meter.gauge("nexa_mir_proxy_rate", mirRate) { it.get() }
    }

    /**
     * 한 지연 집계 batch 의 proxy 결과를 기록한다. [sampleCount] 는 분모(표본 수), [falseInterruptionCount]·
     * [missedInterventionCount] 는 proxy 참 건수다. 비율 gauge 는 표본 0 이면 0(미정의 — 단정 금지).
     */
    fun recordBatch(
        sampleCount: Long,
        falseInterruptionCount: Long,
        missedInterventionCount: Long,
    ) {
        require(sampleCount >= 0 && falseInterruptionCount >= 0 && missedInterventionCount >= 0) {
            "proxy 카운트는 음수일 수 없다"
        }
        require(falseInterruptionCount <= sampleCount && missedInterventionCount <= sampleCount) {
            "proxy 참 건수는 표본 수를 넘을 수 없다"
        }
        meter.counter("nexa_fir_proxy_total").increment(falseInterruptionCount.toDouble())
        meter.counter("nexa_mir_proxy_total").increment(missedInterventionCount.toDouble())
        meter.counter("nexa_proxy_sample_total").increment(sampleCount.toDouble())
        firRate.set(rate(falseInterruptionCount, sampleCount))
        mirRate.set(rate(missedInterventionCount, sampleCount))
    }

    /** 마지막 batch 의 FIR proxy 비율(테스트·내부 조회용). */
    fun falseInterruptionRate(): Double = firRate.get()

    /** 마지막 batch 의 MIR proxy 비율(테스트·내부 조회용). */
    fun missedInterventionRate(): Double = mirRate.get()

    private fun rate(
        part: Long,
        total: Long,
    ): Double = if (total <= 0L) 0.0 else part.toDouble() / total.toDouble()
}
