package com.discordassistant.central.quota.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * /질문 의 **무료 클라우드 폴백**(로컬 프로바이더 부재 시 관리자 클라우드 AI 키로 서버 전체 무료 제공)의
 * **인당 rate limit**. 무료 자원 남용을 막기 위해 사용자별로 시간당·일일 횟수를 제한한다(기본: 1시간 30회, 하루 100회).
 *
 * 일반 요청자 일일 쿼터([QuotaService], 길드 단위 기본 20/일)와는 **별개**다 — 클라우드 폴백은 외부 클라우드
 * 비용/할당을 쓰므로 더 촘촘한 시간당 제한까지 둔다. 카운터는 [RateLimitStore](기본 인메모리, Redis 분산
 * 가능)에 위임 — 기존 [RateLimiter] 와 동일 인프라. (설정 키는 호환을 위해 central.freeask.* 유지)
 */
@Component
class FreeAskRateLimiter(
    private val store: RateLimitStore,
    @param:Value("\${central.freeask.per-hour:30}") private val perHour: Int,
    @param:Value("\${central.freeask.per-day:100}") private val perDay: Int,
) {
    /**
     * 이 사용자가 지금 무료질문을 쓸 수 있는지 검사하고 **소비**한다. null=허용, 비-null=거부 사유 문구.
     *
     * 일일 한도는 먼저 **소비 없이 선검사(peek)** 한다 — 일일 초과가 확정이면 시간당 토큰을 헛되이
     * 소비하지 않게. 그 다음 시간당을 소비하고, 마지막에 일일을 소비한다. 이렇게 하면 시간당/일일 어느
     * 쪽이 거부되든 다른 쪽 카운터를 낭비하지 않으면서 두 한도를 모두 강제한다.
     * perHour/perDay 0 이하는 무제한(해당 한도 미적용).
     */
    fun check(userId: Long): String? {
        if (perDay > 0 && !store.peek("freeask:d:$userId", perDay, 86_400)) {
            return "무료질문은 하루에 ${perDay}번까지 쓸 수 있어요. 내일 다시 시도하세요."
        }
        if (perHour > 0 && !store.tryAcquire("freeask:h:$userId", perHour, 3600)) {
            return "무료질문은 1시간에 ${perHour}번까지 쓸 수 있어요. 잠시 후 다시 시도하세요."
        }
        if (perDay > 0 && !store.tryAcquire("freeask:d:$userId", perDay, 86_400)) {
            return "무료질문은 하루에 ${perDay}번까지 쓸 수 있어요. 내일 다시 시도하세요."
        }
        return null
    }
}
