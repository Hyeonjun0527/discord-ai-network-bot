package com.discordassistant.central.actionruntime.persistence

import com.discordassistant.central.actionruntime.adapter.outbound.persistence.ActionAuditRepository
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.JpaActionAuditStore
import com.discordassistant.central.actionruntime.domain.model.ActionAuditEvent
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Instant

/**
 * NEXA-P13-T022 — JpaActionAuditStore: append-only 감사 로그(Flyway V64)를 H2 에서 검증.
 *
 * acceptance(T022): 원문 없이 decision/action/message IDs 로 사건을 재구성한다 — 레코드에 본문이 없고, action_id 로
 * 모아 시간순으로 읽으면 schedule→generate→typing→send/cancel 의 phase 시퀀스가 복원된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaActionAuditStoreTest
    @Autowired
    constructor(
        private val repo: ActionAuditRepository,
    ) {
        private val store = JpaActionAuditStore(repo)
        private val t0 = Instant.parse("2026-01-01T00:00:00Z")

        private fun event(
            phase: ActionAuditPhase,
            at: Instant,
            messageId: String? = null,
            reason: String? = null,
        ) = ActionAuditEvent(
            actionId = "dec-1#0",
            decisionId = "dec-1",
            phase = phase,
            messageId = messageId,
            reason = reason,
            occurredAt = at,
        )

        @Test
        fun `append 한 사건을 action 기준 시간순으로 재구성한다`() {
            store.append(event(ActionAuditPhase.SCHEDULED, t0))
            store.append(event(ActionAuditPhase.GENERATED, t0.plusSeconds(1)))
            store.append(event(ActionAuditPhase.TYPING_STARTED, t0.plusSeconds(2)))
            store.append(event(ActionAuditPhase.SENT, t0.plusSeconds(3), messageId = "m-100"))
            store.append(event(ActionAuditPhase.COMPLETED, t0.plusSeconds(4)))

            val reconstructed = store.findByAction("dec-1#0")
            assertThat(reconstructed.map { it.phase }).containsExactly(
                ActionAuditPhase.SCHEDULED,
                ActionAuditPhase.GENERATED,
                ActionAuditPhase.TYPING_STARTED,
                ActionAuditPhase.SENT,
                ActionAuditPhase.COMPLETED,
            )
            // message ID 가 SENT 에 연결되어 사건 추적 가능(T017/T022).
            assertThat(reconstructed.first { it.phase == ActionAuditPhase.SENT }.messageId).isEqualTo("m-100")
        }

        @Test
        fun `append-only — 같은 action 의 사건이 누적되며 덮어쓰지 않는다`() {
            store.append(event(ActionAuditPhase.SENT, t0, messageId = "m-1"))
            store.append(event(ActionAuditPhase.SENT, t0.plusSeconds(1), messageId = "m-2"))
            val events = store.findByAction("dec-1#0")
            assertThat(events).hasSize(2)
            assertThat(events.map { it.messageId }).containsExactly("m-1", "m-2")
        }

        @Test
        fun `취소 사유가 reason 으로 남아 사건을 설명한다`() {
            store.append(event(ActionAuditPhase.CANCELLED, t0, reason = "consent_revoked"))
            val events = store.findByAction("dec-1#0")
            assertThat(events.single().reason).isEqualTo("consent_revoked")
        }
    }
