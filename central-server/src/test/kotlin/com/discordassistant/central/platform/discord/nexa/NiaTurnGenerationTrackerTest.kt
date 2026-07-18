package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.conversation.application.scene.ConversationObservation
import com.discordassistant.central.conversation.application.scene.InMemoryConversationSceneIngress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NiaTurnGenerationTrackerTest {
    @Test
    fun `새 메시지가 이전 generation을 supersede하고 순서가 뒤집힌 관찰은 최신값을 되돌리지 않는다`() {
        val tracker = NiaTurnGenerationTracker()

        assertThat(tracker.isLatest(channelId = 3L, generation = 10L)).isTrue()
        assertThat(tracker.observe(channelId = 3L, generation = 10L)).isEqualTo(10L)
        assertThat(tracker.observe(channelId = 3L, generation = 12L)).isEqualTo(12L)
        assertThat(tracker.observe(channelId = 3L, generation = 11L)).isEqualTo(12L)

        assertThat(tracker.isLatest(channelId = 3L, generation = 10L)).isFalse()
        assertThat(tracker.isLatest(channelId = 3L, generation = 12L)).isTrue()
        assertThat(tracker.current(channelId = 3L)).isEqualTo(12L)
    }

    @Test
    fun `actionruntime adapter는 추적 채널의 새 장면만 stale 취소하고 미추적 행동은 보존한다`() {
        val tracker = NiaTurnGenerationTracker()
        val adapter = NiaLatestSceneActionReevaluationAdapter(tracker, InMemoryConversationSceneIngress())
        val tracked = ReevaluationTarget("guild", "channel-pseudonym", "thread", routingChannelId = "3")
        val untracked = ReevaluationTarget("guild", "other-pseudonym", "thread", routingChannelId = "4")
        tracker.observe(channelId = 3L, generation = 12L)

        assertThat(adapter.currentContextVersion(tracked)).isEqualTo(12L)
        assertThat(
            adapter.stillValid(
                decisionId = "decision",
                target = tracked,
                scheduledContextVersion = 10L,
                currentContextVersion = 12L,
            ),
        ).isFalse()

        val unknownVersion = adapter.currentContextVersion(untracked)!!
        assertThat(
            adapter.stillValid(
                decisionId = "legacy-decision",
                target = untracked,
                scheduledContextVersion = 10L,
                currentContextVersion = unknownVersion,
            ),
        ).isTrue()
    }

    @Test
    fun `재시작으로 generation tracker가 비어도 영속 scene이 전진한 예약은 stale이다`() {
        val scenes = InMemoryConversationSceneIngress()
        val scheduledScene =
            scenes.observe(ConversationObservation(1L, 3L, "message:10", Instant.parse("2026-07-18T00:00:00Z")))
        val adapter = NiaLatestSceneActionReevaluationAdapter(NiaTurnGenerationTracker(), scenes)
        val target =
            ReevaluationTarget(
                guildPseudonym = "guild",
                channelId = "channel-pseudonym",
                threadId = "thread",
                routingChannelId = "3",
                scheduledTurnGeneration = 10L,
                scheduledSceneContextVersion = scheduledScene.contextVersion,
            )

        assertThat(adapter.currentContextVersion(target)).isEqualTo(10L)

        scenes.observe(ConversationObservation(1L, 3L, "message:11", Instant.parse("2026-07-18T00:00:01Z")))
        val current = adapter.currentContextVersion(target)!!

        assertThat(current).isNotEqualTo(10L)
        assertThat(adapter.currentSceneContextVersion(target)).isEqualTo(scheduledScene.contextVersion + 1)
        assertThat(adapter.stillValid("decision", target, 10L, current)).isFalse()
    }
}
