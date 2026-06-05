package com.discordassistant.central.discord

import com.discordassistant.central.provider.domain.model.ProviderState
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 휴식 권장(차수 12 #173). */
class RestHintTest {
    @Test
    fun `부하 높음(LIMITED)이면 휴식 권장`() {
        assertNotNull(RestHint.forStatus(ProviderState.LIMITED, activeRequests = 2, remainingDaily = 100))
    }

    @Test
    fun `일일 한도 임박이면 안내`() {
        assertNotNull(RestHint.forStatus(ProviderState.ONLINE_IDLE, activeRequests = 0, remainingDaily = 3))
    }

    @Test
    fun `여유 있으면 안내 없음`() {
        assertNull(RestHint.forStatus(ProviderState.ONLINE_IDLE, activeRequests = 0, remainingDaily = 100))
    }
}
