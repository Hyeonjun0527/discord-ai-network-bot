package com.discordassistant.central.discord

import com.discordassistant.central.domain.ProviderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.Color

/** Embed 고도화(차수 13 #156) — 상태 색상/필드. */
class EmbedFactoryTest {
    @Test
    fun `상태별 색상 badge`() {
        assertEquals(Color(0x57F287), EmbedFactory.stateColor(ProviderState.ONLINE_IDLE))
        assertEquals(Color(0xFEE75C), EmbedFactory.stateColor(ProviderState.PAUSED))
        assertEquals(Color(0xED4245), EmbedFactory.stateColor(ProviderState.UNHEALTHY))
        assertEquals(Color(0x5865F2), EmbedFactory.stateColor(ProviderState.APPROVED))
    }

    @Test
    fun `프로바이더 상태 embed 필드`() {
        val e = EmbedFactory.providerStatus(providerId = 7, state = ProviderState.ONLINE_IDLE, inFlight = 2, failures = 1)
        assertEquals("프로바이더 상태", e.title)
        assertEquals(Color(0x57F287), e.color)
        assertEquals("2", e.fields.first { it.name == "처리중" }.value)
        assertNotNull(e.footer)
    }

    @Test
    fun `풀 요약 embed — 0명이면 적색`() {
        assertEquals(Color(0xED4245), EmbedFactory.poolSummary(active = 0, models = 0, inFlight = 0).color)
        assertEquals(Color(0x57F287), EmbedFactory.poolSummary(active = 3, models = 2, inFlight = 1).color)
    }
}
