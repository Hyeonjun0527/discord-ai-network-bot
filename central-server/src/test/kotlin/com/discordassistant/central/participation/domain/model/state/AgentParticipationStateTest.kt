package com.discordassistant.central.participation.domain.model.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** NEXA-P06-T002: 감정 미표현 + Clock(주입 Instant) 기반 갱신. */
class AgentParticipationStateTest {
    private val scope = ChannelScope(guildPseudonym = "g#1", channelPseudonym = "c#1")
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    @Test
    fun `초기 상태는 행동 없음`() {
        val s = AgentParticipationState.empty(scope)
        assertEquals(0, s.recentBurstCount)
        assertNull(s.lastActedAt)
        assertFalse(s.hasActed)
    }

    @Test
    fun `acceptance - 주입 시각으로 갱신된다 (Clock 기반)`() {
        val s = AgentParticipationState.empty(scope).recordActedAt(t0)
        assertEquals(1, s.recentBurstCount)
        assertEquals(t0, s.lastActedAt)
        assertTrue(s.hasActed)
    }

    @Test
    fun `pending action 은 음수로 내려가지 않는다`() {
        val s = AgentParticipationState.empty(scope).resolvePendingAction()
        assertEquals(0, s.pendingActionCount)
        assertEquals(1, s.addPendingAction().pendingActionCount)
    }

    @Test
    fun `시간 유효성 - 창 밖이면 포화 카운트가 감쇠된다 (영구 낙인 금지)`() {
        val acted = AgentParticipationState.empty(scope).recordActedAt(t0)
        val stale = acted.decayed(now = t0.plus(Duration.ofHours(2)), window = Duration.ofHours(1))
        assertEquals(0, stale.recentBurstCount)
        val fresh = acted.decayed(now = t0.plus(Duration.ofMinutes(10)), window = Duration.ofHours(1))
        assertEquals(1, fresh.recentBurstCount)
    }

    @Test
    fun `윤리 - 감정 라벨 필드가 없다 (카운트와 시각만)`() {
        // 이 모델의 모든 멤버는 카운트(Int)·시각(Instant)·스코프뿐이다. 기분/성격/관계감정 필드가 없다.
        val s = AgentParticipationState.empty(scope)
        assertEquals(scope, s.scope)
        assertEquals(0, s.recentBurstCount)
        assertEquals(0, s.pendingActionCount)
    }
}
