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
     * 시간 한도를 먼저 본다 — 흔한 거부 경로(시간당 초과)에서 일일 카운터를 불필요하게 소비하지 않게.
     * perHour/perDay 0 이하는 무제한(해당 한도 미적용).
     */
    fun check(userId: Long): String? {
        if (perHour > 0 && !store.tryAcquire("freeask:h:$userId", perHour, 3600)) {
            return "무료질문은 1시간에 ${perHour}번까지 쓸 수 있어요. 잠시 후 다시 시도하세요."
        }
        if (perDay > 0 && !store.tryAcquire("freeask:d:$userId", perDay, 86_400)) {
            return "무료질문은 하루에 ${perDay}번까지 쓸 수 있어요. 내일 다시 시도하세요."
        }
        return null
    }
}
