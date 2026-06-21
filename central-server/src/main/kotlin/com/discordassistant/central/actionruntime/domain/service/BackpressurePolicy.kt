package com.discordassistant.central.actionruntime.domain.service

import java.time.Duration

/**
 * Discord rate-limit·backpressure **순수 결정 코어**(NEXA-P13-T021, 순수 도메인 서비스).
 *
 * 사회적 응답은 **타이밍이 본질**이다 — rate-limit(429)·queue 포화로 너무 오래 지연되면, 뒤늦게 쏟아내는 것이
 * 침묵보다 부자연스럽다. 이 정책은 두 가지를 결정한다:
 *
 * 1. **staleness drop**([isTooStale]): 발사 예정 시점 대비 [staleness] 가 [maxStaleness] 를 넘으면 전송하지 않고
 *    취소한다(acceptance T021: "오래 지연된 사회적 응답을 뒤늦게 쏟아내지 않는다").
 * 2. **429 backoff 존중**([acceptableBackoff]): rate-limit 이 권고한 [retryAfter] 가 staleness 예산 안이면 그만큼만
 *    기다려 1회 재시도하고, 예산을 넘으면 무한 재시도·spam 대신 취소한다(무한 재시도·spam 금지).
 *
 * 블로킹 없음 — 이 객체는 sleep 하지 않는다. 상대 시간(Duration)만 판정한다(scheduler 가 시각으로 환산).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만(actionruntime.domain 규칙).
 */
class BackpressurePolicy(
    /** 사회적 응답이 "더는 자연스럽지 않은" 지연 상한. 이 이상 지연된 응답은 전송 대신 취소(뒤늦은 쏟아냄 방지). */
    private val maxStaleness: Duration = DEFAULT_MAX_STALENESS,
) {
    init {
        require(!maxStaleness.isNegative && !maxStaleness.isZero) {
            "maxStaleness 는 양수여야 한다: $maxStaleness"
        }
    }

    /**
     * 발사 예정 시점 대비 [staleness](현재까지 누적 지연)가 상한을 넘으면 true — 전송하지 않고 취소해야 한다.
     */
    fun isTooStale(staleness: Duration): Boolean = staleness > maxStaleness

    /**
     * rate-limit 이 권고한 [retryAfter] 만큼 기다린 **뒤** 의 누적 지연([currentStaleness] + [retryAfter])이 staleness
     * 예산 안이면 true(그만큼 기다려 1회 재시도해도 좋음). 예산을 넘으면 false — 무한 재시도·spam 대신 취소한다.
     */
    fun acceptableBackoff(
        currentStaleness: Duration,
        retryAfter: Duration,
    ): Boolean {
        if (retryAfter.isNegative) return false
        return currentStaleness.plus(retryAfter) <= maxStaleness
    }

    companion object {
        /** 기본 staleness 상한 — 이보다 더 지연된 사회적 응답은 뒤늦게 보내지 않는다(자연스러움 우선). */
        val DEFAULT_MAX_STALENESS: Duration = Duration.ofSeconds(60)
    }
}
