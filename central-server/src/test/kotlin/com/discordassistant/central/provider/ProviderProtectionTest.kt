package com.discordassistant.central.provider

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.provider.application.ProviderProtectionService
import com.discordassistant.central.provider.domain.model.ProviderState
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferError
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.ProviderStatusFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class NoopConn : AgentConnection {
    override val remoteId = "noop"

    override fun sendFrame(frame: Frame) {}

    override fun close(reason: String) {}
}

private class FailingConn : AgentConnection {
    lateinit var session: ProviderSession
    override val remoteId = "fail"

    override fun sendFrame(frame: Frame) {
        if (frame is InferRequest) session.handleFrame(InferError(frame.requestId, "OLLAMA_ERROR", "boom"))
    }

    override fun close(reason: String) {}
}

class ProviderProtectionTest {
    private fun registerNoop(
        reg: ConnectionRegistry,
        id: Long,
    ): ProviderSession {
        val s = ProviderSession(NoopConn(), id, guildId = 100)
        reg.register(s)
        return s
    }

    @Test
    fun `pause·resume·leave`() {
        val reg = ConnectionRegistry()
        val svc = ProviderProtectionService(reg, AuditLog())
        val s = registerNoop(reg, 1)
        assertTrue(svc.pause(1))
        assertEquals(ProviderState.PAUSED, s.state)
        assertTrue(svc.resume(1))
        assertEquals(ProviderState.ONLINE_IDLE, s.state)
        assertTrue(svc.leave(1))
        assertEquals(0, reg.activeCount())
    }

    @Test
    fun `자동 보호 — 배터리·고부하`() {
        val reg = ConnectionRegistry()
        val s = registerNoop(reg, 1)
        s.handleFrame(ProviderStatusFrame(load = "idle", battery = "discharging", online = true, busy = false))
        assertEquals(ProviderState.PAUSED, s.state)

        val s2 = registerNoop(reg, 2)
        s2.handleFrame(ProviderStatusFrame(load = "high", battery = "charging", online = true, busy = false))
        assertEquals(ProviderState.LIMITED, s2.state)
    }

    @Test
    fun `반복 실패 → UNHEALTHY 자동 비활성화`() {
        val reg = ConnectionRegistry()
        val conn = FailingConn()
        val s = ProviderSession(conn, 1, guildId = 100)
        conn.session = s
        reg.register(s)
        repeat(3) { runCatching { s.sendInfer(prompt = "x").get() } }
        assertTrue(s.failures >= 3)
        assertEquals(ProviderState.UNHEALTHY, s.state)
    }
}
