package com.discordassistant.central.conversation.domain.service.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T012: event store 재생 없이 임의 mutation 하지 않고 correction event 가 남는다. */
class ThreadSplitRuleTest {
    private val location = BurstLocationKey(ChannelId(100L), threadId = null)
    private val original = ConversationThreadId.of(location, 0)
    private val splitOff = ConversationThreadId.of(location, 1)
    private val misassigned = setOf(BurstId("burst:2:7"), BurstId("burst:2:9"))

    @Test
    fun `잘못 합쳐진 burst 를 새 스레드로 분리하고 원래 스레드 id 를 유지한다`() {
        val decision = ThreadSplitRule.split(original, splitOff, misassigned, ruleVersion = "v1")

        assertEquals(original, decision.keptThreadId)
        assertEquals(splitOff, decision.splitThreadId)
        assertEquals(misassigned, decision.movedBursts)
    }

    @Test
    fun `correction provenance 가 SPLIT_OFF 로 남는다 (event store 재생 불요)`() {
        val decision = ThreadSplitRule.split(original, splitOff, misassigned, ruleVersion = "v1")

        assertEquals(ThreadProvenanceKind.SPLIT_OFF, decision.correction.kind)
        // correction event 가 어느 burst 가 갈라졌는지 식별자로 남긴다(임의 mutation 아님).
        assertTrue(decision.correction.burstId in misassigned)
    }

    @Test
    fun `결정론 — 같은 입력이면 같은 correction burst (정렬 최소값)`() {
        val d1 = ThreadSplitRule.split(original, splitOff, misassigned, ruleVersion = "v1")
        val d2 = ThreadSplitRule.split(original, splitOff, misassigned, ruleVersion = "v1")
        assertEquals(d1.correction.burstId, d2.correction.burstId)
    }

    @Test
    fun `빈 split 은 거부 (분리할 burst 없음)`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadSplitRule.split(original, splitOff, emptySet(), ruleVersion = "v1")
        }
    }
}
