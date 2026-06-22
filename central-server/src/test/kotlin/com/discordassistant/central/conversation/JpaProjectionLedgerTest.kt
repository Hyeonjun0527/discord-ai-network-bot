package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.adapter.outbound.persistence.JpaProjectionLedger
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaProjectionDeadLetterRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaProjectionLedgerRepository
import com.discordassistant.central.conversation.domain.model.event.EventId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P03-T016/T018 projection 원장·dead-letter JPA 어댑터 통합 테스트(H2 + Flyway V52).
 *
 * - T016: markApplied 가 (eventId, version) 유니크로 처음만 true → 같은 fixture N 회 재생이 1 회분으로 수렴.
 * - T018: dead-letter 가 원문 없이 event ID·분류 코드·시각만 적재, 재격리 멱등, clear 로 재처리 해제.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaProjectionLedgerTest
    @Autowired
    constructor(
        private val ledgerRepo: NexaProjectionLedgerRepository,
        private val deadLetterRepo: NexaProjectionDeadLetterRepository,
    ) {
        private val clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC)
        private val ledger = JpaProjectionLedger(ledgerRepo, deadLetterRepo, clock)

        @Test
        fun `markApplied 는 처음만 true 이고 같은 fixture 10회 재생이 1회분으로 수렴한다`() {
            val fixture = listOf(EventId("a"), EventId("b"), EventId("c"))
            var applied = 0
            repeat(10) {
                for (id in fixture) {
                    if (ledger.markApplied(id, projectionVersion = 1)) applied += 1
                }
            }
            assertEquals(3, applied, "10 회 재생해도 적용은 3 개(첫 1 회)")
            assertEquals(3, ledgerRepo.count(), "원장 행도 3 개(중복 없음)")
        }

        @Test
        fun `다른 projection version 은 별개로 적용된다`() {
            assertTrue(ledger.markApplied(EventId("x"), 1))
            assertTrue(ledger.markApplied(EventId("x"), 2))
            assertFalse(ledger.markApplied(EventId("x"), 1))
            assertEquals(2, ledgerRepo.count())
        }

        @Test
        fun `dead-letter 는 원문 없이 격리하고 재격리는 멱등이다`() {
            ledger.deadLetter(EventId("dl-1"), 1, reasonCode = "permanent", detail = "IllegalStateException")
            ledger.deadLetter(EventId("dl-1"), 1, reasonCode = "retries-exhausted", detail = "TimeoutException")

            assertEquals(1, deadLetterRepo.count(), "event_id 유니크 — 재격리는 한 행 갱신")
            val records = ledger.deadLetters(10)
            assertEquals(1, records.size)
            assertEquals("dl-1", records.single().eventId.value)
            assertEquals("retries-exhausted", records.single().reasonCode, "최신 사유로 갱신")
        }

        @Test
        fun `clearDeadLetter 는 격리를 해제하고 멱등이다`() {
            ledger.deadLetter(EventId("dl-2"), 1, "permanent", "X")
            assertTrue(ledger.clearDeadLetter(EventId("dl-2")))
            assertFalse(ledger.clearDeadLetter(EventId("dl-2")), "이미 없으면 false(멱등)")
            assertEquals(0, deadLetterRepo.count())
        }

        @Test
        fun `deadLetters 는 최신순으로 limit 만큼 돌려준다`() {
            ledger.deadLetter(EventId("first"), 1, "permanent", "")
            ledger.deadLetter(EventId("second"), 1, "permanent", "")
            ledger.deadLetter(EventId("third"), 1, "permanent", "")
            val top2 = ledger.deadLetters(2)
            assertEquals(2, top2.size)
            assertEquals(listOf("third", "second"), top2.map { it.eventId.value }, "failed_at 역순")
        }
    }
