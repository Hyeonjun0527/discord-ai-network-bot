package com.discordassistant.central.dashboard

import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderCapability
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private class FakeConn(override val remoteId: String = "fake") : AgentConnection {
    override fun sendFrame(frame: Frame) {}
    override fun close(reason: String) {}
}

/** 메트릭 API(차수 15 #226) 집계 로직 검증 — Spring 컨텍스트 없이 레지스트리 직접 구성. */
class MetricsApiControllerTest {
    private fun session(pid: Long, gid: Long?, models: List<String>): ProviderSession =
        ProviderSession(FakeConn(), providerId = pid, guildId = gid).apply {
            capability = ProviderCapability(models = models)
        }

    @Test
    fun `pool 집계 — 활성 수·길드별 크기`() {
        val registry = ConnectionRegistry()
        registry.register(session(1, 100, listOf("m1", "m2")))
        registry.register(session(2, 100, listOf("m1")))
        registry.register(session(3, 200, listOf("m3")))
        val api = MetricsApiController(registry)

        val pool = api.pool()
        assertEquals(3, pool["activeProviders"])
        assertEquals(0, pool["inFlightTotal"])
        @Suppress("UNCHECKED_CAST")
        val sizes = pool["guildPoolSizes"] as Map<String, Int>
        assertEquals(2, sizes["100"])
        assertEquals(1, sizes["200"])
    }

    @Test
    fun `guild 상세 — 프로바이더별 상태·모델 수`() {
        val registry = ConnectionRegistry()
        registry.register(session(1, 100, listOf("m1", "m2")))
        val api = MetricsApiController(registry)

        @Suppress("UNCHECKED_CAST")
        val providers = api.guild(100)["providers"] as List<Map<String, Any>>
        assertEquals(1, providers.size)
        assertEquals(1L, providers[0]["providerId"])
        assertEquals(2, providers[0]["models"])
    }
}
