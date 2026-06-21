package com.discordassistant.central.participation.domain.model.decision

import java.time.Duration

/**
 * "입력 중"(typing indicator) 타이밍 계획(NEXA-P12-T014, 순수 도메인 값 객체·불변).
 *
 * 정책이 SPEAK 를 결정했을 때, NEXA 가 발화 **전에** 언제부터 "입력 중" 을 띄우고 최대 얼마나 유지할지를
 * **scheduler 계획**(상대 offset·상한)으로 표현한다. 사람은 답을 보내기 직전 잠깐 "입력 중" 으로 보인다 —
 * 이를 흉내내되 **블로킹 sleep 을 쓰지 않는다**(actionruntime 이 이 계획대로 typing 시작/종료를 예약).
 *
 * **acceptance(T014) — 실제 답변이 취소되면 typing 도 종료되고 무한 typing 이 없다**:
 * - [maxDuration]: typing 유지 상한(필수·양수). actionruntime 이 이 상한을 넘겨 typing 을 끌지 않는다 —
 *   서버 응답이 끝내 안 와도 [startOffset]+[maxDuration] 시점에 typing 이 **반드시 종료**된다(무한 typing 방지).
 * - 발화가 취소(CancelPending)/실패하면 호출자가 [maxDuration] 도래 전이라도 typing 을 끝낸다(이 계획은 상한일
 *   뿐, 조기 종료를 막지 않는다). 즉 typing 종료는 "발화 도착 OR 취소 OR maxDuration 도래" 중 가장 이른 시점.
 * - **블로킹 없음**: 이 객체는 sleep 하지 않는다. [startOffset]/[maxDuration] 은 actionruntime scheduler 가
 *   읽는 상대 시간 값일 뿐이다(랜덤 sleep 금지 — burst_timing scheduler 계획과 동일 원칙).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다(participation.domain 규칙).
 */
data class TypingPlan(
    /** 행동 발사 기준 시각으로부터 "입력 중" 을 **시작** 하기까지의 상대 offset(음수 금지). */
    val startOffset: Duration,
    /** "입력 중" 을 유지하는 **최대** 시간(양수 필수). 이 시점엔 응답이 없어도 typing 이 종료된다(무한 방지). */
    val maxDuration: Duration,
) {
    init {
        require(!startOffset.isNegative) { "startOffset 은 음수일 수 없다: $startOffset" }
        require(!maxDuration.isNegative && !maxDuration.isZero) {
            "maxDuration 은 양수여야 한다(무한 typing 방지): $maxDuration"
        }
        require(maxDuration <= MAX_TYPING_DURATION) {
            "maxDuration 은 상한 $MAX_TYPING_DURATION 을 넘을 수 없다(무한 typing 방지): $maxDuration"
        }
    }

    /** typing 이 **반드시 종료** 되는 가장 늦은 상대 시각(startOffset + maxDuration). 무한 typing 방어선. */
    val mustEndBy: Duration
        get() = startOffset.plus(maxDuration)

    /**
     * 발화가 [elapsed] 시점에 도착/취소되면 typing 을 끝낼지 — 실제 종료는 "발화/취소" 와 [mustEndBy] 중
     * 더 이른 시점이다. [elapsed] 가 [mustEndBy] 이상이면 이미 상한으로 종료됐어야 함(무한 typing 없음).
     */
    fun isExpiredAt(elapsed: Duration): Boolean = elapsed >= mustEndBy

    companion object {
        /** typing 유지 절대 상한(무한 typing 구조적 방지). 사람 typing 텀의 현실적 최대치. */
        val MAX_TYPING_DURATION: Duration = Duration.ofSeconds(30)

        /** typing 없음(즉답·reaction-only 등 입력 중 표시가 불필요한 경우). */
        val NONE: TypingPlan? = null

        /**
         * SPEAK delay 와 burst 형태에서 typing 계획을 만든다. typing 은 발사 직전 [leadTime] 동안만 보이게
         * 시작 offset 을 delay 끝에서 [leadTime] 앞당기고, 유지 상한은 [leadTime] 과 burst 총 추정 시간 중 큰
         * 값을 [MAX_TYPING_DURATION] 으로 cap 한다(무한 typing 방지). 블로킹 없음 — 상대 시간만 계산.
         */
        fun forSpeak(
            fireDelay: Duration,
            leadTime: Duration = Duration.ofSeconds(2),
            burstSpan: Duration = Duration.ZERO,
        ): TypingPlan {
            require(!fireDelay.isNegative) { "fireDelay 는 음수일 수 없다: $fireDelay" }
            require(!leadTime.isNegative && !leadTime.isZero) { "leadTime 은 양수여야 한다: $leadTime" }
            // typing 시작 = 발사 leadTime 전(발사 직전 잠깐만 입력 중). delay 가 leadTime 보다 짧으면 0 부터.
            val start = if (fireDelay > leadTime) fireDelay.minus(leadTime) else Duration.ZERO
            // 유지 상한 = leadTime + burst 총 추정(여러 조각 보낼 시간) — 단 절대 상한으로 cap.
            val rawMax = leadTime.plus(if (burstSpan.isNegative) Duration.ZERO else burstSpan)
            val capped = if (rawMax > MAX_TYPING_DURATION) MAX_TYPING_DURATION else rawMax
            return TypingPlan(startOffset = start, maxDuration = capped)
        }
    }
}
