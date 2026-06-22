package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 버스트 분할 관측 메트릭(NEXA-P04-T024). 한 버스트가 finalize 될 때 평균 fragment 수·gap·종료 이유·교정 비율을
 * **원문 없이** 집계 메트릭으로 노출한다(`/actuator/prometheus`). conversation 도메인 순수성을 깨지 않도록 이
 * 클래스는 도메인 타입을 import 하지 않고 **원시값**(개수·밀리초·이유 라벨)만 받는다 — 호출자(conversation
 * application/adapter)가 도메인 결정을 원시값으로 풀어 넘긴다.
 *
 * **acceptance(T024) — 고카디널리티 ID 비노출**: guild/channel/burst/message ID 를 metric label 로 직접 노출하지
 * 않는다. label 은 저카디널리티 [BurstTerminationOutcome](종료 이유, 고정 enum 집합)뿐이다. fragment 수·gap·교정은
 * label 없는 분포/카운터로만 집계한다 — 라벨 폭발과 원문 유출을 동시에 막는다.
 */
@Component
class BurstSegmentationMetrics(
    private val meter: MeterRegistry,
) {
    /**
     * 한 버스트 finalize 를 기록한다. [fragmentCount] 는 그 버스트의 조각 수, [gapMillis] 는 마지막 조각과 종료
     * deadline(또는 다음 경계) 사이 effective gap(ms), [reason] 은 저카디널리티 종료 이유 라벨이다.
     * 원문·식별자는 받지 않는다(PII 비유출).
     */
    fun recordFinalized(
        fragmentCount: Int,
        gapMillis: Long,
        reason: BurstTerminationOutcome,
    ) {
        require(fragmentCount >= 1) { "버스트는 최소 1개 조각을 가진다" }
        meter.counter("nexa_burst_finalized_total", "reason", reason.label).increment()
        // 평균 fragment 수·gap 은 label 없는 분포로 집계 — 고카디널리티 ID 를 라벨로 쓰지 않는다.
        meter.summary("nexa_burst_fragment_count").record(fragmentCount.toDouble())
        meter.summary("nexa_burst_gap_millis").record(gapMillis.toDouble())
    }

    /**
     * 버스트 교정(편집/삭제로 finalize 후 정정)을 1건 기록한다. correction rate 는 소비자가
     * `nexa_burst_corrected_total / nexa_burst_finalized_total` 로 유도한다(원문·ID 없이 비율만).
     */
    fun recordCorrection() {
        meter.counter("nexa_burst_corrected_total").increment()
    }
}

/**
 * 메트릭 label 로 쓰는 저카디널리티 버스트 종료 이유. conversation 도메인의 BurstTerminationReason 와 1:1 대응하되,
 * 관측 레이어는 도메인 enum 을 import 하지 않으려고 별도 label enum 을 둔다(순수성·결합 최소화). 값 집합이 고정이라
 * label 카디널리티가 폭발하지 않는다.
 */
enum class BurstTerminationOutcome(
    val label: String,
) {
    GAP_ELAPSED("gap_elapsed"),
    OTHER_AUTHOR_INTRUSION("other_author_intrusion"),
    CONTEXT_SWITCH("context_switch"),
    STREAM_END("stream_end"),
}
