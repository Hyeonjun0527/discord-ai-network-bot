package com.discordassistant.central.actionruntime.application.execution

import com.discordassistant.central.actionruntime.domain.service.BackpressurePolicy
import java.time.Duration

/**
 * rate-limit·backpressure 적용 게이트(NEXA-P13-T021, application 레이어).
 *
 * 순수 [BackpressurePolicy] 결정을 실행 흐름에 연결하는 얇은 협력자다([ActionExecutionService] 가 staleness drop·
 * 429 backoff 존중을 한 곳에서 묻는다 — DRY). 정책 자체는 도메인에, 적용은 application 에 둔다.
 *
 * 순수성 경계: application 레이어 — 도메인 서비스·표준 타입만. Spring/JPA/JDA 미참조.
 */
class BackpressureGate(
    private val policy: BackpressurePolicy = BackpressurePolicy(),
) {
    /** 누적 지연 [staleness] 가 상한을 넘어 전송 대신 취소해야 하는가(뒤늦은 쏟아냄 방지 — T021). */
    fun shouldDropForStaleness(staleness: Duration): Boolean = policy.isTooStale(staleness)

    /** 429 권고 [retryAfter] 를 [currentStaleness] 위에 더해도 예산 안이라 1회 존중·재시도해도 좋은가(아니면 취소). */
    fun acceptBackoff(
        currentStaleness: Duration,
        retryAfter: Duration,
    ): Boolean = policy.acceptableBackoff(currentStaleness, retryAfter)
}
