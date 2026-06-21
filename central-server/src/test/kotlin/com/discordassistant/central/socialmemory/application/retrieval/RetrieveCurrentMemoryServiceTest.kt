package com.discordassistant.central.socialmemory.application.retrieval

import com.discordassistant.central.socialmemory.application.port.inbound.MemoryQueryCriteria
import com.discordassistant.central.socialmemory.application.port.out.TemporalFactReadPort
import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P07-T018 현재 유효 기억 조회 포트. acceptance: 현재/과거 시점 조회가 다른 결과(asOf), threshold·scope 적용.
 */
class RetrieveCurrentMemoryServiceTest {
    private val scope = VisibilityScope.Guild("g-1")
    private val base = Instant.parse("2026-01-01T00:00:00Z")

    private fun fact(
        id: String,
        obj: String,
        validFrom: Instant,
        validTo: Instant?,
        conf: Double = 0.8,
    ) = TemporalFact(
        id = id,
        visibility = scope,
        subject = "p-a",
        predicate = "uses_language",
        obj = obj,
        validFrom = validFrom,
        validTo = validTo,
        source = MemorySource(setOf("e-$id"), 1, true, base),
        confidence = Confidence(conf),
    )

    private fun reader(facts: List<TemporalFact>) = TemporalFactReadPort { facts }

    @Test
    fun `현재 조회와 과거 조회가 다른 사실을 돌려준다`() {
        val past = fact("past", "java", base, base.plusSeconds(3600))
        val current = fact("cur", "python", base.plusSeconds(3600), null)
        val svc = RetrieveCurrentMemoryService(reader(listOf(past, current)))

        val atPast = svc.query(MemoryQueryCriteria(asOf = base.plusSeconds(1800), requesterScope = scope))
        val atNow = svc.query(MemoryQueryCriteria(asOf = base.plusSeconds(7200), requesterScope = scope))

        assertEquals(listOf("java"), atPast.map { it.obj })
        assertEquals(listOf("python"), atNow.map { it.obj })
    }

    @Test
    fun `minConfidence 미만 사실은 제외된다`() {
        val low = fact("low", "x", base, null, conf = 0.3)
        val high = fact("high", "y", base, null, conf = 0.9)
        val svc = RetrieveCurrentMemoryService(reader(listOf(low, high)))
        val out = svc.query(MemoryQueryCriteria(asOf = base.plusSeconds(60), requesterScope = scope, minConfidence = 0.5))
        assertEquals(listOf("y"), out.map { it.obj })
    }

    @Test
    fun `subject 좁힘이 적용된다`() {
        val svc = RetrieveCurrentMemoryService(reader(listOf(fact("a", "x", base, null))))
        val out = svc.query(MemoryQueryCriteria(asOf = base.plusSeconds(60), requesterScope = scope, subject = "other"))
        assertTrue(out.isEmpty())
    }
}
