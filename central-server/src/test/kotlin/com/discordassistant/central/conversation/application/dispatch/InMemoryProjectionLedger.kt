package com.discordassistant.central.conversation.application.dispatch

import com.discordassistant.central.conversation.application.port.out.DeadLetterRecord
import com.discordassistant.central.conversation.application.port.out.ProjectionLedgerPort
import com.discordassistant.central.conversation.domain.model.event.EventId

/**
 * 단위 테스트용 인메모리 [ProjectionLedgerPort] — dedup·dead-letter 의 멱등 의미를 DB 없이 검증한다.
 */
class InMemoryProjectionLedger : ProjectionLedgerPort {
    private val applied = mutableSetOf<Pair<String, Int>>()
    private val deadLetters = linkedMapOf<String, DeadLetterRecord>()
    private var clockMs = 0L

    override fun markApplied(
        eventId: EventId,
        projectionVersion: Int,
    ): Boolean = applied.add(eventId.value to projectionVersion)

    override fun isApplied(
        eventId: EventId,
        projectionVersion: Int,
    ): Boolean = applied.contains(eventId.value to projectionVersion)

    override fun deadLetter(
        eventId: EventId,
        projectionVersion: Int,
        reasonCode: String,
        detail: String,
    ) {
        deadLetters[eventId.value] =
            DeadLetterRecord(eventId, projectionVersion, reasonCode, detail, clockMs++)
    }

    override fun deadLetters(limit: Int): List<DeadLetterRecord> = deadLetters.values.sortedByDescending { it.failedAtEpochMs }.take(limit)

    override fun clearDeadLetter(eventId: EventId): Boolean = deadLetters.remove(eventId.value) != null
}
