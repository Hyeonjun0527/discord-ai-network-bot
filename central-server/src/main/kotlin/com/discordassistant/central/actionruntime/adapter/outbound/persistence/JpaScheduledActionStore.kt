package com.discordassistant.central.actionruntime.adapter.outbound.persistence

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.ClaimedAction
import com.discordassistant.central.actionruntime.application.port.out.PendingActionPurgePort
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * [ActionSchedulerPort] + [PendingActionPurgePort] 의 JPA 구현(NEXA-P13-T003/T006/T007/T014, Flyway V63).
 *
 * 예약 행동을 `nexa_scheduled_action` 에 영속하고, due 행을 **SELECT FOR UPDATE SKIP LOCKED** 로 claim 해 여러
 * 인스턴스가 같은 행을 동시에 가져가지 못하게 한다(T006/T007). worker lease(`lease_owner`/`lease_expires_at`)로
 * crash 후 다른 인스턴스가 만료 lease 를 회수한다(T010). 같은 결정 재처리는 `identity` UNIQUE 로 거부(T004).
 *
 * **원문 보호(T003)**: 예약 시점에는 content 를 저장하지 않는다(`nexa_scheduled_action_content` 는 생성 후에만 행).
 *
 * 순수성: 도메인/application 타입 ↔ entity 매핑만. 도메인은 이 어댑터를 모른다(헥사고날).
 */
