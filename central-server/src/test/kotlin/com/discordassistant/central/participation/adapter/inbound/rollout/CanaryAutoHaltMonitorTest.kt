package com.discordassistant.central.participation.adapter.inbound.rollout

import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.application.rollout.CanaryAutoHaltService
import com.discordassistant.central.participation.application.rollout.CanaryHaltAlert
import com.discordassistant.central.participation.application.rollout.CanarySignalCollector
import com.discordassistant.central.participation.application.rollout.OperatorAlertPort
import com.discordassistant.central.participation.application.rollout.PendingActionCancellationPort
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P18-T023 monitor tick: LIVE 길드에서 시간당 발화가 한도를 넘으면, 모니터가 [CanaryAutoHaltService] 로
 * SHADOW_PREDICT 강등을 집행하고 pending 을 취소한다(SAFETY NET 배선 end-to-end).
 */
class CanaryAutoHaltMonitorTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `over-limit live guild is auto-demoted and its pending cancelled`() {
        val store = FakeShadowStore(mapOf("g-live" to ShadowMode.LIVE))
        val cancel = FakeCancellation(pending = mapOf("g-live" to 4))
        val alert = FakeAlert()
        val service = CanaryAutoHaltService(store, cancel, alert, clock)
        val collector = CanarySignalCollector()
        repeat(31) { collector.recordUtterance("g-live") } // 한도(30) 초과.

        val monitor =
            CanaryAutoHaltMonitor(
                canaryAutoHaltService = service,
                signalCollector = collector,
                maxUtterancesPerHour = 30,
                maxComplaints = 3,
                maxStaleSends = 5,
            )

        monitor.tick()

        assertThat(store.currentMode("g-live")).isEqualTo(ShadowMode.SHADOW_PREDICT) // 강등됨.
        assertThat(cancel.cancelledGuilds).containsExactly("g-live") // pending 취소됨.
        assertThat(alert.alerts).hasSize(1)
        assertThat(alert.alerts[0].cancelledPending).isEqualTo(4)
    }

    @Test
    fun `within-limit live guild is not demoted`() {
        val store = FakeShadowStore(mapOf("g-live" to ShadowMode.LIVE))
        val cancel = FakeCancellation()
        val service = CanaryAutoHaltService(store, cancel, FakeAlert(), clock)
        val collector = CanarySignalCollector()
        repeat(5) { collector.recordUtterance("g-live") } // 한도 이내.

        val monitor = CanaryAutoHaltMonitor(service, collector, 30, 3, 5)
        monitor.tick()

        assertThat(store.currentMode("g-live")).isEqualTo(ShadowMode.LIVE)
        assertThat(cancel.cancelledGuilds).isEmpty()
    }

    // ── fakes ─────────────────────────────────────────────────────────

    private class FakeShadowStore(
        initial: Map<String, ShadowMode>,
    ) : ShadowModeStorePort {
        private val modes = initial.toMutableMap()

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
