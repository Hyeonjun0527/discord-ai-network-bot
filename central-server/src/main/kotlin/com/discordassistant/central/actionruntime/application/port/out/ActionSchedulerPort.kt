package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import java.time.Instant

/**
 * 예약 행동 스케줄링 아웃바운드 포트(NEXA-P13-T005, application 레이어).
 *
 * 예약 행동의 영속·due claim·재예약·취소·완료를 추상한다. **구현 타입(Quartz/JPA/JDA)을 일절 노출하지 않는다**
 * (acceptance T005) — 도메인/application 은 이 인터페이스와 도메인 타입만 본다. 구현은 adapter(DB polling +
 * SELECT FOR UPDATE SKIP LOCKED lease)가 채운다.
 *
 * **idempotency(T004)**: [schedule] 은 같은 [ScheduledSocialAction.identity] 를 두 번 받으면 두 번째를 **무시**한다
 * (중복 예약 안 만듦) — persistence unique 제약이 SSOT.
 *
 * **동시성·lease(T006/T007)**: [claimDue] 는 lease 를 잡으며 due 행을 가져온다 — 여러 인스턴스가 같은 행을 동시에
 * 가져가지 못한다(SELECT FOR UPDATE SKIP LOCKED). 잡은 행은 [leaseExpiresAt] 까지 그 worker 소유다.
 *
 * 순수성 경계: application 레이어 — 도메인 타입·표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface ActionSchedulerPort {
    /**
     * [action] 을 예약한다(due index 에 SCHEDULED 로 올림). 같은 [ScheduledSocialAction.identity] 가 이미 있으면
     * **아무 것도 하지 않는다**(idempotent — 중복 예약 안 만듦, T004). 새로 예약하면 true, 중복이라 무시하면 false.
     */
    fun schedule(action: ScheduledSocialAction): Boolean

    /**
     * [now] 기준 due 인 예약을 최대 [limit] 건 **claim** 한다(lease 를 [leaseExpiresAt] 까지 건다). 동시에 도는 다른
     * worker 는 같은 행을 가져갈 수 없다(SELECT FOR UPDATE SKIP LOCKED — T006/T007). claim 된 행은 REEVALUATING
     * 으로 전이된 상태로 돌아온다.
     */
    fun claimDue(
        now: Instant,
        leaseExpiresAt: Instant,
        limit: Int,
    ): List<ClaimedAction>

    /**
     * crash 등으로 **만료된 lease**([now] 이전 만료)를 가진 in-flight 행을 회수해 다시 claim 가능하게 만든다(T007/T010).
     * 회수된 행 식별자 목록을 돌려준다(recovery 로그용). TYPING/PARTIALLY_SENT 는 회수 시 이중 전송 방지 규칙(T010)을
     * application(recovery)이 적용한다 — 이 포트는 lease 회수만 한다.
     */
    fun reclaimExpiredLeases(now: Instant): List<ActionIdentity>

    /** [identity] 예약을 [executeAfter] 로 재예약한다(SCHEDULED 로 되돌림 — transient 재시도 T009). lease 해제. */
    fun reschedule(
        identity: ActionIdentity,
        executeAfter: Instant,
        attempt: Int,
    ): Boolean

    /** [identity] 예약을 TYPING 으로 전이한다(재평가 통과 후 실제 실행 직전 상태 저장 — P12/T011). */
    fun markTyping(identity: ActionIdentity): Boolean

    /** [identity] 예약을 PARTIALLY_SENT 로 전이한다(일부 버블 전송 후 잔여 취소/복구 경계 — T010/T020). */
    fun markPartiallySent(identity: ActionIdentity): Boolean

    /** [identity] 예약을 취소한다(CANCELLED 종결 — T012~T014). 이미 terminal 이면 무시(idempotent). */
    fun cancel(identity: ActionIdentity): Boolean

    /** [identity] 예약을 완료 종결한다(COMPLETED). lease 해제. */
    fun complete(identity: ActionIdentity): Boolean

    /** [identity] 예약을 영구 실패 종결한다(FAILED + [reason] — T009). lease 해제. */
    fun fail(
        identity: ActionIdentity,
        reason: ActionFailureReason,
    ): Boolean

    /** [identity] 의 현재 예약 상태를 조회한다(없으면 null) — 재평가/취소 유스케이스가 최신 상태를 읽을 때. */
    fun find(identity: ActionIdentity): ScheduledSocialAction?
}

/**
 * claim 된 예약(application 값 객체). 실제 worker 소유권 검증은 포트 구현체의 lease owner 저장소가 강제한다.
 */
data class ClaimedAction(
    /** claim 된 예약(REEVALUATING 상태로 전이됨). */
    val action: ScheduledSocialAction,
    /** 이 claim 의 lease 만료 시각(이 시각 후엔 다른 worker 가 회수 가능 — T007). */
    val leaseExpiresAt: Instant,
)
