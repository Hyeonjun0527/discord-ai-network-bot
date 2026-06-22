package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.application.port.out.GuildKillSwitchAction
import com.discordassistant.central.actionruntime.application.port.out.GuildKillSwitchAuditEvent
import com.discordassistant.central.actionruntime.application.port.out.GuildKillSwitchStorePort
import com.discordassistant.central.actionruntime.application.port.out.PendingActionPurgePort
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P18-T013 acceptance: 관리자/운영자가 **즉시** 신규 결정·예약·전송을 중단하고, 이미 생성된 pending content
 * 까지 취소되며 audit 가 남는다.
 */
class GuildKillSwitchServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `engage immediately blocks new actions for that guild`() {
        val store = FakeStore()
        val purge = FakePurge()
        val service = GuildKillSwitchService(store, purge, clock)

        assertFalse(service.isBlocked("g-1")) // 발동 전.
        service.engage("g-1", actor = "op-7", reason = "over_talk")
        // 발동 즉시 다음 호출부터 BLOCK(tick 대기 없음).
        assertTrue(service.isBlocked("g-1"))
        // 다른 길드는 영향 없음.
        assertFalse(service.isBlocked("g-2"))
    }

    @Test
    fun `engage cancels already-generated pending content`() {
        val store = FakeStore()
        val purge =
            FakePurge(
                pendingByGuild =
                    mutableMapOf(
                        "g-1" to mutableListOf(ActionIdentity.of("d1", 0), ActionIdentity.of("d2", 0)),
                    ),
            )
        val service = GuildKillSwitchService(store, purge, clock)

        val cancelled = service.engage("g-1", actor = "op-7", reason = "over_talk")
        assertEquals(2, cancelled)
        // pending 이 실제로 purge 됐다(content 까지 제거 — ConsentRevocation 과 같은 경로).
        assertEquals(2, purge.purged.size)
    }

    @Test
    fun `engage and disengage leave audit trail`() {
        val store = FakeStore()
        val service = GuildKillSwitchService(store, FakePurge(), clock)

        service.engage("g-1", actor = "op-7", reason = "model_error")
        service.disengage("g-1", actor = "op-7")

        val audit = store.auditFor("g-1")
        assertEquals(2, audit.size)
        assertEquals(GuildKillSwitchAction.ENGAGE, audit[0].action)
        assertEquals("model_error", audit[0].reason)
        assertEquals(GuildKillSwitchAction.DISENGAGE, audit[1].action)
        // 해제 후 다시 ALLOW.
        assertFalse(service.isBlocked("g-1"))
    }

    // ── 인메모리 fake 들 ───────────────────────────────────────────────

    private class FakeStore : GuildKillSwitchStorePort {
        private val active = mutableSetOf<String>()
        private val audit = mutableListOf<GuildKillSwitchAuditEvent>()

        override fun activeKilledGuilds(): Set<String> = active.toSet()

        override fun engage(
            guildPseudonym: String,
            actor: String,
            reason: String,
            cancelledPending: Int,
            at: Instant,
        ) {
            active.add(guildPseudonym)
            audit.add(GuildKillSwitchAuditEvent(guildPseudonym, GuildKillSwitchAction.ENGAGE, actor, reason, cancelledPending, at))
        }

        override fun disengage(
            guildPseudonym: String,
            actor: String,
            at: Instant,
        ) {
            active.remove(guildPseudonym)
            audit.add(GuildKillSwitchAuditEvent(guildPseudonym, GuildKillSwitchAction.DISENGAGE, actor, "", 0, at))
        }

        override fun auditFor(guildPseudonym: String): List<GuildKillSwitchAuditEvent> =
            audit.filter { it.guildPseudonym == guildPseudonym }
    }

    private class FakePurge(
        private val pendingByGuild: MutableMap<String, MutableList<ActionIdentity>> = mutableMapOf(),
    ) : PendingActionPurgePort {
        val purged = mutableListOf<ActionIdentity>()

        override fun findPendingIn(scope: RevocationScope): List<ActionIdentity> =
            pendingByGuild[scope.guildPseudonym]?.toList() ?: emptyList()

        override fun purge(identity: ActionIdentity) {
            purged.add(identity)
        }
    }
}
