package com.discordassistant.central.socialmemory.domain.service.relationship

import com.discordassistant.central.socialmemory.domain.model.relationship.MemberInteractionState
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** NEXA-P06-T005: 교환 burst + 최근성으로 bounded familiarity, 체류 기간만으론 안 오름. */
class FamiliarityCalculatorTest {
    private val key = MemberKey(guildPseudonym = "g#1", memberPseudonym = "m#1")
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    @Test
    fun `acceptance - 교환이 없으면 familiarity 0 (체류 기간만으로 오르지 않음)`() {
        val noExchange = MemberInteractionState.empty(key)
        assertEquals(0.0, FamiliarityCalculator.familiarity(noExchange, now = t0))
    }

    @Test
    fun `교환이 많을수록 familiarity 가 높다`() {
        var few = MemberInteractionState.empty(key)
        repeat(2) { few = few.recordMemberToNexa(t0) }
        var many = MemberInteractionState.empty(key)
        repeat(40) { many = many.recordMemberToNexa(t0) }
        assertTrue(
            FamiliarityCalculator.familiarity(many, now = t0) >
                FamiliarityCalculator.familiarity(few, now = t0),
        )
    }

    @Test
    fun `최근성 감쇠 - 오래된 관계는 약화된다 (영구 낙인 금지)`() {
        var s = MemberInteractionState.empty(key)
        repeat(30) { s = s.recordMemberToNexa(t0) }
        val fresh = FamiliarityCalculator.familiarity(s, now = t0)
        val old = FamiliarityCalculator.familiarity(s, now = t0.plus(Duration.ofDays(60)))
        assertTrue(old < fresh, "오래되면 familiarity 가 감쇠한다")
    }

    @Test
    fun `familiarity 는 0~1 범위`() {
        var s = MemberInteractionState.empty(key)
        repeat(1000) { s = s.recordMemberToNexa(t0) }
        assertTrue(FamiliarityCalculator.familiarity(s, now = t0) in 0.0..1.0)
    }
}
