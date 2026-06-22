package com.discordassistant.central.actionruntime.support

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.ClaimedAction
import com.discordassistant.central.actionruntime.application.port.out.PendingActionPurgePort
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// NEXA-P13-T008 — Clock 기반 due-action 테스트 harness.
//
// acceptance(T008) — 실제 sleep 없이 시간 전진과 due claim 을 테스트한다. 실행 시간이 예약 delay 길이에 의존하지
// 않는다: MutableTestClock 으로 시계를 임의 전진(advance)하고, InMemoryActionScheduler 가 그 시계 기준으로 due 를
// 판정한다. 실제 Thread.sleep 이 전혀 없으므로 1 시간 뒤 예약이든 1 초 뒤 예약이든 테스트는 즉시 끝난다. 이 harness
// 는 어떤 단위/통합 테스트도 시간에 의존해 느려지지 않게 하는 공유 지원물이다.

/** 임의로 전진 가능한 테스트 시계(실제 sleep 없이 시간 제어 — Date.now 직접 금지 원칙과 일관). */
class MutableTestClock(
    private var instant: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun instant(): Instant = instant

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableTestClock(instant, zone)

    /** 시계를 [duration] 만큼 전진시킨다(실제 sleep 없음 — 예약 delay 만큼 실제로 기다리지 않는다). */
    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }

    /** 시계를 [target] 으로 설정한다. */
    fun setTo(target: Instant) {
        instant = target
    }
}

/**
 * [ActionSchedulerPort] + [PendingActionPurgePort] 의 in-memory 가짜 구현(테스트 전용). 실제 DB·lease 동시성은
 * [com.discordassistant.central.actionruntime.persistence] 의 @DataJpaTest 가 검증하고, 이 가짜는 application
 * 유스케이스(poller/recovery/consent)의 시간·분기 로직을 빠르게 검증한다.
 *
 * lease·만료 회수도 모델링해 재시작 복구(T010) 테스트가 실제 sleep 없이 동작한다.
 */
class InMemoryActionScheduler(
    private val clock: MutableTestClock,
) : ActionSchedulerPort,
    PendingActionPurgePort {
    private val store = linkedMapOf<String, ScheduledSocialAction>()
    private val leaseExpiry = mutableMapOf<String, Instant>()

    override fun schedule(action: ScheduledSocialAction): Boolean {
        if (store.containsKey(action.identity.value)) return false
        store[action.identity.value] = action.markScheduled()
        return true
    }

    override fun claimDue(
        now: Instant,
        leaseExpiresAt: Instant,
        limit: Int,
    ): List<ClaimedAction> =
        store.values
            .filter { it.status == ActionStatus.SCHEDULED && it.isDue(now) }
            .take(limit)
            .map { claimed ->
                val reeval = claimed.beginReevaluation()
                store[reeval.identity.value] = reeval
                leaseExpiry[reeval.identity.value] = leaseExpiresAt
                ClaimedAction(action = reeval, leaseExpiresAt = leaseExpiresAt)
            }

    override fun reclaimExpiredLeases(now: Instant): List<ActionIdentity> =
        leaseExpiry
            .filterValues { it.isBefore(now) }
            .keys
            .toList()
            .also { keys -> keys.forEach { leaseExpiry.remove(it) } }
            .map { ActionIdentity.of(store.getValue(it).decisionId, it.substringAfterLast('#').toInt()) }

    override fun reschedule(
        identity: ActionIdentity,
        executeAfter: Instant,
        attempt: Int,
    ) {
        store[identity.value]?.let {
            store[identity.value] = it.copy(status = ActionStatus.SCHEDULED, executeAfter = executeAfter, attempt = attempt)
            leaseExpiry.remove(identity.value)
        }
    }

    override fun cancel(identity: ActionIdentity) {
        store[identity.value]?.let {
            if (!it.status.isTerminal) store[identity.value] = it.copy(status = ActionStatus.CANCELLED)
            leaseExpiry.remove(identity.value)
        }
    }

    override fun complete(identity: ActionIdentity) {
        store[identity.value]?.let {
            store[identity.value] = it.copy(status = ActionStatus.COMPLETED)
            leaseExpiry.remove(identity.value)
        }
    }

    override fun fail(
        identity: ActionIdentity,
        reason: ActionFailureReason,
    ) {
        store[identity.value]?.let {
            store[identity.value] = it.copy(status = ActionStatus.FAILED, failureReason = reason)
            leaseExpiry.remove(identity.value)
        }
    }

    override fun find(identity: ActionIdentity): ScheduledSocialAction? = store[identity.value]

    override fun findPendingIn(scope: RevocationScope): List<ActionIdentity> =
        store.values
            .filter {
                it.target.guildPseudonym == scope.guildPseudonym &&
                    (scope.channelId == null || it.target.channelId == scope.channelId) &&
                    !it.status.isTerminal
            }.map { it.identity }

    override fun purge(identity: ActionIdentity) {
        cancel(identity)
    }

    /** 테스트가 특정 상태를 직접 심을 때(예: PARTIALLY_SENT in-flight 재시작 시나리오). */
    fun put(
        action: ScheduledSocialAction,
        leaseExpiresAt: Instant? = null,
    ) {
        store[action.identity.value] = action
        leaseExpiresAt?.let { leaseExpiry[action.identity.value] = it }
    }
}
