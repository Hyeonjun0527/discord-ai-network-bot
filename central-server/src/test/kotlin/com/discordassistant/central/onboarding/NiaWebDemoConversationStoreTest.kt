package com.discordassistant.central.onboarding

import com.discordassistant.central.onboarding.application.NiaWebDemoConversationStore
import com.discordassistant.central.routing.application.CloudTurn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class NiaWebDemoConversationStoreTest {
    @Test
    fun `최근 턴만 보관하고 TTL 뒤에는 대화 문맥을 폐기한다`() {
        val clock = MutableClock(Instant.parse("2026-07-29T00:00:00Z"))
        val store = NiaWebDemoConversationStore(ttlSeconds = 30, clock = clock)

        repeat(3) { index ->
            store.append(
                userId = "user",
                conversationId = "conversation",
                userMessage = "질문 ${index + 1}",
                assistantMessage = "답변 ${index + 1}",
                maxMessages = 4,
            )
        }

        assertThat(store.history("user", "conversation", 4))
            .containsExactly(
                CloudTurn("user", "질문 2"),
                CloudTurn("assistant", "답변 2"),
                CloudTurn("user", "질문 3"),
                CloudTurn("assistant", "답변 3"),
            )

        clock.advanceSeconds(31)

        assertThat(store.history("user", "conversation", 4)).isEmpty()
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
