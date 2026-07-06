package com.discordassistant.central.participation.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [ChannelAttentionGate] 단위 테스트 — core attention_gate.py 타이밍 케이스 1:1.
 *
 * 검증: NO_WAKE-on-nia(앵커 갱신)·pingpong wake·min_gap debounce·typing grace·dynamic_idle 보간·WAKE_AFTER_IDLE·
 * RESPOND_NOW 즉시 wake·DROP no-wake·idle_due/clear_pending.
 */
class ChannelAttentionGateTest {
    @Test
    fun `attention timing constants are fixed core port policy`() {
        assertThat(AttentionGateConstants.ATTENTION_VERSION).isEqualTo("att-1")
        assertThat(AttentionGateConstants.IDLE_MIN_MS).isEqualTo(2_000)
        assertThat(AttentionGateConstants.IDLE_MAX_MS).isEqualTo(7_000)
        assertThat(AttentionGateConstants.PINGPONG_WINDOW_MS).isEqualTo(20_000)
        assertThat(AttentionGateConstants.MIN_GAP_MS).isEqualTo(1_500)
        assertThat(AttentionGateConstants.TYPING_GRACE_MS).isEqualTo(4_000)
        assertThat(AttentionGateConstants.GAP_WINDOW).isEqualTo(8)

        assertThat(AttentionGateConstants.MIN_GAP_MS).isPositive()
        assertThat(AttentionGateConstants.IDLE_MIN_MS).isGreaterThan(AttentionGateConstants.MIN_GAP_MS)
        assertThat(AttentionGateConstants.IDLE_MAX_MS).isGreaterThan(AttentionGateConstants.IDLE_MIN_MS)
        assertThat(AttentionGateConstants.TYPING_GRACE_MS)
            .isBetween(AttentionGateConstants.IDLE_MIN_MS, AttentionGateConstants.IDLE_MAX_MS)
        assertThat(AttentionGateConstants.PINGPONG_WINDOW_MS).isGreaterThan(AttentionGateConstants.IDLE_MAX_MS)
    }

    @Test
    fun `니아 자기 발화는 NO_WAKE 이고 핑퐁 앵커만 갱신한다(C8)`() {
        val state = ChannelAttentionGate.ChannelAttentionState()
        val d = ChannelAttentionGate.decide(tsMs = 1_000, isNia = true, hardPolicy = null, state = state)
        assertThat(d.action).isEqualTo(AttentionGateConstants.NO_WAKE)
        assertThat(d.reasonCode).isEqualTo("NIA_SELF")
        assertThat(state.lastNiaTsMs).isEqualTo(1_000)
    }

    @Test
    fun `니아 직전 발화 후 핑퐁 창 내 응답이면 WAKE_NOW(C2)`() {
        val state = ChannelAttentionGate.ChannelAttentionState()
        // 니아 발화로 앵커 설정.
        ChannelAttentionGate.decide(tsMs = 1_000, isNia = true, hardPolicy = null, state = state)
        // 핑퐁 창(20s) 내 사람 응답 — CANDIDATE 라도 핑퐁이 먼저 깨운다.
        val d =
            ChannelAttentionGate.decide(
                tsMs = 1_000 + 5_000,
                isNia = false,
                hardPolicy = ChannelAttentionGate.HARD_CANDIDATE,
                state = state,
            )
        assertThat(d.action).isEqualTo(AttentionGateConstants.WAKE_NOW)
        assertThat(d.reasonCode).isEqualTo("PINGPONG")
    }

    @Test
    fun `핑퐁 창 밖이면 핑퐁 아님(일반 idle 경로)`() {
        val state = ChannelAttentionGate.ChannelAttentionState()
        ChannelAttentionGate.decide(tsMs = 1_000, isNia = true, hardPolicy = null, state = state)
        val d =
            ChannelAttentionGate.decide(
                tsMs = 1_000 + 25_000, // 25s > pingpong_window 20s
                isNia = false,
                hardPolicy = ChannelAttentionGate.HARD_CANDIDATE,
                state = state,
            )
        assertThat(d.action).isEqualTo(AttentionGateConstants.WAKE_AFTER_IDLE)
    }

    @Test
    fun `RESPOND_NOW 면 즉시 WAKE_NOW 이고 idle 대기 해제`() {
        val state = ChannelAttentionGate.ChannelAttentionState(pendingIdleDeadlineMs = 9_999)
        val d =
            ChannelAttentionGate.decide(
                tsMs = 2_000,
                isNia = false,
                hardPolicy = ChannelAttentionGate.HARD_RESPOND_NOW,
                state = state,
            )
        assertThat(d.action).isEqualTo(AttentionGateConstants.WAKE_NOW)
        assertThat(state.pendingIdleDeadlineMs).isNull()
    }

