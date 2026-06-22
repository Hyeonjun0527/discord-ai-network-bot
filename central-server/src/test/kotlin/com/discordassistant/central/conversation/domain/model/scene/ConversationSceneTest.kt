package com.discordassistant.central.conversation.domain.model.scene

import com.discordassistant.central.conversation.domain.event.SceneChangeType
import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T017: 최근 burst·graph refs·participants·tempo·focus·contextVersion aggregate(원문 비복제, ID provenance 유지). */
class ConversationSceneTest {
    private val guild = GuildId(1L)
    private val channel = ChannelId(100L)
    private val location = BurstLocationKey(channel, threadId = null)
    private val threadA = ConversationThreadId.of(location, 0)
    private val threadB = ConversationThreadId.of(location, 1)
    private val version = "scene-v1"

    private fun initial() = ConversationScene.initial(guild, channel, version)

    @Test
    fun `초기 장면은 sceneSeq 0, INITIAL version, idle`() {
        val s = initial()
        assertEquals(0L, s.sceneSeq)
        assertEquals(ContextVersion.INITIAL, s.contextVersion)
        assertTrue(s.focus.isIdle)
        assertEquals(ConversationTempo.IDLE, s.tempo)
    }

    @Test
    fun `장면은 식별자만 담는다 (원문 비복제 acceptance)`() {
        val s =
            initial().advance(
                change = SceneChange.HUMAN_REPLIED,
                recentBurstIds = listOf(BurstId("burst:1:1")),
                activeThreadIds = setOf(threadA),
            )
        // burst·thread 는 식별자 참조만 — 원문 텍스트 필드가 없다.
        assertEquals(listOf(BurstId("burst:1:1")), s.recentBurstIds)
        assertEquals(setOf(threadA), s.activeThreadIds)
    }

    @Test
    fun `advance 는 sceneSeq 를 올리고 정책 무효화 change 면 contextVersion 도 올린다`() {
        val s0 = initial()
        val s1 = s0.advance(SceneChange.HUMAN_REPLIED)
        assertEquals(1L, s1.sceneSeq)
        assertEquals(1L, s1.contextVersion.value)
    }

    @Test
    fun `metric 갱신은 sceneSeq 만 올리고 contextVersion 은 유지`() {
        val s1 = initial().advance(SceneChange.HUMAN_REPLIED)
        val s2 = s1.advance(SceneChange.METRICS_UPDATED)
        assertEquals(2L, s2.sceneSeq)
        assertEquals(s1.contextVersion, s2.contextVersion)
    }

    @Test
    fun `toSceneUpdated 는 변경 유형·이전 새 version·영향 thread 를 싣는다 (T019)`() {
        val s0 = initial().advance(SceneChange.HUMAN_REPLIED, activeThreadIds = setOf(threadA))
        val s1 = s0.advance(SceneChange.TOPIC_SWITCHED, activeThreadIds = setOf(threadA, threadB))

        val event = s1.toSceneUpdated(previous = s0, change = SceneChange.TOPIC_SWITCHED)
        assertEquals(SceneChangeType.TOPIC_SWITCHED, event.changeType)
        assertEquals(s0.contextVersion.value, event.previousContextVersion)
        assertEquals(s1.contextVersion.value, event.newContextVersion)
        // 새로 추가된 threadB 가 영향 thread.
        assertEquals(setOf(threadB), event.affectedThreadIds)
    }

    @Test
    fun `결정론 — 같은 입력 시퀀스 재생이면 같은 sceneSeq 와 version`() {
        fun replay(): ConversationScene =
            initial()
                .advance(SceneChange.HUMAN_REPLIED)
                .advance(SceneChange.METRICS_UPDATED)
                .advance(SceneChange.TOPIC_SWITCHED)
        val a = replay()
        val b = replay()
        assertEquals(a.sceneSeq, b.sceneSeq)
        assertEquals(a.contextVersion, b.contextVersion)
    }
}
