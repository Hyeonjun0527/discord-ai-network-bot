package com.discordassistant.central.participation.application

import com.discordassistant.central.conversation.domain.event.SceneChangeType
import com.discordassistant.central.conversation.domain.event.SceneUpdated
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T005 SceneUpdated→participation 트리거 게이팅 단위 테스트.
 *
 * 핵심 acceptance: **메시지마다 정책 호출 금지** — contextVersion 이 오른(정책 무효화) 장면 갱신에서만 평가한다.
 */
class SceneParticipationConnectorTest {
    @Test
    fun `acceptance — contextVersion 이 오르면 평가 트리거`() {
        val triggered = mutableListOf<SceneUpdated>()
        val connector = SceneParticipationConnector { triggered.add(it) }

        val outcome = connector.onSceneUpdated(sceneUpdated(prev = 1, next = 2))

        assertThat(outcome).isEqualTo(TriggerOutcome.EVALUATED)
        assertThat(triggered).hasSize(1)
    }

    @Test
    fun `acceptance — contextVersion 이 그대로면 평가 안 함(직전 판단 재사용)`() {
        val triggered = mutableListOf<SceneUpdated>()
        val connector = SceneParticipationConnector { triggered.add(it) }

        val outcome = connector.onSceneUpdated(sceneUpdated(prev = 5, next = 5))

        assertThat(outcome).isEqualTo(TriggerOutcome.SKIPPED_NO_INVALIDATION)
        assertThat(triggered).isEmpty()
    }

    private fun sceneUpdated(
        prev: Long,
        next: Long,
    ): SceneUpdated =
        SceneUpdated(
            guildId = GuildId(1L),
            channelId = ChannelId(100L),
            sceneSeq = 1L,
            changeType = SceneChangeType.HUMAN_REPLIED,
            previousContextVersion = prev,
            newContextVersion = next,
            affectedThreadIds = emptySet(),
        )
}
