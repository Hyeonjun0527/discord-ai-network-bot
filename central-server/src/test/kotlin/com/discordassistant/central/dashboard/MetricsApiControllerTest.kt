package com.discordassistant.central.dashboard

import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderCapability
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeConn(
    override val remoteId: String = "fake",
) : AgentConnection {
    override fun sendFrame(frame: Frame) {}

    override fun close(reason: String) {}
}

/** 메트릭 API(차수 15 #226) 집계 로직 검증 — Spring 컨텍스트 없이 레지스트리 직접 구성. */
class MetricsApiControllerTest {
    private fun session(
        pid: Long,
        gid: Long?,
        models: List<String>,
    ): ProviderSession =
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
    fun `guild 상세 — 공개 audience 는 provider 식별자와 부하 상세를 숨긴다`() {
        val registry = ConnectionRegistry()
        registry.register(session(1, 100, listOf("m1", "m2")))
        val api = MetricsApiController(registry)

        @Suppress("UNCHECKED_CAST")
        val providers = api.guild(100)["providers"] as List<Map<String, Any>>
        assertEquals(1, providers.size)
        assertTrue(providers[0]["providerLabel"].toString().startsWith("Provider "))
        assertFalse(providers[0].containsKey("providerId"))
        assertFalse(providers[0].containsKey("inFlight"))
        assertEquals(2, providers[0]["modelCount"])
    }

    @Test
    fun `guild 상세 — 관리자 audience 에서만 provider 식별자와 부하 상세를 볼 수 있다`() {
        val registry = ConnectionRegistry()
        registry.register(session(1, 100, listOf("m1", "m2")))
        val api = MetricsApiController(registry)

        @Suppress("UNCHECKED_CAST")
        val providers = api.guild(100, audience = "admin")["providers"] as List<Map<String, Any>>
        assertEquals(1, providers.size)
        assertEquals(1L, providers[0]["providerId"])
        assertEquals(0, providers[0]["inFlight"])
        assertEquals(0, providers[0]["queued"])
        assertEquals(0, providers[0]["failures"])
        assertEquals(2, providers[0]["modelCount"])
    }
}
