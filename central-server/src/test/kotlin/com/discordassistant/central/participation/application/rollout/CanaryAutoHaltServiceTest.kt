package com.discordassistant.central.participation.application.rollout

import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P18-T023 service acceptance: 자동 중단이 단계를 강등하고, **중단 후 pending action 도 취소되며 운영자
 * 알림이 간다**.
 */
class CanaryAutoHaltServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)
    private val limits = CanaryLimits(maxUtterancesPerHour = 10, maxComplaints = 2, maxStaleSends = 1)

    @Test
    fun `over-talk halt demotes to shadow predict, cancels pending, and notifies operator`() {
        val store = FakeShadowStore(initial = ShadowMode.LIVE)
        val cancel = FakeCancellation(pending = mapOf("g-1" to 3))
        val alert = FakeAlert()
        val service = CanaryAutoHaltService(store, cancel, alert, clock)

        val halted = service.handle("g-1", CanarySignals(utterancesPerHour = 50), limits)

        assertTrue(halted)
        assertEquals(ShadowMode.SHADOW_PREDICT, store.currentMode("g-1")) // 강등됨.
        assertEquals(listOf("g-1"), cancel.cancelledGuilds) // pending 취소됨.
        assertEquals(1, alert.alerts.size) // 운영자 알림 1건.
        assertEquals(3, alert.alerts[0].cancelledPending)
        assertTrue(alert.alerts[0].reasons.contains(HaltReason.OVER_TALK))
    }

    @Test
    fun `privacy error halt demotes to OFF`() {
        val store = FakeShadowStore(initial = ShadowMode.CANARY)
        val service = CanaryAutoHaltService(store, FakeCancellation(), FakeAlert(), clock)

        assertTrue(service.handle("g-1", CanarySignals(privacyErrors = 1), limits))
        assertEquals(ShadowMode.OFF, store.currentMode("g-1"))
    }

    @Test
    fun `no halt when within limits - no demotion, no cancel, no alert`() {
        val store = FakeShadowStore(initial = ShadowMode.LIVE)
        val cancel = FakeCancellation()
        val alert = FakeAlert()
        val service = CanaryAutoHaltService(store, cancel, alert, clock)

        assertFalse(service.handle("g-1", CanarySignals(utterancesPerHour = 5), limits))
        assertEquals(ShadowMode.LIVE, store.currentMode("g-1"))
        assertTrue(cancel.cancelledGuilds.isEmpty())
        assertTrue(alert.alerts.isEmpty())
    }

    @Test
    fun `no halt when already in shadow - idempotent safety`() {
        val store = FakeShadowStore(initial = ShadowMode.SHADOW_PREDICT)
        val service = CanaryAutoHaltService(store, FakeCancellation(), FakeAlert(), clock)
        // 이미 전송이 꺼진 단계 → 추가 강등 없음.
        assertFalse(service.handle("g-1", CanarySignals(utterancesPerHour = 999, privacyErrors = 9), limits))
    }

    // ── fakes ─────────────────────────────────────────────────────────

    private class FakeShadowStore(
        initial: ShadowMode,
    ) : ShadowModeStorePort {
        private val modes = mutableMapOf("g-1" to initial)

        override fun currentMode(guildPseudonym: String): ShadowMode = modes[guildPseudonym] ?: ShadowMode.OFF

        override fun applyTransition(audit: ShadowModeAudit) {
            modes[audit.guildPseudonym] = audit.to
        }

        override fun auditTrail(guildPseudonym: String): List<ShadowModeAudit> = emptyList()

        override fun listModes(): List<ShadowModeState> = emptyList()
    }

    private class FakeCancellation(
        private val pending: Map<String, Int> = emptyMap(),
    ) : PendingActionCancellationPort {
        val cancelledGuilds = mutableListOf<String>()

        override fun cancelPendingFor(guildPseudonym: String): Int {
            cancelledGuilds.add(guildPseudonym)
            return pending[guildPseudonym] ?: 0
        }
    }

    private class FakeAlert : OperatorAlertPort {
        val alerts = mutableListOf<CanaryHaltAlert>()

        override fun notifyAutoHalt(alert: CanaryHaltAlert) {
            alerts.add(alert)
        }
    }
}
