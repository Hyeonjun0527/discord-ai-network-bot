package com.discordassistant.central.speech.adapter.outbound.routing

import java.time.Duration
import java.time.Instant

/**
 * 클라우드 모델 호출 timeout budget(NEXA-P14-T014, adapter/outbound/routing 값 객체·불변).
 *
 * action 의 executeAfter 와 stale deadline 을 고려한 **짧은 timeout** 을 표현한다.
 * (타입 이름에 provider 식별자를 넣지 않는다 — speech 는 provider-neutral 이어야 하며 ArchUnit 가 강제한다.)
 *
 * **acceptance(T014) — 늦게 도착한 응답이 현재 contextVersion 에 전송되지 않는다**: [isStale] 가 호출 직전·응답
 * 직후 deadline 을 검사한다. deadline 이 지난(또는 contextVersion 이 바뀐) 응답은 어댑터가 **버린다**(빈 결과로
 * 흡수) — 전송은 actionruntime 이 하므로 speech 가 stale 결과를 내보내지 않는 것이 1차 가드다.
 */
data class CloudCallBudget(
    /** 1회 호출 timeout(짧게 — 사람 응답 속도). */
    val perCallTimeout: Duration,
    /** 이 시각 이후 도착한 응답은 stale 로 폐기한다(action executeAfter + 여유). */
    val deadline: Instant,
) {
    init {
        require(!perCallTimeout.isNegative && !perCallTimeout.isZero) { "perCallTimeout 은 양수여야 한다" }
    }

    /** [now] 가 deadline 을 지났으면 stale(응답 폐기 대상). */
    fun isStale(now: Instant): Boolean = !now.isBefore(deadline)

    companion object {
        /**
         * [deadline] 까지의 짧은 기본 budget. timeout 은 남은 시간과 [defaultTimeout] 중 작은 값으로 둔다(deadline 을
         * 넘기는 timeout 금지).
         */
        fun until(
            now: Instant,
            deadline: Instant,
            defaultTimeout: Duration = Duration.ofSeconds(8),
        ): CloudCallBudget {
            val remaining = Duration.between(now, deadline)
            val timeout =
                if (remaining.isNegative || remaining.isZero) {
                    defaultTimeout
                } else {
                    minOf(defaultTimeout, remaining)
                }
            return CloudCallBudget(
                perCallTimeout = if (timeout.isZero || timeout.isNegative) defaultTimeout else timeout,
                deadline = deadline,
            )
        }
    }
}
