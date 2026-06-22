package com.discordassistant.central.conversation.domain.model.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** NEXA-P05-T018: 사람이 답함·주제 전환·삭제는 version 을 올리고 metric 갱신만으로는 올리지 않는다. */
class ContextVersionTest {
    @Test
    fun `사람이 답하면 version 이 오른다`() {
        val v = ContextVersion.INITIAL.apply(SceneChange.HUMAN_REPLIED)
        assertEquals(1L, v.value)
    }

    @Test
    fun `주제 전환은 version 을 올린다`() {
        assertEquals(1L, ContextVersion.INITIAL.apply(SceneChange.TOPIC_SWITCHED).value)
    }

    @Test
    fun `삭제는 version 을 올린다`() {
        assertEquals(1L, ContextVersion.INITIAL.apply(SceneChange.MESSAGE_DELETED).value)
    }

    @Test
    fun `스레드 재구조화는 version 을 올린다`() {
        assertEquals(1L, ContextVersion.INITIAL.apply(SceneChange.THREAD_RESTRUCTURED).value)
    }

    @Test
    fun `metric 갱신만으로는 version 이 오르지 않는다 (acceptance)`() {
        val v = ContextVersion(5)
        assertEquals(5L, v.apply(SceneChange.METRICS_UPDATED).value)
        assertEquals(5L, v.apply(SceneChange.PARTICIPANT_METADATA_TOUCHED).value)
    }

    @Test
    fun `결정론 — 같은 change 시퀀스는 같은 최종 version`() {
        val changes =
            listOf(
                SceneChange.HUMAN_REPLIED,
                SceneChange.METRICS_UPDATED,
                SceneChange.TOPIC_SWITCHED,
                SceneChange.PARTICIPANT_METADATA_TOUCHED,
                SceneChange.MESSAGE_DELETED,
            )

        fun fold() = changes.fold(ContextVersion.INITIAL) { acc, c -> acc.apply(c) }
        // 정책 무효화 change 3건 → +3.
        assertEquals(3L, fold().value)
        assertEquals(fold().value, fold().value)
    }
}
