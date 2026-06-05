package com.discordassistant.central.alert

import com.discordassistant.central.ainetwork.application.Notifier
import com.discordassistant.central.ainetwork.application.PoolAlertMonitor
import com.discordassistant.central.ainetwork.application.Severity
import com.discordassistant.central.relay.ConnectionRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class RecordingNotifier : Notifier {
    val events = mutableListOf<Triple<Severity, String, String>>()

    override fun notify(
        severity: Severity,
        title: String,
        message: String,
    ) {
        events.add(Triple(severity, title, message))
    }
}

/** 풀 알림 모니터(차수 15 #220) — edge-trigger 동작 검증. */
class PoolAlertMonitorTest {
    private fun monitor(
        notifier: Notifier,
        low: Int = 1,
    ) = PoolAlertMonitor(ConnectionRegistry(), notifier, lowThreshold = low)

    @Test
    fun `0명 진입은 한 번만 CRITICAL`() {
        val n = RecordingNotifier()
        val m = monitor(n)
        assertEquals(1, m.evaluate(0))
        assertEquals(0, m.evaluate(0)) // 연속 0명은 재알림 없음
        assertEquals(Severity.CRITICAL, n.events.single().first)
    }

    @Test
    fun `복구 시 INFO 알림`() {
        val n = RecordingNotifier()
        val m = monitor(n)
        m.evaluate(0)
        n.events.clear()
        assertEquals(1, m.evaluate(2))
        assertEquals(Severity.INFO, n.events.single().first)
    }

    @Test
    fun `프로바이더 오프라인 전환 감지(#163)`() {
        val n = RecordingNotifier()
        val m = monitor(n)
        assertEquals(0, m.evaluateProviders(setOf(1L, 2L, 3L))) // 첫 관측: 알림 없음
        assertEquals(0, m.evaluateProviders(setOf(1L, 2L, 3L))) // 변화 없음
        assertEquals(1, m.evaluateProviders(setOf(1L, 3L))) // 2 오프라인 → WARN 1
        assertEquals(Severity.WARN, n.events.single().first)
        assertTrue(
            n.events
                .single()
                .third
                .contains("2"),
        )
        assertEquals(0, m.evaluateProviders(setOf(1L, 3L, 4L))) // 신규 추가는 알림 없음
    }

    @Test
    fun `용량 부족 임계 이하 진입은 한 번만 WARN`() {
        val n = RecordingNotifier()
        val m = monitor(n, low = 3)
        assertEquals(1, m.evaluate(2)) // 2 < 3 → WARN
        assertEquals(0, m.evaluate(1)) // 여전히 임계 이하 → 재알림 없음
        assertEquals(Severity.WARN, n.events.single().first)
        // 회복 후 다시 이하로 가면 재알림
        assertEquals(0, m.evaluate(3)) // 회복(임계 이상)
        assertEquals(1, m.evaluate(2)) // 다시 이하 → WARN
    }
}
