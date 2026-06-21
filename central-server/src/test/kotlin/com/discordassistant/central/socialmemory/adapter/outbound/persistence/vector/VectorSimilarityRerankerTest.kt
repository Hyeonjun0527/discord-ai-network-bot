package com.discordassistant.central.socialmemory.adapter.outbound.persistence.vector

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.discordassistant.central.socialmemory.domain.service.retrieval.MemoryRetrievalRanking
import com.discordassistant.central.socialmemory.domain.service.retrieval.RankedMemory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P07-T023 pgvector 보조 reranker(순수, DB 불필요). acceptance: vector top-k 가 validity 필터를 우회하지
 * 않는다 — 정형 필터(랭킹)를 통과한 집합 안에서만 재정렬하고, 제외된 기억을 새로 끌어오지 않는다.
 */
class VectorSimilarityRerankerTest {
    private val scope = VisibilityScope.Guild("g-1")
    private val base = Instant.parse("2026-01-01T00:00:00Z")

    private fun fact(
        id: String,
        obj: String,
        status: MemoryStatus = MemoryStatus.ACTIVE,
        validTo: Instant? = null,
    ) = TemporalFact(
        id = id,
        visibility = scope,
        subject = "p-a",
        predicate = "pred",
        obj = obj,
        validFrom = base,
        validTo = validTo,
        source = MemorySource(setOf("e-$id"), 1, true, base),
        confidence = Confidence(0.7),
        status = status,
    )

    @Test
    fun `validity 필터를 통과한 집합 안에서만 의미 유사도로 재정렬한다`() {
        // a 는 query 와 유사, b 는 비유사. 둘 다 ACTIVE 라 정형 필터 통과.
        val filtered =
            listOf(
                RankedMemory(fact("a", "java"), 0.50),
                RankedMemory(fact("b", "python"), 0.55),
            )
        val query = floatArrayOf(1f, 0f)
        val embeddings = mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0f, 1f))

        val out = VectorSimilarityReranker.rerank(filtered, query, embeddings, vectorWeight = 0.6)
        // a 는 코사인 1.0 가중으로 b 를 추월한다(보조 의미 정렬). 집합 크기는 그대로(추가 없음).
        assertEquals(listOf("a", "b"), out.map { it.fact.id })
        assertEquals(2, out.size)
    }

    @Test
    fun `정형 필터에서 제외된 기억은 reranker 가 끌어오지 못한다(validity 우회 금지)`() {
        // SUPERSEDED 사실은 랭킹(정형 필터)에서 이미 제외된다 → reranker 입력에 없다 → 결과에도 없다.
        val all = listOf(fact("active", "java"), fact("dead", "python", status = MemoryStatus.SUPERSEDED))
        val filtered = MemoryRetrievalRanking.rank(all, scope, base.plusSeconds(60))
        assertEquals(listOf("active"), filtered.map { it.fact.id })

        // 죽은 기억에 강한 임베딩을 줘도 — 입력에 없으니 결과에 없다.
        val query = floatArrayOf(0f, 1f)
        val embeddings = mapOf("dead" to floatArrayOf(0f, 1f), "active" to floatArrayOf(1f, 0f))
        val out = VectorSimilarityReranker.rerank(filtered, query, embeddings)
        assertEquals(listOf("active"), out.map { it.fact.id })
        assertFalse(out.any { it.fact.id == "dead" })
    }

    @Test
    fun `임베딩 없거나 query 없으면 기존 랭킹을 유지한다(fallback)`() {
        val filtered = listOf(RankedMemory(fact("a", "x"), 0.9), RankedMemory(fact("b", "y"), 0.8))
        // query 없음 → 그대로.
        val noQuery = VectorSimilarityReranker.rerank(filtered, null, emptyMap())
        assertEquals(listOf("a", "b"), noQuery.map { it.fact.id })
        // 임베딩 없음 → 기존 점수 정렬 유지.
        val noEmb = VectorSimilarityReranker.rerank(filtered, floatArrayOf(1f, 0f), emptyMap())
        assertEquals(listOf("a", "b"), noEmb.map { it.fact.id })
        assertTrue(noEmb.all { it.score <= 1.0 })
    }
}