@Repository
class JpaScheduledActionStore(
    private val repo: ScheduledActionRepository,
    private val content: ScheduledActionContentRepository,
    @org.springframework.beans.factory.annotation.Value("\${nexa.action-runtime.worker-id:central}")
    private val workerId: String = "central",
    private val clock: Clock = Clock.systemUTC(),
) : ActionSchedulerPort,
    PendingActionPurgePort {
    @Transactional
    override fun schedule(action: ScheduledSocialAction): Boolean {
        if (repo.findByIdentity(action.identity.value) != null) return false // idempotent — 중복 예약 안 만듦(T004).
        val now = Instant.now(clock)
        repo.save(action.markScheduled().toEntity(now))
        return true
    }

    @Transactional
    override fun claimDue(
        now: Instant,
        leaseExpiresAt: Instant,
        limit: Int,
    ): List<ClaimedAction> {
        // SELECT ... FOR UPDATE SKIP LOCKED — 다른 인스턴스가 잡은 행은 건너뛴다(중복 claim 방지 T006/T007).
        val rows = repo.lockDue(now, limit)
        return rows.map { entity ->
            entity.applyDomain(entity.toDomain().beginReevaluation(), now)
            entity.leaseOwner = workerId
            entity.leaseExpiresAt = leaseExpiresAt
            ClaimedAction(action = entity.toDomain(), leaseExpiresAt = leaseExpiresAt)
        }
    }

    @Transactional
    override fun reclaimExpiredLeases(now: Instant): List<ActionIdentity> {
        val expired = repo.findExpiredInFlight(now)
        return expired.map {
            it.leaseOwner = null
            it.leaseExpiresAt = null
            it.updatedAt = now
            ActionIdentity.of(it.decisionId, indexFrom(it.identity))
        }
    }

    @Transactional
    override fun reschedule(
        identity: ActionIdentity,
        executeAfter: Instant,
        attempt: Int,
    ): Boolean {
        val entity = requireEntity(identity)
        // 재시작 복구/재시도 경로가 부르는데, 그 사이 purge 가 CANCELLED 로 종결했으면 불법 전이 예외로 복구 루프
        // 전체가 throw + 나머지 in-flight 행이 stranded 된다 — 종결 상태면 멱등 no-op.
        if (ActionStatus.valueOf(entity.status).isTerminal) return false
        entity.requireLeaseOwner(identity)
        val rescheduled = entity.toDomain().retryToSchedule(executeAfter, attempt)
        entity.applyDomain(rescheduled, Instant.now(clock))
        entity.leaseOwner = null
        entity.leaseExpiresAt = null
        return true
    }

    @Transactional
    override fun markTyping(identity: ActionIdentity): Boolean {
        val entity = requireEntity(identity)
        // 동의철회/kill-switch purge 가 별도 트랜잭션에서 CANCELLED 로 종결하고 lease 를 null 로 만든 뒤 poller 가
        // markTyping 을 부르면 불법 전이 예외로 tick 전체가 throw 된다 — cancel/complete/fail 과 동일한 종결 가드로 멱등 no-op.
        if (ActionStatus.valueOf(entity.status).isTerminal) return false
        entity.requireLeaseOwner(identity)
        entity.applyDomain(entity.toDomain().passReevaluation(), Instant.now(clock))
        return true
    }

    @Transactional
    override fun markPartiallySent(identity: ActionIdentity): Boolean {
        val entity = requireEntity(identity)
        // 실행 서비스가 첫 버블 전송 시점과 이후 부분취소/실패 경로 양쪽에서 호출할 수 있으므로 멱등이어야 한다 —
        // 이미 PARTIALLY_SENT 면 불법 자기전이 예외 대신 no-op.
        if (ActionStatus.valueOf(entity.status) == ActionStatus.PARTIALLY_SENT) return false
        entity.requireLeaseOwner(identity)
        entity.applyDomain(entity.toDomain().markPartiallySent(), Instant.now(clock))
        return true
    }

    @Transactional
    override fun cancel(identity: ActionIdentity): Boolean {
        val entity = requireEntity(identity)
        if (ActionStatus.valueOf(entity.status).isTerminal) return false
        entity.requireLeaseOwner(identity)
        entity.applyDomain(entity.toDomain().cancel(), Instant.now(clock))
        entity.leaseOwner = null
        entity.leaseExpiresAt = null
        return true
    }

    @Transactional
    override fun complete(identity: ActionIdentity): Boolean {
        val entity = requireEntity(identity)
        // 동의 철회 purge 등으로 이미 종결(CANCELLED/FAILED)됐으면 멱등 no-op — 불법 전이 예외로 실행 tick 을
        // 크래시시키지 않는다(cancel 과 동일한 종결 가드).
        if (ActionStatus.valueOf(entity.status).isTerminal) return false
        entity.requireLeaseOwner(identity)
        entity.applyDomain(entity.toDomain().complete(), Instant.now(clock))
        entity.leaseOwner = null
        entity.leaseExpiresAt = null
        return true
    }

    @Transactional
    override fun fail(
        identity: ActionIdentity,
        reason: ActionFailureReason,
    ): Boolean {
        val entity = requireEntity(identity)
        if (ActionStatus.valueOf(entity.status).isTerminal) return false
        entity.requireLeaseOwner(identity)
        entity.applyDomain(entity.toDomain().fail(reason), Instant.now(clock))
        entity.leaseOwner = null
        entity.leaseExpiresAt = null
        return true
    }

    @Transactional(readOnly = true)
    override fun find(identity: ActionIdentity): ScheduledSocialAction? = repo.findByIdentity(identity.value)?.toDomain()

    // ── PendingActionPurgePort(T014) ──

    @Transactional(readOnly = true)
    override fun findPendingIn(scope: RevocationScope): List<ActionIdentity> =
        repo
            .findPendingInScope(
                guildPseudonym = scope.guildPseudonym,
                channelId = scope.channelId,
                userPseudonym = scope.userPseudonym,
            ).map { ActionIdentity.of(it.decisionId, indexFrom(it.identity)) }

    @Transactional
    override fun purge(identity: ActionIdentity) {
        repo.findByIdentity(identity.value)?.let {
            if (!ActionStatus.valueOf(it.status).isTerminal) it.applyDomain(it.toDomain().cancel(), Instant.now(clock))
            it.leaseOwner = null
            it.leaseExpiresAt = null
        }
        // 생성된 content 도 함께 제거(T014 — pending action + generated content 둘 다 제거).
        content.deleteByActionIdentity(identity.value)
    }

    private fun requireEntity(identity: ActionIdentity): ScheduledActionEntity =
        repo.findByIdentity(identity.value)
            ?: throw NoSuchElementException("scheduled action not found: ${identity.value}")

    private fun ScheduledActionEntity.requireLeaseOwner(identity: ActionIdentity) {
        val owner = leaseOwner ?: return
        require(owner == workerId) {
            "scheduled action lease is owned by another worker: action=${identity.value}, owner=$owner, worker=$workerId"
        }
    }

    private fun ScheduledActionEntity.applyDomain(
        action: ScheduledSocialAction,
        now: Instant,
    ) {
        executeAfter = action.executeAfter
        status = action.status.name
        attempt = action.attempt
        maxAttempts = action.maxAttempts
        failureReason = action.failureReason?.wireName
        updatedAt = now
    }

    private fun ScheduledSocialAction.toEntity(now: Instant): ScheduledActionEntity =
        ScheduledActionEntity(
            identity = identity.value,
            decisionId = decisionId,
            actionType = type.wireName,
            guildPseudonym = target.guildPseudonym,
            channelId = target.channelId,
            threadId = target.threadId,
            subjectPseudonym = target.subjectPseudonym,
            executeAfter = executeAfter,
            contextVersion = contextVersion,
            status = status.name,
            attempt = attempt,
            maxAttempts = maxAttempts,
            failureReason = failureReason?.wireName,
            createdAt = now,
            updatedAt = now,
        )

    private fun ScheduledActionEntity.toDomain(): ScheduledSocialAction =
        ScheduledSocialAction(
            identity = ActionIdentity.of(decisionId, indexFrom(identity)),
            decisionId = decisionId,
            type = typeFromWire(actionType),
            target =
                ActionTarget(
                    guildPseudonym = guildPseudonym,
                    channelId = channelId,
                    threadId = threadId,
                    subjectPseudonym = subjectPseudonym,
                ),
            executeAfter = executeAfter,
            contextVersion = contextVersion,
            status = ActionStatus.valueOf(status),
            attempt = attempt,
            failureReason = failureReason?.let { reasonFromWire(it) },
            maxAttempts = maxAttempts,
        )

    private companion object {
        // identity = "<decisionId>#<index>" 에서 index 복원(toDomain 재구성용).
        fun indexFrom(identity: String): Int = identity.substringAfterLast('#').toInt()

        fun typeFromWire(wire: String): ScheduledActionType = ScheduledActionType.entries.first { it.wireName == wire }

        fun reasonFromWire(wire: String): ActionFailureReason = ActionFailureReason.entries.first { it.wireName == wire }

        fun ScheduledSocialAction.retryToSchedule(
            executeAfter: Instant,
            attempt: Int,
        ): ScheduledSocialAction {
            require(attempt >= this.attempt) { "attempt 는 감소할 수 없다: current=${this.attempt}, next=$attempt" }
            require(status.canTransitionTo(ActionStatus.SCHEDULED)) {
                "불법 상태 전이: $status → ${ActionStatus.SCHEDULED} (action=${identity.value})"
            }
            return copy(status = ActionStatus.SCHEDULED, executeAfter = executeAfter, attempt = attempt)
        }
    }
}

