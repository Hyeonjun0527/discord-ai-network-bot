package com.discordassistant.central.conversation.domain.service.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T011: merge 시 기존 ID 와 과거 decision provenance 가 보존된다. */
class ThreadMergeRuleTest {
    private val location = BurstLocationKey(ChannelId(100L), threadId = null)
    private val threadA = ConversationThreadId.of(location, 0)
    private val threadB = ConversationThreadId.of(location, 1)

    private fun lineage(
        id: ConversationThreadId,
        seq: Long,
        provenance: List<ThreadProvenance>,
    ) = ThreadLineage(threadId = id, createdSequence = seq, provenance = provenance)

    private val reply = ThreadProvenance(kind = ThreadProvenanceKind.MERGE_REPLY, ruleVersion = "v1")

    @Test
    fun `더 오래된 스레드가 canonical 로 살아남고 기존 ID 를 보존한다`() {
        val older =
            lineage(threadA, seq = 1, provenance = listOf(ThreadProvenance(ThreadProvenanceKind.ABSORBED_HISTORY, ruleVersion = "v1")))
        val newer = lineage(threadB, seq = 5, provenance = emptyList())

        val decision = ThreadMergeRule.merge(older, newer, reply)!!

        // canonical = 기존 스레드 id 그대로(새 id 미생성).
        assertEquals(threadA, decision.canonicalThreadId)
        assertEquals(threadB, decision.absorbedThreadId)
    }

    @Test
    fun `merge 방향은 입력 순서와 무관하게 결정론적이다`() {
        val older = lineage(threadA, seq = 1, provenance = emptyList())
        val newer = lineage(threadB, seq = 5, provenance = emptyList())

        val d1 = ThreadMergeRule.merge(older, newer, reply)!!
        val d2 = ThreadMergeRule.merge(newer, older, reply)!!

        assertEquals(d1.canonicalThreadId, d2.canonicalThreadId)
        assertEquals(d1.absorbedThreadId, d2.absorbedThreadId)
    }

    @Test
    fun `흡수된 스레드의 과거 provenance 와 연결 reply 가 보존된다`() {
        val absorbedHistory = ThreadProvenance(ThreadProvenanceKind.ABSORBED_HISTORY, burstId = BurstId("burst:1:1"), ruleVersion = "v1")
        val older = lineage(threadA, seq = 1, provenance = emptyList())
        val newer = lineage(threadB, seq = 5, provenance = listOf(absorbedHistory))

        val decision = ThreadMergeRule.merge(older, newer, reply)!!

        // 흡수된 쪽의 과거 기록이 보존된다(삭제되지 않는다).
        assertTrue(decision.preservedProvenance.contains(absorbedHistory))
        // 연결 reply 근거도 남는다.
        assertTrue(decision.preservedProvenance.contains(reply))
    }

    @Test
    fun `같은 스레드 merge 는 null (불요)`() {
        val a = lineage(threadA, seq = 1, provenance = emptyList())
        assertNull(ThreadMergeRule.merge(a, a, reply))
    }
}
