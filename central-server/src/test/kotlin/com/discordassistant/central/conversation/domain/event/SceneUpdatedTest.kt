package com.discordassistant.central.conversation.domain.event

import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.scene.ConversationScene
import com.discordassistant.central.conversation.domain.model.scene.SceneChange
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T019: 변경 유형·이전/새 contextVersion·영향 thread 전달, 동일 입력 재생에서 이벤트 순서·version 동일. */
class SceneUpdatedTest {
    private val guild = GuildId(1L)
    private val channel = ChannelId(100L)
    private val location = BurstLocationKey(channel, threadId = null)
    private val threadA = ConversationThreadId.of(location, 0)

    @Test
    fun `멱등성 키는 channelId + sceneSeq (domain-events 계약)`() {
        val event = sceneUpdated(sceneSeq = 7)
        assertEquals("scene:100:7", event.idempotencyKey())
    }

    @Test
    fun `version 이 오르면 정책 무효화 표식`() {
        val bumped = sceneUpdated(prev = 2, next = 3)
        assertTrue(bumped.invalidatedPolicy)
        val same = sceneUpdated(prev = 2, next = 2)
        assertFalse(same.invalidatedPolicy)
    }

    @Test
    fun `newContextVersion 은 previous 보다 작을 수 없다 (단조 비감소)`() {
        assertThrows(IllegalArgumentException::class.java) {
            sceneUpdated(prev = 5, next = 4)
        }
    }

    @Test
    fun `동일 입력 재생에서 이벤트 순서와 version 이 동일하다 (acceptance)`() {
        fun replayEvents(): List<SceneUpdated> {
            var scene = ConversationScene.initial(guild, channel, "v1")
            val changes = listOf(SceneChange.HUMAN_REPLIED, SceneChange.METRICS_UPDATED, SceneChange.TOPIC_SWITCHED)
            val events = mutableListOf<SceneUpdated>()
            for (change in changes) {
                val next = scene.advance(change, activeThreadIds = setOf(threadA))
                events.add(next.toSceneUpdated(previous = scene, change = change))
                scene = next
            }
            return events
        }

        val run1 = replayEvents()
        val run2 = replayEvents()
        // 순서·sceneSeq·version 동일.
        assertEquals(run1.map { it.idempotencyKey() }, run2.map { it.idempotencyKey() })
        assertEquals(run1.map { it.newContextVersion }, run2.map { it.newContextVersion })
        // metric 갱신 step 은 version 유지.
        assertEquals(listOf(1L, 1L, 2L), run1.map { it.newContextVersion })
    }

    @Test
    fun `SceneChangeType 은 모든 SceneChange 를 매핑한다`() {
        SceneChange.entries.forEach { change ->
            // 매핑이 누락되면 when 이 컴파일되지 않으므로 호출만으로 전수 매핑을 보장한다.
            SceneChangeType.of(change)
        }
    }

    private fun sceneUpdated(
        sceneSeq: Long = 1,
        prev: Long = 0,
        next: Long = 1,
    ): SceneUpdated =
        SceneUpdated(
            guildId = guild,
            channelId = channel,
            sceneSeq = sceneSeq,
            changeType = SceneChangeType.HUMAN_REPLIED,
            previousContextVersion = prev,
            newContextVersion = next,
            affectedThreadIds = setOf(threadA),
        )
}