/** 예약 행동 영속 엔티티(`nexa_scheduled_action`). */
@Entity
@Table(name = "nexa_scheduled_action")
class ScheduledActionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "identity") var identity: String = "",
    @Column(name = "decision_id") var decisionId: String = "",
    @Column(name = "action_type") var actionType: String = "",
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "channel_id") var channelId: String = "",
    @Column(name = "thread_id") var threadId: String = "",
    @Column(name = "subject_pseudonym") var subjectPseudonym: String? = null,
    @Column(name = "execute_after") var executeAfter: Instant = Instant.EPOCH,
    @Column(name = "context_version") var contextVersion: Long = 0,
    @Column(name = "status") var status: String = ActionStatus.CONSIDERING.name,
    @Column(name = "attempt") var attempt: Int = 0,
    @Column(name = "max_attempts") var maxAttempts: Int = ScheduledSocialAction.DEFAULT_MAX_ATTEMPTS,
    @Column(name = "failure_reason") var failureReason: String? = null,
    @Column(name = "lease_owner") var leaseOwner: String? = null,
    @Column(name = "lease_expires_at") var leaseExpiresAt: Instant? = null,
    @Column(name = "policy_version") var policyVersion: String? = null,
    @Column(name = "model_version") var modelVersion: String? = null,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String = "ScheduledActionEntity(identity=$identity, status=$status)"
}

