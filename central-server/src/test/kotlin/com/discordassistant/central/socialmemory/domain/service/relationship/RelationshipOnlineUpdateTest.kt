package com.discordassistant.central.socialmemory.domain.service.relationship

import com.discordassistant.central.socialmemory.domain.model.relationship.InteractionOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** NEXA-P19-T008: 관계 상태 온라인 update — step cap·min sample 로 한 번에 뒤집히지 않고 발산하지 않는다. */
class RelationshipOnlineUpdateTest {
    private val config = RelationshipOnlineUpdate.Config()

    @Test
    fun `acceptance - 한 번의 부정 반응이 장기 긍정 관계를 뒤집지 않는다`() {
        // 오래 쌓인 높은 rapport(0.9), 표본 충분.
        val before = 0.9
        val after =
            RelationshipOnlineUpdate.update(
                current = before,
                outcome = InteractionOutcome.COMPLAINED,
                priorSampleCount = 100,
                config = config,
            )
        // 내려가긴 하지만 step cap 때문에 여전히 높게 유지(0.5 아래로 뒤집히지 않음).
        assertTrue(after < before, "부정 반응은 신호를 낮춘다")
        assertTrue(after > 0.5, "한 번의 부정으로 장기 관계가 뒤집히지 않는다: $after")
    }

    @Test
    fun `acceptance - 한 번의 긍정 반응이 장기 부정 관계를 뒤집지 않는다`() {
        val before = 0.1
        val after =
            RelationshipOnlineUpdate.update(
                current = before,
                outcome = InteractionOutcome.CONTINUED,
                priorSampleCount = 100,
                config = config,
            )
        assertTrue(after > before)
        assertTrue(after < 0.5, "한 번의 긍정으로 장기 관계가 뒤집히지 않는다: $after")
    }

    @Test
    fun `step cap - 한 update 변화량은 maxStep 을 넘지 않는다`() {
        // current 와 target 이 가장 멀고 표본 충분한 worst case.
        val after =
            RelationshipOnlineUpdate.update(
                current = 0.0,
                outcome = InteractionOutcome.CONTINUED,
                priorSampleCount = 1_000_000,
                config = config,
            )
        assertTrue(abs(after - 0.0) <= config.maxStep + 1e-12, "변화량 ≤ maxStep")
    }

    @Test
    fun `min sample - 표본이 적으면 변화가 더 작다 (초기 과신 방지)`() {
        val fewSample =
            RelationshipOnlineUpdate.update(0.5, InteractionOutcome.CONTINUED, priorSampleCount = 1, config = config)
        val manySample =
            RelationshipOnlineUpdate.update(0.5, InteractionOutcome.CONTINUED, priorSampleCount = 100, config = config)
        assertTrue((fewSample - 0.5) < (manySample - 0.5), "적은 표본은 더 천천히 움직인다")
    }

    @Test
    fun `표본 0 이면 변화가 없다 (학습률 0)`() {
        val after = RelationshipOnlineUpdate.update(0.5, InteractionOutcome.CONTINUED, priorSampleCount = 0, config = config)
        assertEquals(0.5, after, 1e-12)
    }

    @Test
    fun `결과는 항상 0~1 범위 (발산 없음)`() {
        var s = 0.5
        repeat(1000) { s = RelationshipOnlineUpdate.update(s, InteractionOutcome.CONTINUED, priorSampleCount = 50, config = config) }
        assertTrue(s in 0.0..1.0)
        repeat(1000) { s = RelationshipOnlineUpdate.update(s, InteractionOutcome.COMPLAINED, priorSampleCount = 50, config = config) }
        assertTrue(s in 0.0..1.0)
    }

    @Test
    fun `반복된 일관 신호는 천천히 수렴한다 (장기 신호 반영)`() {
        var s = 0.5
        repeat(50) { s = RelationshipOnlineUpdate.update(s, InteractionOutcome.CONTINUED, priorSampleCount = 50, config = config) }
        assertTrue(s > 0.8, "일관된 긍정이 누적되면 점진적으로 높아진다: $s")
    }

    @Test
    fun `outcome 부호 매핑은 관찰 행동만 쓴다`() {
        assertEquals(1.0, RelationshipOnlineUpdate.targetSignal(InteractionOutcome.CONTINUED))
        assertEquals(1.0, RelationshipOnlineUpdate.targetSignal(InteractionOutcome.REACTED))
        assertEquals(0.0, RelationshipOnlineUpdate.targetSignal(InteractionOutcome.IGNORED))
        assertEquals(0.0, RelationshipOnlineUpdate.targetSignal(InteractionOutcome.COMPLAINED))
    }
}