    @Test
    fun `DROP 이면 NO_WAKE`() {
        val state = ChannelAttentionGate.ChannelAttentionState()
        val d =
            ChannelAttentionGate.decide(
                tsMs = 2_000,
                isNia = false,
                hardPolicy = ChannelAttentionGate.HARD_DROP,
                state = state,
            )
        assertThat(d.action).isEqualTo(AttentionGateConstants.NO_WAKE)
        assertThat(d.reasonCode).isEqualTo("DROP")
    }

    @Test
    fun `직전 메시지와 간격이 min_gap 미만이면 WAIT(debounce, C4)`() {
        val state = ChannelAttentionGate.ChannelAttentionState()
        ChannelAttentionGate.decide(tsMs = 1_000, isNia = false, hardPolicy = ChannelAttentionGate.HARD_CANDIDATE, state = state)
        // min_gap=1500ms 미만(1000ms 뒤) 연타 → WAIT.
        val d =
            ChannelAttentionGate.decide(
                tsMs = 1_000 + 1_000,
                isNia = false,
                hardPolicy = ChannelAttentionGate.HARD_CANDIDATE,
                state = state,
            )
        assertThat(d.action).isEqualTo(AttentionGateConstants.WAIT)
        assertThat(d.reasonCode).isEqualTo("DEBOUNCE")
    }

    @Test
    fun `typing 유예 중이면 WAIT(typing grace, C3)`() {
        val state = ChannelAttentionGate.ChannelAttentionState()
        // 첫 메시지로 lastMessage 설정(gap 신호 확보).
        ChannelAttentionGate.decide(tsMs = 1_000, isNia = false, hardPolicy = ChannelAttentionGate.HARD_CANDIDATE, state = state)
        ChannelAttentionGate.onTyping(tsMs = 5_000, state = state) // typing_until = 5_000 + 4_000.
        val d =
            ChannelAttentionGate.decide(
                tsMs = 6_000, // typing 유예(9_000) 내 + 간격 충분.
                isNia = false,
                hardPolicy = ChannelAttentionGate.HARD_CANDIDATE,
                state = state,
            )
        assertThat(d.action).isEqualTo(AttentionGateConstants.WAIT)
    }

    @Test
    fun `일반 CANDIDATE 는 WAKE_AFTER_IDLE 이고 deadline 을 둔다(C5·C7)`() {
        val state = ChannelAttentionGate.ChannelAttentionState()
        val d =
            ChannelAttentionGate.decide(
                tsMs = 1_000,
                isNia = false,
                hardPolicy = ChannelAttentionGate.HARD_CANDIDATE,
                state = state,
            )
        assertThat(d.action).isEqualTo(AttentionGateConstants.WAKE_AFTER_IDLE)
        assertThat(state.pendingIdleDeadlineMs).isNotNull()
        // 표본 없으면 idle = (min+max)/2 = 4500.
        assertThat(d.idleDeadlineMs).isEqualTo(1_000 + 4_500)
    }

    @Test
    fun `dynamic_idle 는 빠른 템포면 idle_min 으로 수렴`() {
        val state =
            ChannelAttentionGate.ChannelAttentionState(
                recentGapsMs = mutableListOf(500, 600, 700), // 모두 idle_min(2000) 이하.
            )
        assertThat(ChannelAttentionGate.dynamicIdleMs(state)).isEqualTo(AttentionGateConstants.IDLE_MIN_MS)
    }

    @Test
    fun `dynamic_idle 는 느린 템포면 idle_max 로 수렴`() {
        val state =
            ChannelAttentionGate.ChannelAttentionState(
                recentGapsMs = mutableListOf(8_000, 9_000, 10_000), // 모두 idle_max(7000) 이상.
            )
        assertThat(ChannelAttentionGate.dynamicIdleMs(state)).isEqualTo(AttentionGateConstants.IDLE_MAX_MS)
    }

    @Test
    fun `idle_due 와 clear_pending`() {
        val state = ChannelAttentionGate.ChannelAttentionState(pendingIdleDeadlineMs = 5_000)
        assertThat(ChannelAttentionGate.idleDue(nowMs = 4_999, state = state)).isFalse()
        assertThat(ChannelAttentionGate.idleDue(nowMs = 5_000, state = state)).isTrue()
        ChannelAttentionGate.clearPending(state)
        assertThat(ChannelAttentionGate.idleDue(nowMs = 9_999, state = state)).isFalse()
    }

    @Test
    fun `gap window 8 을 넘으면 오래된 표본을 버린다`() {
        val state = ChannelAttentionGate.ChannelAttentionState()
        var ts = 0L
        // 9번 메시지로 8개 초과 간격 표본을 만든다.
        repeat(9) {
            ts += 3_000
            ChannelAttentionGate.decide(tsMs = ts, isNia = false, hardPolicy = ChannelAttentionGate.HARD_CANDIDATE, state = state)
        }
        assertThat(state.recentGapsMs.size).isLessThanOrEqualTo(AttentionGateConstants.GAP_WINDOW)
    }
}
