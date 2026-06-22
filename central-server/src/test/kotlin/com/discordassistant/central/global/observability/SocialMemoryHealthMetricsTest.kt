package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * NEXA-P18-T009 acceptance: invalid memory retrieval 차단·conflict rate·deletion backlog 를 측정하되, **실제 memory
 * object 를 metric 에 노출하지 않는다**(집계만).
 */
class SocialMemoryHealthMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = SocialMemoryHealthMetrics(registry)

    @Test
    fun `counts invalid retrieval blocks and conflict rate`() {
        metrics.recordInvalidRetrievalBlocked()
        metrics.recordInvalidRetrievalBlocked()
        repeat(10) { metrics.recordRetrieval(conflicted = false) }
        repeat(2) { metrics.recordRetrieval(conflicted = true) }

        assertEquals(2.0, registry.find("nexa_memory_invalid_retrieval_blocked_total").counter()!!.count())
        // conflict rate = conflict / retrieval = 2 / 12.
        val retr = registry.find("nexa_memory_retrieval_total").counter()!!.count()
        val conflict = registry.find("nexa_memory_conflict_total").counter()!!.count()
        assertEquals(12.0, retr)
        assertEquals(2.0, conflict)
    }

    @Test
    fun `publishes deletion backlog as a gauge`() {
        metrics.publishDeletionBacklog(7)
        assertEquals(7.0, registry.find("nexa_memory_deletion_backlog").gauge()!!.value())
    }

    @Test
    fun `does not expose memory object content as labels`() {
        metrics.recordRetrieval(conflicted = true)
        // 어떤 metric 도 label 을 달지 않는다(memory key/소유자 비노출).
        registry.meters.forEach { m -> assertTrue(m.id.tags.isEmpty(), "memory metric 은 label 없이 집계만: ${m.id.name}") }
    }

    @Test
    fun `rejects negative backlog`() {
        assertThrows<IllegalArgumentException> { metrics.publishDeletionBacklog(-1) }
    }
}
