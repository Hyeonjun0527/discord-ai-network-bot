package com.discordassistant.central.socialmemory.domain.service.retrieval

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P07-T019 retrieval 랭킹. acceptance: expired/conflicted/deleted·valid 구간 밖·다른 스코프 기억은 점수와
 * 무관하게 필터링된다. T018 연계: asOf 에 따라 현재/과거 조회가 다른 결과를 낸다.
 */
class MemoryRetrievalRankingTest {
    private val scope = VisibilityScope.Guild("g-1")
    private val base = Instant.parse("2026-01-01T00:00:00Z")

    private fun source(events: Set<String> = setOf("e1")) =
        MemorySource(sourceEventIds = events, extractionVersion = 1, consentGranted = true, createdAt = base)

    private fun fact(
        id: String,
        obj: String,
        validFrom: Instant = base,
        validTo: Instant? = null,
        status: MemoryStatus = MemoryStatus.ACTIVE,
        conf: Double = 0.8,
        visibility: VisibilityScope = scope,
        events: Set<String> = setOf("e-$id"),
    ) = TemporalFact(
        id = id,
        visibility = visibility,
        subject = "p-a",
        predicate = "uses_language",
        obj = obj,
        validFrom = validFrom,
        validTo = validTo,
        source = source(events),
        confidence = Confidence(conf),
        status = status,
    )

    @Test
    fun `높은 confidence 라도 SUPERSEDED 는 필터링된다`() {
        val ranked =
            MemoryRetrievalRanking.rank(
                facts = listOf(fact("f1", "java", status = MemoryStatus.SUPERSEDED, conf = 1.0)),
                requesterScope = scope,
                now = base.plusSeconds(60),
            )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `CONFLICTED 와 INVALIDATED 와 EXPIRED 는 점수와 무관하게 제외된다`() {
        val facts =
            listOf(
                fact("c", "a", status = MemoryStatus.CONFLICTED, conf = 1.0),
                fact("i", "b", status = MemoryStatus.INVALIDATED, conf = 1.0),
                fact("e", "c", status = MemoryStatus.EXPIRED, conf = 1.0),
                fact("ok", "d", status = MemoryStatus.ACTIVE, conf = 0.4),
            )
        val ranked = MemoryRetrievalRanking.rank(facts, scope, base.plusSeconds(60))
        assertEquals(listOf("ok"), ranked.map { it.fact.id })
    }

    @Test
    fun `다른 guild 스코프 기억은 노출되지 않는다`() {
        val other = fact("o", "x", visibility = VisibilityScope.Guild("g-2"), conf = 1.0)
        val ranked = MemoryRetrievalRanking.rank(listOf(other), scope, base.plusSeconds(60))
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `asOf 현재 조회와 과거 시점 조회가 다른 결과를 낸다`() {
        // 과거 사실(java): valid [base, base+1h] 로 닫힘(supersession 으로 닫힌 ACTIVE 가 아니라 과거 구간).
        // 현재 사실(python): valid [base+1h, open).
        val past = fact("past", "java", validFrom = base, validTo = base.plusSeconds(3600))
        val current = fact("cur", "python", validFrom = base.plusSeconds(3600), validTo = null)
        val facts = listOf(past, current)

        // 과거 시점(base+30분) 조회: java 만 유효.
        val atPast = MemoryRetrievalRanking.rank(facts, scope, base.plusSeconds(1800))
        assertEquals(listOf("past"), atPast.map { it.fact.id })

        // 현재 시점(base+2h) 조회: python 만 유효(java 는 validTo 지남).
        val atNow = MemoryRetrievalRanking.rank(facts, scope, base.plusSeconds(7200))
        assertEquals(listOf("cur"), atNow.map { it.fact.id })
    }

    @Test
    fun `현재 열린 사실이 닫힌 과거 사실보다 같은 조건에서 우대된다`() {
        val openCurrent = fact("open", "python", validFrom = base, validTo = null, conf = 0.6)
        // 과거 구간이지만 asOf 안: validTo 가 asOf 이후.
        val closedButValid = fact("closed", "java", validFrom = base, validTo = base.plusSeconds(7200), conf = 0.6)
        val at = base.plusSeconds(60)
        val s1 = MemoryRetrievalRanking.score(openCurrent, at)
        val s2 = MemoryRetrievalRanking.score(closedButValid, at)
        assertTrue(s1 > s2)
        assertFalse(s1 > 1.0)
    }
}
