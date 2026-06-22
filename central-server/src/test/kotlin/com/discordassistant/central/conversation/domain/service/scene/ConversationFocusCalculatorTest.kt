package com.discordassistant.central.conversation.domain.service.scene

import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T016: 활성 thread·최근 target·pending burst 로 focus 분포를 만들고, 활성 스레드 없음을 정상 상태로 표현한다. */
class ConversationFocusCalculatorTest {
    private val location = BurstLocationKey(ChannelId(100L), threadId = null)
    private val threadA = ConversationThreadId.of(location, 0)
    private val threadB = ConversationThreadId.of(location, 1)
    private val version = "focus-v1"

    @Test
    fun `활성 스레드가 전혀 없으면 idle 분포 (정상 상태 acceptance)`() {
        val focus = ConversationFocusCalculator.calculate(emptySet(), emptySet(), emptySet(), ruleVersion = version)
        assertTrue(focus.isIdle)
        assertEquals(1.0, focus.idleProbability)
        assertTrue(focus.candidates.isEmpty())
    }

    @Test
    fun `세 신호가 모이면 가장 강한 스레드가 primary`() {
        val focus =
            ConversationFocusCalculator.calculate(
                activeThreads = setOf(threadA),
                recentTargetThreads = setOf(threadA),
                pendingBurstThreads = setOf(threadA),
                ruleVersion = version,
            )
        assertEquals(threadA, focus.primary!!.threadId)
        assertTrue(
            focus.primary!!.evidence.containsAll(
                setOf(FocusEvidence.ACTIVE_THREAD, FocusEvidence.RECENT_TARGET, FocusEvidence.PENDING_BURST),
            ),
        )
    }

    @Test
    fun `확률 합은 후보 + idle = 1`() {
        val focus =
            ConversationFocusCalculator.calculate(
                activeThreads = setOf(threadA),
                recentTargetThreads = setOf(threadB),
                pendingBurstThreads = emptySet(),
                ruleVersion = version,
            )
        val total = focus.idleProbability + focus.candidates.sumOf { it.probability }
        assertEquals(1.0, total, 1e-9)
    }

    @Test
    fun `약한 신호면 idle 질량이 남는다`() {
        // 활성 신호 1개(가장 약한 activeThread)만 → idleBaseline 이 남아 idle 확률 > 0.
        val focus =
            ConversationFocusCalculator.calculate(
                activeThreads = setOf(threadA),
                recentTargetThreads = emptySet(),
                pendingBurstThreads = emptySet(),
                ruleVersion = version,
            )
        assertTrue(focus.idleProbability > 0.0)
    }

    @Test
    fun `결정론 — 같은 입력이면 같은 분포`() {
        fun calc() =
            ConversationFocusCalculator.calculate(
                activeThreads = setOf(threadA, threadB),
                recentTargetThreads = setOf(threadA),
                pendingBurstThreads = setOf(threadB),
                ruleVersion = version,
            )
        assertEquals(calc(), calc())
    }
}
