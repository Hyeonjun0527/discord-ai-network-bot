package com.discordassistant.central.channelai.application

import com.discordassistant.central.channelai.domain.model.ConsentLockedException
import com.discordassistant.central.channelai.domain.model.NexaSettingsSnapshot
import com.discordassistant.central.channelai.domain.model.SettingsRollbackException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P18-T016 acceptance: version history 이전값 복구가 되고, 동의 철회 상태는 단순 설정 rollback 으로
 * 되돌릴 수 없다.
 */
class NexaSettingsRollbackServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)

    private fun snapshot(
        version: Int,
        values: Map<String, String>,
        consentRevoked: Boolean = false,
    ) = NexaSettingsSnapshot(
        version = version,
        values = values,
        consentRevoked = consentRevoked,
        actor = "op-7",
        reason = "set",
        at = Instant.parse("2026-06-20T00:00:00Z"),
    )

    private fun service(history: MutableList<NexaSettingsSnapshot>): NexaSettingsRollbackService =
        NexaSettingsRollbackService(
            loadHistory = { history.toList() },
            appendSnapshot = { _, s -> history.add(s) },
            clock = clock,
        )

    @Test
    fun `rollback restores previous version values as a new appended version`() {
        val history =
            mutableListOf(
                snapshot(1, mapOf("freq" to "low")),
                snapshot(2, mapOf("freq" to "high")),
            )
        val svc = service(history)

        val rolledBack = svc.rollBackTo("g-1", targetVersion = 1, actor = "op-9")

        // 전방 복구: v1 의 값으로 v3 을 append(과거는 보존 — append-only).
        assertEquals(3, rolledBack.version)
        assertEquals(mapOf("freq" to "low"), rolledBack.values)
        assertEquals("rollback-to-v1", rolledBack.reason)
        assertEquals(3, history.size)
        assertEquals(mapOf("freq" to "high"), history[1].values) // 과거 v2 보존.
    }

    @Test
    fun `rollback rejected when current state has revoked consent`() {
        val history =
            mutableListOf(
                snapshot(1, mapOf("freq" to "low")),
                snapshot(2, mapOf("freq" to "high"), consentRevoked = true),
            )
        val svc = service(history)

        // acceptance: 동의 철회 상태는 단순 설정 rollback 으로 되돌릴 수 없다.
        assertThrows(ConsentLockedException::class.java) { svc.rollBackTo("g-1", targetVersion = 1, actor = "op-9") }
        assertEquals(2, history.size) // 아무 것도 append 되지 않음.
    }

    @Test
    fun `rollback to missing version fails`() {
        val history = mutableListOf(snapshot(1, mapOf("freq" to "low")))
        val svc = service(history)
        assertThrows(SettingsRollbackException::class.java) { svc.rollBackTo("g-1", targetVersion = 99, actor = "op-9") }
    }

    @Test
    fun `rollback on empty history fails`() {
        val svc = service(mutableListOf())
        assertThrows(SettingsRollbackException::class.java) { svc.rollBackTo("g-1", targetVersion = 1, actor = "op-9") }
    }
}
