package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.application.port.out.ChannelMuteAuditEvent
import com.discordassistant.central.actionruntime.application.port.out.ChannelMuteStorePort
import com.discordassistant.central.actionruntime.application.port.out.PendingActionPurgePort
import com.discordassistant.central.actionruntime.domain.ChannelMuteLevel
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P18-T014 acceptance: 채널별 mute 가 발화/관찰을 분리 차단하고(관찰 중단은 새 append 부터), 즉시 발효하며,
 * 발화 차단 시 이미 생성된 pending content 도 즉시 취소된다.
 */
class ChannelMuteServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `speech-only mute blocks speech immediately but keeps observation append`() {
        val store = FakeStore()
        val service = ChannelMuteService(store, FakePurge(), clock)

        assertTrue(service.allowsSpeech("c-1")) // 발동 전.
        assertTrue(service.allowsObservationAppend("c-1"))

        service.mute("c-1", ChannelMuteLevel.SPEECH_ONLY, actor = "op-7", reason = "over_talk")

        // 즉시 발효: 다음 호출부터 발화 차단(tick 대기 없음). 관찰은 계속(맥락 보존).
        assertFalse(service.allowsSpeech("c-1"))
        assertTrue(service.allowsObservationAppend("c-1"))
        // 다른 채널은 영향 없음.
        assertTrue(service.allowsSpeech("c-2"))
    }

    @Test
    fun `observe-and-speech mute blocks new event store append from that point`() {
        val store = FakeStore()
        val service = ChannelMuteService(store, FakePurge(), clock)

        service.mute("c-1", ChannelMuteLevel.OBSERVE_AND_SPEECH, actor = "op-7", reason = "user_request")

        // acceptance: 관찰 중단은 새 event store append 부터 차단한다.
        assertFalse(service.allowsObservationAppend("c-1"))
        assertFalse(service.allowsSpeech("c-1"))
    }

    @Test
    fun `mute cancels already-generated pending content in that channel`() {
        val store = FakeStore()
        val purge =
            FakePurge(
                pendingByChannel =
                    mutableMapOf(
                        "c-1" to mutableListOf(ActionIdentity.of("d1", 0), ActionIdentity.of("d2", 0)),
                    ),
            )
        val service = ChannelMuteService(store, purge, clock)

        val cancelled = service.mute("c-1", ChannelMuteLevel.SPEECH_ONLY, actor = "op-7", reason = "over_talk")
        assertEquals(2, cancelled)
        assertEquals(2, purge.purged.size)
    }

    @Test
    fun `unmute restores both speech and observation and leaves audit trail`() {
        val store = FakeStore()
        val service = ChannelMuteService(store, FakePurge(), clock)

        service.mute("c-1", ChannelMuteLevel.OBSERVE_AND_SPEECH, actor = "op-7", reason = "model_error")
        service.unmute("c-1", actor = "op-7")

        assertTrue(service.allowsSpeech("c-1"))
        assertTrue(service.allowsObservationAppend("c-1"))
        assertEquals(ChannelMuteLevel.NONE, service.levelOf("c-1"))

        val audit = store.auditFor("c-1")
        assertEquals(2, audit.size)
        assertEquals(ChannelMuteLevel.OBSERVE_AND_SPEECH, audit[0].level)
        assertEquals("model_error", audit[0].reason)
        assertEquals(ChannelMuteLevel.NONE, audit[1].level)
    }

    @Test
    fun `mute with NONE level is treated as unmute`() {
        val store = FakeStore()
        val service = ChannelMuteService(store, FakePurge(), clock)
        service.mute("c-1", ChannelMuteLevel.SPEECH_ONLY, actor = "op-7", reason = "x")
        assertFalse(service.allowsSpeech("c-1"))

        service.mute("c-1", ChannelMuteLevel.NONE, actor = "op-7", reason = "")
        assertTrue(service.allowsSpeech("c-1"))
    }

    // ── 인메모리 fake 들 ───────────────────────────────────────────────

    private class FakeStore : ChannelMuteStorePort {
        private val active = mutableMapOf<String, ChannelMuteLevel>()
        private val audit = mutableListOf<ChannelMuteAuditEvent>()

        override fun activeMutes(): Map<String, ChannelMuteLevel> = active.toMap()

        override fun mute(
            channelPseudonym: String,
            level: ChannelMuteLevel,
            actor: String,
            reason: String,
            cancelledPending: Int,
            at: Instant,
        ) {
            active[channelPseudonym] = level
            audit.add(
                ChannelMuteAuditEvent(
                    channelPseudonym = channelPseudonym,
                    action = com.discordassistant.central.actionruntime.application.port.out.ChannelMuteAction.MUTE,
                    level = level,
                    actor = actor,
                    reason = reason,
                    cancelledPending = cancelledPending,
                    at = at,
                ),
            )
        }

        override fun unmute(
            channelPseudonym: String,
            actor: String,
            at: Instant,
        ) {
            active.remove(channelPseudonym)
            audit.add(
                ChannelMuteAuditEvent(
                    channelPseudonym = channelPseudonym,
                    action = com.discordassistant.central.actionruntime.application.port.out.ChannelMuteAction.UNMUTE,
                    level = ChannelMuteLevel.NONE,
                    actor = actor,
                    reason = "",
                    cancelledPending = 0,
                    at = at,
                ),
            )
        }

        override fun auditFor(channelPseudonym: String): List<ChannelMuteAuditEvent> =
            audit.filter { it.channelPseudonym == channelPseudonym }
    }

    private class FakePurge(
        private val pendingByChannel: MutableMap<String, MutableList<ActionIdentity>> = mutableMapOf(),
    ) : PendingActionPurgePort {
        val purged = mutableListOf<ActionIdentity>()

        override fun findPendingIn(scope: RevocationScope): List<ActionIdentity> =
            pendingByChannel[scope.channelId]?.toList() ?: emptyList()

        override fun purge(identity: ActionIdentity) {
            purged.add(identity)
        }
    }
}
