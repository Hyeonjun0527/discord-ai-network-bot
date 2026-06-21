package com.discordassistant.central.speech.context

import com.discordassistant.central.speech.application.context.MemoryContextSelector
import com.discordassistant.central.speech.application.port.out.CandidateMemory
import com.discordassistant.central.speech.application.port.out.MemoryStatus
import com.discordassistant.central.speech.application.port.out.ValidMemoryReadPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T008: 유효 기억 selector — conflicted/expired/deleted 제외, valid/decay만. */
class MemoryContextSelectorTest {
    private fun selector(memories: List<CandidateMemory>) =
        MemoryContextSelector(
            object : ValidMemoryReadPort {
                override fun candidateMemories(
                    guildId: Long,
                    targetPseudonym: String?,
                    limit: Int,
                ): List<CandidateMemory> = memories
            },
        )

    @Test
    fun `excludes expired conflicted and deleted memories`() {
        val memories =
            listOf(
                CandidateMemory("유효한 사실", "observed", 0.9, MemoryStatus.VALID),
                CandidateMemory("감쇠중", "stated", 0.8, MemoryStatus.DECAYING),
                CandidateMemory("만료됨", "observed", 0.95, MemoryStatus.EXPIRED),
                CandidateMemory("충돌됨", "observed", 0.95, MemoryStatus.CONFLICTED),
                CandidateMemory("삭제됨", "observed", 0.95, MemoryStatus.DELETED),
            )
        val refs = selector(memories).select(1L, "user_1", minConfidence = 0.0)
        assertThat(refs.map { it.claim }).containsExactlyInAnyOrder("유효한 사실", "감쇠중")
    }

    @Test
    fun `filters out weak confidence below threshold`() {
        val memories =
            listOf(
                CandidateMemory("강함", "observed", 0.9, MemoryStatus.VALID),
                CandidateMemory("약함", "observed", 0.2, MemoryStatus.VALID),
            )
        val refs = selector(memories).select(1L, null, minConfidence = 0.4)
        assertThat(refs.map { it.claim }).containsExactly("강함")
    }

    @Test
    fun `caps result and sorts by confidence desc`() {
        val memories = (1..20).map { CandidateMemory("m$it", "observed", it / 100.0, MemoryStatus.VALID) }
        val refs = selector(memories).select(1L, null, minConfidence = 0.0)
        assertThat(refs).hasSizeLessThanOrEqualTo(6)
        assertThat(refs.first().confidence).isGreaterThanOrEqualTo(refs.last().confidence)
    }
}
