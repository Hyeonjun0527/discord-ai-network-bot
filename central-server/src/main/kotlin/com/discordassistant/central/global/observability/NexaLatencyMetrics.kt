package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 정책·GLM latency metric(NEXA-P18-T006). 한 발화 흐름의 지연을 **단계별로 분리**해 label 없는 분포(summary)로
 * 집계한다. 단계가 섞이지 않게 각 단계가 별도 metric 이다.
 *
 * **acceptance(T006) — 취소된 action 도 취소까지 걸린 시간이 기록된다**:
 *  - policy inference / schedule wait / generation / first bubble / last bubble 을 각각 분리 기록한다.
 *  - [recordCancelled] 는 예약~취소까지 걸린 시간을 **별도 분포**로 남긴다 — 취소돼 전송되지 않은 흐름도 latency 가
 *    관측된다.
 *
 * 모든 metric 은 label 없는 summary — guild/channel/user ID 를 label 로 노출하지 않는다(고카디널리티 회피).
 */
@Component
class NexaLatencyMetrics(
    private val meter: MeterRegistry,
) {
    /** 정책 추론 지연(ms) — 입력 feature 준비 후 결정까지. */
    fun recordPolicyInference(millis: Long) = record("nexa_latency_policy_inference_millis", millis)

    /** 스케줄 대기 지연(ms) — 예약 후 due 도래까지(사람다운 지연 포함). */
    fun recordScheduleWait(millis: Long) = record("nexa_latency_schedule_wait_millis", millis)

    /** 생성 지연(ms) — GLM 요청~응답 완료. */
    fun recordGeneration(millis: Long) = record("nexa_latency_generation_millis", millis)

    /** 첫 bubble 지연(ms) — 흐름 시작~첫 메시지 조각 전송. */
    fun recordFirstBubble(millis: Long) = record("nexa_latency_first_bubble_millis", millis)

    /** 마지막 bubble 지연(ms) — 흐름 시작~마지막 메시지 조각 전송. */
    fun recordLastBubble(millis: Long) = record("nexa_latency_last_bubble_millis", millis)

    /**
     * 취소된 action 의 예약~취소 지연(ms)을 별도 분포로 기록한다(acceptance T006 — 취소도 시간이 남는다).
     */
    fun recordCancelled(millis: Long) = record("nexa_latency_cancelled_millis", millis)

    private fun record(
        name: String,
        millis: Long,
    ) {
        require(millis >= 0) { "latency 는 음수일 수 없다" }
        meter.summary(name).record(millis.toDouble())
    }
}
