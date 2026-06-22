package com.discordassistant.central.speech.context

import com.discordassistant.central.speech.application.context.ConversationContextSelector
import com.discordassistant.central.speech.application.port.out.RawThreadTurn
import com.discordassistant.central.speech.application.port.out.SceneContextReadPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T007: 대화 context selector — focus thread만·token budget 안. */
class ConversationContextSelectorTest {
    private fun selector(turns: List<RawThreadTurn>) =
        ConversationContextSelector(
            object : SceneContextReadPort {
                override fun recentTurns(
                    focusThreadKey: String,
                    limit: Int,
                ): List<RawThreadTurn> = turns.take(limit)
            },
        )

    @Test
    fun `excludes turns from other threads (no concurrent thread leakage)`() {
        val turns =
            listOf(
                RawThreadTurn("thread_A", "user_1", "포커스 1"),
                RawThreadTurn("thread_B", "user_2", "다른 스레드 비밀"),
                RawThreadTurn("thread_A", "user_3", "포커스 2"),
            )
        val selected = selector(turns).select("thread_A", tokenBudget = 1000)
        assertThat(selected.map { it.text }).containsExactly("포커스 1", "포커스 2")
        assertThat(selected.map { it.text }).noneMatch { it.contains("다른 스레드") }
    }

    @Test
    fun `respects token budget keeping most recent turns`() {
        // 각 turn 본문 8자 → ~2 token + 라벨 3 = 5 token. budget 12 면 최신 2개만.
        val turns = (1..5).map { RawThreadTurn("t", "u$it", "메시지내용abc$it") }
        val selected = selector(turns).select("t", tokenBudget = 12)
        assertThat(selected).hasSizeLessThanOrEqualTo(2)
        // 시간순(과거→현재)으로 반환되며 가장 최근이 포함된다.
        assertThat(selected.last().text).isEqualTo("메시지내용abc5")
    }

    @Test
    fun `returns chronological order oldest to newest`() {
        val turns =
            listOf(
                RawThreadTurn("t", "u1", "첫째"),
                RawThreadTurn("t", "u2", "둘째"),
                RawThreadTurn("t", "u3", "셋째"),
            )
        val selected = selector(turns).select("t", tokenBudget = 1000)
        assertThat(selected.map { it.text }).containsExactly("첫째", "둘째", "셋째")
    }
}
