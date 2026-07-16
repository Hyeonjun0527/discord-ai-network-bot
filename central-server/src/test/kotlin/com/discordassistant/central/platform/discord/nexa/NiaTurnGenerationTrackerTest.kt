package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
        val adapter = NiaLatestSceneActionReevaluationAdapter(tracker)
        val tracked = ReevaluationTarget("guild", "3", "thread")
        val untracked = ReevaluationTarget("guild", "4", "thread")
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
}
