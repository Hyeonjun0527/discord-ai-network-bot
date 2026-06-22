package com.discordassistant.central.socialmemory.domain.service.retrieval

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P07-T020 다양성·중복 억제. acceptance: top-k 가 하나의 오래된 사건 복제본으로 채워지지 않는다 —
 * 같은 source event(같은 사건) 복제본은 cluster 당 제한(perCluster)까지만 통과한다.
 */
class MemoryDiversityFilterTest {
    private val scope = VisibilityScope.Guild("g-1")
    private val base = Instant.parse("2026-01-01T00:00:00Z")

    private fun ranked(
        id: String,
        score: Double,
        events: Set<String>,
        subject: String = "p-a",
        predicate: String = "pred",
        obj: String = "o-$id",
    ) = RankedMemory(
        fact =
            TemporalFact(
                id = id,
                visibility = scope,
                subject = subject,
                predicate = predicate,
                obj = obj,
                validFrom = base,
                validTo = null,
                source =
                    MemorySource(
                        sourceEventIds = events,
                        extractionVersion = 1,
                        consentGranted = true,
                        createdAt = base,
                    ),
                confidence = Confidence(0.8),
            ),
        score = score,
    )

    @Test
    fun `같은 사건 복제본이 top-k 를 점령하지 못한다`() {
        // m1·m2·m3 은 같은 원천 이벤트(같은 사건)에서 나온 복제본 — 점수가 가장 높아도 1개만 통과.
        // d1 은 다른 사건 — 자리를 얻는다.
        val input =
            listOf(
                ranked("m1", 0.99, setOf("event-old")),
                ranked("m2", 0.98, setOf("event-old")),
                ranked("m3", 0.97, setOf("event-old")),
                ranked("d1", 0.50, setOf("event-new")),
            )
        val out = MemoryDiversityFilter.diversify(input, topK = 4, perCluster = 1)
        // 한 사건 복제본 1개 + 다른 사건 1개 — 오래된 사건이 top-k 를 독점하지 않는다.
        assertEquals(setOf("m1", "d1"), out.map { it.fact.id }.toSet())
    }

    @Test
    fun `원천 이벤트가 겹치면 같은 cluster 로 합친다`() {
        // m1(e1) , m2(e1,e2) 는 e1 공유 → 같은 사건. m3(e3) 는 다른 사건.
        val input =
            listOf(
                ranked("m1", 0.9, setOf("e1")),
                ranked("m2", 0.8, setOf("e1", "e2")),
                ranked("m3", 0.7, setOf("e3")),
            )
        val out = MemoryDiversityFilter.diversify(input, topK = 5, perCluster = 1)
        assertEquals(setOf("m1", "m3"), out.map { it.fact.id }.toSet())
    }

    @Test
    fun `perCluster 를 늘리면 같은 사건도 그 수만큼 허용한다`() {
        val input =
            listOf(
                ranked("m1", 0.9, setOf("e1")),
                ranked("m2", 0.8, setOf("e1")),
                ranked("m3", 0.7, setOf("e1")),
            )
        val out = MemoryDiversityFilter.diversify(input, topK = 5, perCluster = 2)
        assertEquals(2, out.size)
    }

    @Test
    fun `topK 상한을 넘지 않는다`() {
        val input = (1..5).map { ranked("m$it", 1.0 - it * 0.1, setOf("e$it")) }
        val out = MemoryDiversityFilter.diversify(input, topK = 3, perCluster = 1)
        assertTrue(out.size <= 3)
    }
}
