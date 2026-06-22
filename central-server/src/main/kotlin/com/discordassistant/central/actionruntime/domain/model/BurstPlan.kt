package com.discordassistant.central.actionruntime.domain.model

import java.time.Duration

/**
 * 멀티 응답 **버스트 전송 계획**(NEXA-P13-T019, 순수 도메인 값 객체·불변).
 *
 * 사람이 한 생각을 여러 짧은 메시지(버블)로 끊어 보내는 것을 흉내낸다(P04 버스트/P12 간격 모델). 각 [bubbles] 는
 * 순차 전송할 한 조각이고, [gapAfter] 는 다음 버블까지의 상대 간격(블로킹 없음 — scheduler 가 시각으로 환산).
 * actionruntime 의 executor 가 이 계획대로 버블을 순서대로 전송한다(T017). 도중 취소되면 **남은 버블은 보내지
 * 않는다**(T020).
 *
 * **acceptance(T019) — 기존 pseudo-streaming API 가 정책 action 수를 늘리지 않는다**:
 * - 기존 multiresponse 의 의사-스트리밍은 **한 메시지를 제자리 편집**(action 1개)이다. 이를 버블 N개로 펼치면 안
 *   된다 — adapter 는 의사-스트림을 **버블 1개**(최종 본문)로 매핑한다. [bubbleCount] 가 전송 action 수다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만(actionruntime.domain 규칙).
 */
data class BurstPlan(
    /** 순차 전송할 버블들(최소 1개). 각 버블은 한 번의 전송 action. */
    val bubbles: List<Bubble>,
) {
    init {
        require(bubbles.isNotEmpty()) { "BurstPlan 은 최소 1개의 버블을 가져야 한다" }
    }

    /** 전송 action 수(= 버블 수). 기존 pseudo-stream 매핑은 이 값을 1로 유지한다(action 수 증가 금지 — T019). */
    val bubbleCount: Int
        get() = bubbles.size

    /** 멀티 버블인가(2개 이상) — 버스트 간격이 의미를 갖는 경우. */
    val isMultiBubble: Boolean
        get() = bubbles.size > 1

    /** 모든 버블을 보내는 데 걸리는 총 추정 span(typing maxDuration cap 계산용 — P12 TypingPlan.burstSpan). */
    val totalSpan: Duration
        get() = bubbles.fold(Duration.ZERO) { acc, b -> acc.plus(b.gapAfter) }

    companion object {
        /**
         * 단일 버블 계획(기존 의사-스트리밍·즉답 매핑 — action 1개). [speechPlanRef] 는 speech 가 만든 본문 참조.
         * acceptance(T019): 의사-스트림은 이 단일 버블로 매핑돼 정책 action 수를 늘리지 않는다.
         */
        fun single(speechPlanRef: String): BurstPlan =
            BurstPlan(listOf(Bubble(index = 0, speechPlanRef = speechPlanRef, gapAfter = Duration.ZERO)))
    }
}

/**
 * 버스트의 한 조각(NEXA-P13-T019, 순수 도메인 값 객체·불변). [index] 는 0-기반 순서(부분 전송 추적·재구성 키 —
 * T020), [speechPlanRef] 는 본문 참조(원문 비포함), [gapAfter] 는 다음 버블까지 상대 간격(마지막 버블은 ZERO).
 */
data class Bubble(
    val index: Int,
    val speechPlanRef: String,
    val gapAfter: Duration = Duration.ZERO,
) {
    init {
        require(index >= 0) { "버블 index 는 음수일 수 없다: $index" }
        require(speechPlanRef.isNotBlank()) { "speechPlanRef 는 비어 있을 수 없다" }
        require(!gapAfter.isNegative) { "gapAfter 는 음수일 수 없다: $gapAfter" }
    }
}
