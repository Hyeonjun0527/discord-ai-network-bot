package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 정책 action 분포 metric(NEXA-P18-T004). 정책이 낸 raw action 과 하드 제약 적용 후 final action 의 분포를 **원문
 * 없이** 집계한다. 호출자(participation application)가 도메인 결정을 저카디널리티 코드([NexaActionLabel])로 풀어
 * 넘긴다(BurstSegmentationMetrics 와 같은 경계 규칙 — 도메인 타입 미import).
 *
 * **acceptance(T004) — constraint 가 action 을 변경한 비율이 별도 지표다**:
 *  - [recordDecision] 은 raw·final 분포를 `nexa_policy_action_total{kind, stage}` 로 따로 센다(stage=raw/final).
 *  - raw≠final 이면 `nexa_policy_constraint_overridden_total` 을 올린다 — override 비율 = 이 카운터 /
 *    `nexa_policy_action_total{stage=raw}` 합. 분포와 독립된 별도 지표다.
 *
 * label 은 저카디널리티 kind(고정 5 enum)·stage(raw/final)뿐 — guild/channel/user ID 를 label 로 노출하지 않는다.
 */
@Component
class PolicyActionMetrics(
    private val meter: MeterRegistry,
) {
    /**
     * 한 결정의 [rawKind](제약 전)·[finalKind](제약 후)를 기록한다. 둘이 다르면 constraint override 1건도 센다.
     */
    fun recordDecision(
        rawKind: NexaActionLabel,
        finalKind: NexaActionLabel,
    ) {
        meter.counter("nexa_policy_action_total", "kind", rawKind.label, "stage", "raw").increment()
        meter.counter("nexa_policy_action_total", "kind", finalKind.label, "stage", "final").increment()
        if (rawKind != finalKind) {
            meter.counter("nexa_policy_constraint_overridden_total").increment()
        }
    }
}

/**
 * 정책 action 분포 metric label(저카디널리티 enum). participation SocialActionKind 와 1:1 대응하되 관측 레이어는
 * 도메인 enum 을 import 하지 않으려고 별도 label enum 을 둔다(순수성·결합 최소화, BurstTerminationOutcome 패턴).
 */
enum class NexaActionLabel(
    val label: String,
) {
    IGNORE("ignore"),
    WAIT("wait"),
    REACT("react"),
    SPEAK("speak"),
    CANCEL_PENDING("cancel_pending"),
}