/** 생성된 발화 content(`nexa_scheduled_action_content` — 생성된 뒤에만 행 존재, T003 원문 보호). */
@Entity
@Table(name = "nexa_scheduled_action_content")
class ScheduledActionContentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "action_identity") var actionIdentity: String = "",
    @Lob @Column(name = "content") var content: String = "",
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
)

interface ScheduledActionRepository : JpaRepository<ScheduledActionEntity, Long> {
    fun findByIdentity(identity: String): ScheduledActionEntity?

    /**
     * due(SCHEDULED & execute_after<=now)인 행을 **FOR UPDATE SKIP LOCKED** 로 잠가 가져온다 — 다른 트랜잭션이
     * 이미 잡은 행은 건너뛴다(여러 인스턴스 중복 claim 방지 T006/T007). H2(PostgreSQL 모드)·Postgres 공통 문법.
     */
    @Query(
        value =
            "SELECT * FROM nexa_scheduled_action " +
                "WHERE status = 'SCHEDULED' AND execute_after <= :now " +
                "ORDER BY execute_after ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
        nativeQuery = true,
    )
    fun lockDue(
        @Param("now") now: Instant,
        @Param("limit") limit: Int,
    ): List<ScheduledActionEntity>

    /** lease 가 만료된(lease_expires_at < now) in-flight(claim 됐던) 행 — 재시작 회수 대상(T007/T010). */
    @Query(
        "SELECT e FROM ScheduledActionEntity e " +
            "WHERE e.leaseExpiresAt IS NOT NULL " +
            "AND e.leaseExpiresAt < :now " +
            "AND e.status IN ('REEVALUATING', 'TYPING', 'PARTIALLY_SENT')",
    )
    fun findExpiredInFlight(
        @Param("now") now: Instant,
    ): List<ScheduledActionEntity>

    /**
     * [guildPseudonym] 범위(채널 좁히기 선택)의 **종결되지 않은**(COMPLETED/CANCELLED/FAILED 아님) 예약 — 동의
     * 철회 즉시 취소 대상(T014). [channelId] 가 null 이면 길드 전체, [userPseudonym] 이 있으면 그 동의 주체만.
     */
    @Query(
        "SELECT e FROM ScheduledActionEntity e " +
            "WHERE e.guildPseudonym = :guildPseudonym " +
            "AND (:channelId IS NULL OR e.channelId = :channelId) " +
            "AND (:userPseudonym IS NULL OR e.subjectPseudonym = :userPseudonym) " +
            "AND e.status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')",
    )
    fun findPendingInScope(
        @Param("guildPseudonym") guildPseudonym: String,
        @Param("channelId") channelId: String?,
        @Param("userPseudonym") userPseudonym: String?,
    ): List<ScheduledActionEntity>
}

interface ScheduledActionContentRepository : JpaRepository<ScheduledActionContentEntity, Long> {
    fun findByActionIdentity(actionIdentity: String): ScheduledActionContentEntity?

    fun deleteByActionIdentity(actionIdentity: String)
}
