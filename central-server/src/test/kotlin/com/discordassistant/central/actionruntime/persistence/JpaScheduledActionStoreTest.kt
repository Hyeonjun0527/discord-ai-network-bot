package com.discordassistant.central.actionruntime.persistence

import com.discordassistant.central.actionruntime.adapter.outbound.persistence.JpaScheduledActionStore
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.JpaWaitReevaluationOutbox
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.ScheduledActionContentEntity
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.ScheduledActionContentRepository
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.ScheduledActionRepository
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.WaitReevaluationOutboxEntity
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.WaitReevaluationOutboxRepository
import com.discordassistant.central.actionruntime.application.ParticipationActionRouter
import com.discordassistant.central.actionruntime.application.RouteResult
import com.discordassistant.central.actionruntime.application.WaitReevaluationRetentionService
import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledDeliveryMode
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.participation.domain.model.action.SocialAction
import com.discordassistant.central.participation.domain.model.action.SpeechRequestRef
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P13-T003/T004/T006/T007/T014 — JpaScheduledActionStore: 실제 영속(Flyway V63)·due claim(SKIP LOCKED)·
 * idempotency·lease 만료 회수·범위 취소를 H2(PostgreSQL 모드)에서 검증.
 *
 * @DataJpaTest 는 기본 빌드에서 H2 로 돈다(integration-docker 태그 아님 — 빠른 검증·커버리지 집계 포함).
 * SELECT FOR UPDATE SKIP LOCKED 문법은 H2 PostgreSQL 모드가 파싱한다(단일 트랜잭션이라 잠금 충돌 자체는 없지만,
 * 쿼리·due 필터·claim 전이의 정합성을 검증한다 — 실제 다중 인스턴스 잠금은 Postgres Testcontainers 책임).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaScheduledActionStoreTest
    @Autowired
    constructor(
        private val repo: ScheduledActionRepository,
        private val contentRepo: ScheduledActionContentRepository,
        private val waitOutboxRepo: WaitReevaluationOutboxRepository,
        private val entityManager: EntityManager,
    ) {
        private val now = Instant.parse("2026-01-01T00:00:00Z")
        private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
        private val store = JpaScheduledActionStore(repo, contentRepo, workerId = "w1", clock = clock)
        private val waitOutbox = JpaWaitReevaluationOutbox(repo, waitOutboxRepo, clock)

        @BeforeEach
        fun configureFieldEncryption() {
            FieldCrypto.configure("scheduled-action-test-key")
        }

        @AfterEach
        fun resetFieldEncryption() {
            FieldCrypto.configure(null)
        }

        private fun action(
            decision: String = "d1",
            index: Int = 0,
            executeAfter: Instant = now,
            guild: String = "g1",
            channel: String = "c1",
            thread: String = "t1",
            user: String? = "u1",
            replyToMessageId: String? = null,
            targetMessageId: String? = null,
            type: ScheduledActionType = ScheduledActionType.SPEAK,
            deliveryMode: ScheduledDeliveryMode = ScheduledDeliveryMode.REPLY,
        ) = ScheduledSocialAction.create(
            decisionId = decision,
            sampledActionIndex = index,
            type = type,
            target =
                ActionTarget(
                    guild,
                    channel,
                    thread,
                    subjectPseudonym = user,
                    replyToMessageId = replyToMessageId,
                    targetMessageId = targetMessageId,
                ),
            executeAfter = executeAfter,
            contextVersion = 7,
            originRolloutMode = ShadowMode.CANARY,
            deliveryMode = deliveryMode,
        )

        @Test
        fun `schedule 은 행을 SCHEDULED 로 영속한다`() {
            assertThat(store.schedule(action())).isTrue()
            val persisted = store.find(action().identity)!!
            assertThat(persisted.status).isEqualTo(ActionStatus.SCHEDULED)
            assertThat(persisted.contextVersion).isEqualTo(7)
            assertThat(persisted.originRolloutMode).isEqualTo(ShadowMode.CANARY)
            assertThat(persisted.deliveryMode).isEqualTo(ScheduledDeliveryMode.REPLY)
        }

        @Test
        fun `participation 라우터와 JPA 저장소를 연결해도 한 번만 SCHEDULED 로 전이한다`() {
            val router = ParticipationActionRouter(store)

            val result =
                router.route(
                    decisionId = "live-turn",
                    sampledActionIndex = 0,
                    action = SocialAction.Speak(SpeechRequestRef("live-turn")),
                    target = ActionTarget("g1", "c1", "t1"),
                    executeAfter = now,
                    contextVersion = 7,
                    originRolloutMode = ShadowMode.LIVE,
                )

            assertThat(result).isEqualTo(RouteResult.Scheduled(ScheduledActionType.SPEAK, newlyScheduled = true))
            assertThat(store.find(ActionIdentity.of("live-turn", 0))!!.status).isEqualTo(ActionStatus.SCHEDULED)
        }

        @Test
        fun `운영 Discord target 형식의 thread id를 저장하고 복원한다`() {
            val productionShapedThreadId = "discord:${"g".repeat(46)}:${"1".repeat(19)}"
            assertThat(productionShapedThreadId).hasSize(74)

            val replyToMessageId = "1234567890123456789"
            val action =
                action(
                    decision = "production-thread",
                    thread = productionShapedThreadId,
                    replyToMessageId = replyToMessageId,
                )

            assertThat(store.schedule(action)).isTrue()
            val restoredTarget = store.find(action.identity)!!.target
            assertThat(restoredTarget.threadId).isEqualTo(productionShapedThreadId)
            assertThat(restoredTarget.targetMessageId).isEqualTo(replyToMessageId)
            assertThat(restoredTarget.replyToMessageId).isNull()
        }

        @Test
        fun `암호화 키가 사라져도 due claim은 암호문을 라우팅 값으로 쓰지 않고 격리한다`() {
            val encryptedAction =
                action(decision = "missing-field-key", targetMessageId = "456").copy(
                    target =
                        ActionTarget(
                            guildPseudonym = "g1",
                            channelId = "channel-pseudonym",
                            threadId = "t1",
                            targetMessageId = "456",
                            routingChannelId = "3",
                        ),
                )
            assertThat(store.schedule(encryptedAction)).isTrue()
            entityManager.flush()
            entityManager.clear()

            FieldCrypto.configure(null)

            val claimed = store.claimDue(now, now.plusSeconds(30), 1).single().action
            assertThat(claimed.target.targetMessageId).isNull()
            assertThat(claimed.target.routingChannelId).isNull()
            assertThat(claimed.status).isEqualTo(ActionStatus.REEVALUATING)

            entityManager.flush()
            entityManager.clear()
            val preservedTarget =
                entityManager
                    .createNativeQuery("SELECT target_message_id FROM nexa_scheduled_action WHERE identity = :identity")
                    .setParameter("identity", encryptedAction.identity.value)
                    .singleResult as String?
            val preservedRouting =
                entityManager
                    .createNativeQuery("SELECT routing_channel_id FROM nexa_scheduled_action WHERE identity = :identity")
                    .setParameter("identity", encryptedAction.identity.value)
                    .singleResult as String?
            assertThat(preservedTarget).startsWith("enc1:")
            assertThat(preservedRouting).startsWith("enc1:")
        }

        @Test
        fun `같은 decision 재처리(같은 identity)는 중복 예약을 만들지 않는다(T004)`() {
            assertThat(store.schedule(action())).isTrue()
            assertThat(store.schedule(action())).isFalse() // 두 번째는 무시
            assertThat(repo.findAll()).hasSize(1)
        }

        @Test
        fun `claimDue 는 due 행만 REEVALUATING 으로 claim 하고 lease 를 건다(SKIP LOCKED)`() {
            store.schedule(action(decision = "due", executeAfter = now.minusSeconds(1)))
            store.schedule(action(decision = "future", index = 0, executeAfter = now.plusSeconds(3600)))

            val claimed = store.claimDue(now = now, leaseExpiresAt = now.plus(Duration.ofSeconds(30)), limit = 10)

            assertThat(claimed).hasSize(1)
            assertThat(claimed[0].action.decisionId).isEqualTo("due")
            assertThat(claimed[0].action.status).isEqualTo(ActionStatus.REEVALUATING)
            // future 행은 아직 SCHEDULED.
            assertThat(store.find(action(decision = "future").identity)!!.status).isEqualTo(ActionStatus.SCHEDULED)
        }

        @Test
        fun `만료 lease 는 reclaim 으로 회수되어 다시 처리 가능하다(T007 T010)`() {
            store.schedule(action(executeAfter = now.minusSeconds(1)))
            store.claimDue(now = now, leaseExpiresAt = now.plus(Duration.ofSeconds(1)), limit = 10)

            // lease 만료 후(now+10s) reclaim.
            val reclaimed = store.reclaimExpiredLeases(now.plus(Duration.ofSeconds(10)))

            assertThat(reclaimed).singleElement().isEqualTo(action().identity)
            // lease 가 풀려 다시 회수 가능(만료 행 없음).
            assertThat(store.reclaimExpiredLeases(now.plus(Duration.ofSeconds(10)))).isEmpty()
        }

        @Test
        fun `active lease 를 잡은 worker 만 in-flight action 을 전이할 수 있다`() {
            val otherWorkerStore = JpaScheduledActionStore(repo, contentRepo, workerId = "w2", clock = clock)
            store.schedule(action(executeAfter = now.minusSeconds(1)))
            store.claimDue(now = now, leaseExpiresAt = now.plus(Duration.ofSeconds(30)), limit = 10)

            assertThatThrownBy { otherWorkerStore.markTyping(action().identity) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("owned by another worker")

            assertThat(store.find(action().identity)!!.status).isEqualTo(ActionStatus.REEVALUATING)
            assertThat(store.markTyping(action().identity)).isTrue()
            assertThat(store.find(action().identity)!!.status).isEqualTo(ActionStatus.TYPING)
        }

        @Test
        fun `complete fail cancel 이 상태와 lease 를 갱신한다`() {
            store.schedule(action(decision = "a", executeAfter = now.minusSeconds(1)))
            store.schedule(action(decision = "b", executeAfter = now.minusSeconds(1)))
            store.schedule(action(decision = "c"))
            store.claimDue(now, now.plus(Duration.ofSeconds(30)), 10)
            store.markTyping(action(decision = "a").identity)

            store.complete(action(decision = "a").identity)
            store.fail(action(decision = "b").identity, ActionFailureReason.PERMISSION_DENIED)
            store.cancel(action(decision = "c").identity)

            assertThat(store.find(action(decision = "a").identity)!!.status).isEqualTo(ActionStatus.COMPLETED)
            val failed = store.find(action(decision = "b").identity)!!
            assertThat(failed.status).isEqualTo(ActionStatus.FAILED)
            assertThat(failed.failureReason).isEqualTo(ActionFailureReason.PERMISSION_DENIED)
            assertThat(store.find(action(decision = "c").identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
        }

        @Test
        fun `저장소도 도메인 상태머신을 우회하지 않는다`() {
            store.schedule(action(decision = "a"))

            assertThatThrownBy { store.complete(action(decision = "a").identity) }
                .isInstanceOf(IllegalStateException::class.java)

            assertThat(store.find(action(decision = "a").identity)!!.status).isEqualTo(ActionStatus.SCHEDULED)
        }

        @Test
        fun `없는 action 전이는 조용히 무시하지 않고 실패한다`() {
            val missing = ActionIdentity.of("missing", 0)

            assertThatThrownBy { store.cancel(missing) }
                .isInstanceOf(NoSuchElementException::class.java)
        }

        @Test
        fun `reschedule 은 SCHEDULED 로 되돌리고 attempt 를 갱신한다(T009)`() {
            store.schedule(action(executeAfter = now.minusSeconds(1)))
            store.claimDue(now, now.plus(Duration.ofSeconds(30)), 10)

            store.reschedule(action().identity, executeAfter = now.plusSeconds(60), attempt = 1)

            val rescheduled = store.find(action().identity)!!
            assertThat(rescheduled.status).isEqualTo(ActionStatus.SCHEDULED)
            assertThat(rescheduled.attempt).isEqualTo(1)
        }

        @Test
        fun `만료 lease 회수는 non-terminal in-flight 상태만 대상으로 한다`() {
            store.schedule(action(decision = "terminal", executeAfter = now.minusSeconds(1)))
            store.claimDue(now, now.plus(Duration.ofSeconds(1)), 10)
            store.markTyping(action(decision = "terminal").identity)
            store.complete(action(decision = "terminal").identity)

            val entity = repo.findByIdentity(action(decision = "terminal").identity.value)!!
            entity.leaseExpiresAt = now.minusSeconds(1)
            repo.saveAndFlush(entity)

            assertThat(store.reclaimExpiredLeases(now)).isEmpty()
        }

        @Test
        fun `findPendingIn 과 purge 는 범위의 pending 을 취소하고 content 를 제거한다(T014)`() {
            store.schedule(action(decision = "keep", guild = "g1", channel = "c2"))
            store.schedule(action(decision = "revoke", guild = "g1", channel = "c1"))
            // 생성된 content 가 있는 상태(원문 생성 후).
            contentRepo.save(
                ScheduledActionContentEntity(
                    actionIdentity = action(decision = "revoke").identity.value,
                    content = "secret",
                    createdAt = now,
                ),
            )

            val scope = RevocationScope(guildPseudonym = "g1", channelId = "c1")
            val pending = store.findPendingIn(scope)
            assertThat(pending).singleElement().isEqualTo(action(decision = "revoke").identity)

            store.purge(action(decision = "revoke").identity)

            assertThat(store.find(action(decision = "revoke").identity)!!.status).isEqualTo(ActionStatus.CANCELLED)
            assertThat(contentRepo.findByActionIdentity(action(decision = "revoke").identity.value)).isNull()
            // 다른 채널은 보존.
            assertThat(store.find(action(decision = "keep").identity)!!.status).isEqualTo(ActionStatus.SCHEDULED)
        }

        @Test
        fun `findPendingIn 은 사용자 범위 철회면 같은 채널의 해당 subject 만 찾는다`() {
            store.schedule(action(decision = "u1", guild = "g1", channel = "c1", user = "u1"))
            store.schedule(action(decision = "u2", guild = "g1", channel = "c1", user = "u2"))
            store.schedule(action(decision = "other-channel", guild = "g1", channel = "c2", user = "u1"))

            val pending = store.findPendingIn(RevocationScope(guildPseudonym = "g1", channelId = "c1", userPseudonym = "u1"))

            assertThat(pending).containsExactly(action(decision = "u1").identity)
        }

        @Test
        fun `WAIT 완료와 child 재평가 명령은 한 outbox 전이로 멱등 저장된다`() {
            val wait =
                action(decision = "wait", type = ScheduledActionType.WAIT).copy(
                    target =
                        ActionTarget(
                            guildPseudonym = "g1",
                            channelId = "channel-pseudonym",
                            threadId = "t1",
                            subjectPseudonym = "user-pseudonym",
                            targetMessageId = "456",
                            routingGuildId = "1",
                            routingChannelId = "3",
                            routingUserId = "2",
                        ),
                )
            store.schedule(wait)
            val claimed = store.claimDue(now, now.plusSeconds(30), 1).single().action

            val first = waitOutbox.completeAndEnqueue(claimed, observedContextVersion = 9)
            val duplicate = waitOutbox.completeAndEnqueue(claimed, observedContextVersion = 9)

            assertThat(first).isNotNull
            assertThat(duplicate).isEqualTo(first)
            assertThat(first?.waitActionIdentity).isEqualTo(wait.identity.value)
            assertThat(first?.observedContextVersion).isEqualTo(9)
            assertThat(first?.routingGuildId).isEqualTo("1")
            assertThat(first?.routingChannelId).isEqualTo("3")
            assertThat(first?.routingUserId).isEqualTo("2")
            assertThat(first?.targetMessageId).isEqualTo("456")
            assertThat(store.find(wait.identity)?.status).isEqualTo(ActionStatus.COMPLETED)
            assertThat(waitOutbox.claimPending(10)).containsExactly(first)
            assertThat(waitOutbox.claimPending(10)).isEmpty()

            entityManager.flush()
            val actionRouting =
                entityManager
                    .createNativeQuery("SELECT routing_channel_id FROM nexa_scheduled_action WHERE identity = :identity")
                    .setParameter("identity", wait.identity.value)
                    .singleResult as String
            val outboxRouting =
                entityManager
                    .createNativeQuery(
                        "SELECT routing_channel_id FROM nexa_wait_reevaluation_outbox WHERE wait_action_identity = :identity",
                    ).setParameter("identity", wait.identity.value)
                    .singleResult as String
            val outboxTarget =
                entityManager
                    .createNativeQuery("SELECT target_message_id FROM nexa_wait_reevaluation_outbox WHERE wait_action_identity = :identity")
                    .setParameter("identity", wait.identity.value)
                    .singleResult as String

            assertThat(actionRouting).startsWith("enc1:").isNotEqualTo("3")
            assertThat(outboxRouting).startsWith("enc1:").isNotEqualTo("3")
            assertThat(outboxTarget).startsWith("enc1:").isNotEqualTo("456")
        }

        @Test
        fun `WAIT outbox도 키 장애 동안 암호문을 보존하고 키 복구 후 다시 읽는다`() {
            val wait =
                action(decision = "wait-key-recovery", type = ScheduledActionType.WAIT).copy(
                    target =
                        ActionTarget(
                            guildPseudonym = "g1",
                            channelId = "channel-pseudonym",
                            threadId = "t1",
                            targetMessageId = "456",
                            routingChannelId = "3",
                        ),
                )
            store.schedule(wait)
            entityManager.flush()
            entityManager.clear()

            FieldCrypto.configure(null)

            val claimedWithoutKey = store.claimDue(now, now.plusSeconds(30), 1).single().action
            assertThat(claimedWithoutKey.target.targetMessageId).isNull()
            assertThat(claimedWithoutKey.target.routingChannelId).isNull()
            val enqueued = waitOutbox.completeAndEnqueue(claimedWithoutKey, observedContextVersion = 9)!!
            assertThat(enqueued.targetMessageId).isNull()
            assertThat(enqueued.routingChannelId).isNull()
            entityManager.flush()
            entityManager.clear()
            val preservedRouting =
                entityManager
                    .createNativeQuery(
                        "SELECT routing_channel_id FROM nexa_wait_reevaluation_outbox WHERE child_decision_id = :childId",
                    ).setParameter("childId", enqueued.childDecisionId)
                    .singleResult as String?
            assertThat(preservedRouting).startsWith("enc1:")

            FieldCrypto.configure("scheduled-action-test-key")

            val recovered = waitOutbox.claimPending(1).single()
            assertThat(recovered.targetMessageId).isEqualTo("456")
            assertThat(recovered.routingChannelId).isEqualTo("3")
        }

        @Test
        fun `WAIT outbox retention은 보존 기간을 넘긴 행만 삭제한다`() {
            waitOutboxRepo.saveAll(
                listOf(
                    WaitReevaluationOutboxEntity(
                        childDecisionId = "expired-child",
                        waitActionIdentity = "expired-wait",
                        guildPseudonym = "g",
                        channelId = "c",
                        threadId = "t",
                        observedContextVersion = 1,
                        wakeAttempt = 1,
                        expiresAt = now.minus(Duration.ofDays(31)),
                        createdAt = now.minus(Duration.ofDays(32)),
                    ),
                    WaitReevaluationOutboxEntity(
                        childDecisionId = "retained-child",
                        waitActionIdentity = "retained-wait",
                        guildPseudonym = "g",
                        channelId = "c",
                        threadId = "t",
                        observedContextVersion = 1,
                        wakeAttempt = 1,
                        expiresAt = now.minus(Duration.ofDays(29)),
                        createdAt = now.minus(Duration.ofDays(30)),
                    ),
                ),
            )

            val retention = WaitReevaluationRetentionService(waitOutboxRepo, clock)

            assertThat(retention.purgeExpired(30, now)).isEqualTo(1)
            assertThat(waitOutboxRepo.findByChildDecisionId("expired-child")).isNull()
            assertThat(waitOutboxRepo.findByChildDecisionId("retained-child")).isNotNull()
        }

        @Test
        fun `만료된 WAIT는 child 재평가 outbox를 만들지 않는다`() {
            val wait = action(decision = "expired-wait", executeAfter = now.minusSeconds(300), type = ScheduledActionType.WAIT)
            store.schedule(wait)
            val claimed = store.claimDue(now, now.plusSeconds(30), 1).single().action

            assertThat(waitOutbox.completeAndEnqueue(claimed, observedContextVersion = 9)).isNull()
            assertThat(waitOutboxRepo.count()).isZero()
        }

        @Test
        fun `만료된 outbox claim lease는 같은 child id로 회수된다`() {
            val wait = action(decision = "recover-wait", type = ScheduledActionType.WAIT)
            store.schedule(wait)
            val claimed = store.claimDue(now, now.plusSeconds(30), 1).single().action
            val command = waitOutbox.completeAndEnqueue(claimed, observedContextVersion = 9)!!
            assertThat(waitOutbox.claimPending(10)).containsExactly(command)

            val recovered =
                JpaWaitReevaluationOutbox(
                    repo,
                    waitOutboxRepo,
                    Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC),
                ).claimPending(10)

            assertThat(recovered).containsExactly(command)
            assertThat(recovered.single().childDecisionId).isEqualTo(command.childDecisionId)
        }

        @Test
        fun `긴 focus thread와 비가역 consent token을 잘리지 않고 영속한다`() {
            val longThread = "thread:" + "x".repeat(240)
            val consentToken = "consent:" + "a".repeat(64)
            val longTarget =
                ScheduledSocialAction.create(
                    decisionId = "long-target",
                    sampledActionIndex = 0,
                    type = ScheduledActionType.SPEAK,
                    target = ActionTarget("g1", "c1", longThread, subjectPseudonym = consentToken),
                    executeAfter = now,
                    contextVersion = 1,
                    originRolloutMode = ShadowMode.LIVE,
                )

            assertThat(store.schedule(longTarget)).isTrue()
            assertThat(store.find(longTarget.identity)?.target?.threadId).isEqualTo(longThread)
            assertThat(store.find(longTarget.identity)?.target?.subjectPseudonym).isEqualTo(consentToken)
        }
    }
