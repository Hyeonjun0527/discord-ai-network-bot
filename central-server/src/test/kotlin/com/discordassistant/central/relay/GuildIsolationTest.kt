package com.discordassistant.central.relay

import com.discordassistant.central.relay.protocol.Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class NoopConn(
    override val remoteId: String = "n",
) : AgentConnection {
    override fun sendFrame(frame: Frame) {}

    override fun close(reason: String) {}
}

/**
 * 멀티 길드 격리 검증(차수 11 #155). 한 길드의 프로바이더는 다른 길드 풀에 절대 노출되지 않는다.
 */
class GuildIsolationTest {
    private fun session(
        pid: Long,
        gid: Long,
    ) = ProviderSession(NoopConn(), providerId = pid, guildId = gid)

    @Test
    fun `길드별 풀은 서로 격리된다`() {
        val registry = ConnectionRegistry()
        registry.register(session(1, 100))
        registry.register(session(2, 100))
        registry.register(session(3, 200))

        val g100 = registry.byGuild(100).map { it.providerId }.toSet()
        val g200 = registry.byGuild(200).map { it.providerId }.toSet()

        assertEquals(setOf(1L, 2L), g100)
        assertEquals(setOf(3L), g200)
        assertTrue(g100.intersect(g200).isEmpty(), "두 길드 풀은 교집합이 없어야 한다")
        assertFalse(g100.contains(3L))
        assertFalse(g200.contains(1L))
    }

    @Test
    fun `한 길드 세션 해제는 다른 길드에 영향 없음`() {
        val registry = ConnectionRegistry()
        val s1 = session(1, 100)
        val s3 = session(3, 200)
        registry.register(s1)
        registry.register(s3)
        registry.unregister(s1)
        assertTrue(registry.byGuild(100).isEmpty())
        assertEquals(listOf(3L), registry.byGuild(200).map { it.providerId })
    }
}
