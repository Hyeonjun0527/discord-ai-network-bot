package com.discordassistant.central.health

import com.discordassistant.central.relay.ConnectionRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status

class PoolHealthIndicatorTest {
    @Test
    fun `헬스 — 활성 연결 수 노출`() {
        val health = PoolHealthIndicator(ConnectionRegistry()).health()
        assertEquals(Status.UP, health.status)
        assertEquals(0, health.details["activeProviderConnections"])
    }
}
