package com.discordassistant.central.relay

import com.discordassistant.central.domain.ProviderState
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.ProviderHelloFrame
import com.discordassistant.central.relay.protocol.ProviderStatusFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class NoopConnection : AgentConnection {
    override val remoteId = "noop"
    override fun sendFrame(frame: Frame) {}
    override fun close(reason: String) {}
}

class StateMachineTest {

    @Test
    fun `상태 전이표 가드`() {
        assertTrue(ProviderState.ONLINE_IDLE.canTransitionTo(ProviderState.ONLINE_BUSY))
        assertTrue(ProviderState.OFFLINE.canTransitionTo(ProviderState.ONLINE_IDLE)) // 재연결
        assertFalse(ProviderState.ONLINE_IDLE.canTransitionTo(ProviderState.APPROVED)) // 역행 불가
        assertFalse(ProviderState.REMOVED.canTransitionTo(ProviderState.ONLINE_IDLE)) // 종단
    }

    @Test
    fun `세션 전이 가드 — 불가 전이 거부`() {
        val s = ProviderSession(NoopConnection(), providerId = 1, guildId = 100)
        assertTrue(s.transitionTo(ProviderState.ONLINE_BUSY))
        assertFalse(s.transitionTo(ProviderState.APPROVED)) // BUSY→APPROVED 불가
        assertEquals(ProviderState.ONLINE_BUSY, s.state) // 변경 안 됨
    }

    @Test
    fun `provider_status busy 반영`() {
        // 배터리/고부하가 아니면 busy → ONLINE_BUSY (자동 보호는 K-차수 12 가 우선 처리).
        val s = ProviderSession(NoopConnection(), 1, 100)
        s.handleFrame(ProviderStatusFrame(load = "medium", battery = "charging", online = true, busy = true))
        assertEquals(ProviderState.ONLINE_BUSY, s.state)
        assertEquals("medium", s.liveStatus.load)
        assertEquals("charging", s.liveStatus.battery)
    }

    @Test
    fun `provider_hello 일일 잔여 → sendInfer 감소`() {
        val s = ProviderSession(NoopConnection(), 1, 100)
        s.handleFrame(ProviderHelloFrame(models = listOf("m"), maxConcurrency = 2, remainingDailyRequests = 5))
        assertEquals(5, s.remainingDailyRequests)
        s.sendInfer(prompt = "x") // 요청 1건
        assertEquals(4, s.remainingDailyRequests)
    }
}
