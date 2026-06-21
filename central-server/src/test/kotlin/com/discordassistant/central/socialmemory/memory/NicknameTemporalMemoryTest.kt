package com.discordassistant.central.socialmemory.memory

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.discordassistant.central.socialmemory.domain.service.fact.FactSupersession
import com.discordassistant.central.socialmemory.domain.service.retrieval.MemoryRetrievalRanking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Instant

/**
 * NEXA-P07-T021 닉네임 변경 temporal fact 처리. 합성 fixture(test-fixtures/nexa/memory/nickname-change.yaml,
 * 실제 사용자 데이터 아님)를 로드해 검증한다 — 실제 외부 API·운영 DB 호출 없음.
 *
 * acceptance: 현재 prompt 에는 최신 닉네임("두두")만 기본 제공되고, 과거 회상 시 이전 닉네임("코알라")을 쓸 수 있다.
 * supersession(T008): 과거 사실을 물리 삭제하지 않고 validTo·SUPERSEDED 로 닫아 둘이 공존한다.
 */
class NicknameTemporalMemoryTest {
    private data class FixtureFact(
        val id: String,
        val obj: String,
        val validFrom: Instant,
        val validTo: Instant?,
        val status: MemoryStatus,
        val confidence: Double,
        val events: Set<String>,
    )

    private data class FixtureQuery(
        val asOf: Instant,
        val recall: Boolean,
        val expectObject: String,
    )

    private data class Fixture(
        val scope: VisibilityScope,
        val facts: List<FixtureFact>,
        val supersedes: List<Pair<String, String>>,
        val queries: List<FixtureQuery>,
    )

    @Test
    fun `현재 조회는 최신 닉네임 과거 회상은 이전 닉네임을 낸다`() {
        val fx = loadFixture()
        val facts = fx.facts.map { it.toDomain(fx.scope) }

        fx.queries.forEach { q ->
            // 현재 조회는 ACTIVE 만(rank), 과거 회상은 그 시점 유효했던 닫힌 사실까지(recallAt).
            val ranked =
                if (q.recall) {
                    MemoryRetrievalRanking.recallAt(facts, fx.scope, q.asOf)
                } else {
                    MemoryRetrievalRanking.rank(facts, fx.scope, q.asOf)
                }
            assertEquals(
                listOf(q.expectObject),
                ranked.map { it.fact.obj },
                "asOf=${q.asOf} recall=${q.recall} 에서 기대 닉네임",
            )
        }
    }

    @Test
    fun `supersession 은 과거 사실을 물리 삭제하지 않고 validTo 로 닫는다`() {
        val fx = loadFixture()
        val koala =
            fx.facts
                .first { it.id == "fact-nick-koala" }
                .toDomain(fx.scope)
                .copy(validTo = null, status = MemoryStatus.ACTIVE)
        val dudu = fx.facts.first { it.id == "fact-nick-dudu" }.toDomain(fx.scope)

        val result = FactSupersession.supersede(koala, dudu, supersededAt = dudu.validFrom)
        // 과거 사실은 물리 삭제되지 않고 SUPERSEDED·validTo 로 닫힌다(공존).
        assertEquals(MemoryStatus.SUPERSEDED, result.superseded.status)
        assertEquals(dudu.validFrom, result.superseded.validTo)
        // lineage edge 가 fixture 의 supersedes 와 일치한다.
        assertEquals("fact-nick-koala", result.supersedesEdge.supersededFactId)
        assertEquals("fact-nick-dudu", result.supersedesEdge.supersedingFactId)
        assertEquals(fx.supersedes, listOf("fact-nick-koala" to "fact-nick-dudu"))
    }

    private fun FixtureFact.toDomain(scope: VisibilityScope) =
        TemporalFact(
            id = id,
            visibility = scope,
            subject = "p-koala",
            predicate = "nickname",
            obj = obj,
            validFrom = validFrom,
            validTo = validTo,
            source = MemorySource(events, 1, true, validFrom),
            confidence = Confidence(confidence),
            status = status,
        )

    @Suppress("UNCHECKED_CAST")
    private fun loadFixture(): Fixture {
        val file = File("../test-fixtures/nexa/memory/nickname-change.yaml")
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        check(root["schemaVersion"] == "nexa.memory-fixture.v1") { "예상치 못한 memory fixture 스키마 버전" }
        val base = Instant.parse(root["baseInstant"].toString())
        val scope = VisibilityScope.Guild(root["guildPseudonym"].toString())

        val facts =
            (root["facts"] as List<Map<String, Any?>>).map { f ->
                FixtureFact(
                    id = f["id"].toString(),
                    obj = f["object"].toString(),
                    validFrom = base.plusSeconds((f["validFromOffsetSec"] as Number).toLong()),
                    validTo = (f["validToOffsetSec"] as Number?)?.let { base.plusSeconds(it.toLong()) },
                    status = MemoryStatus.valueOf(f["status"].toString()),
                    confidence = (f["confidence"] as Number).toDouble(),
                    events = (f["sourceEventIds"] as List<Any?>).map { it.toString() }.toSet(),
                )
            }
        val supersedes =
            (root["supersedes"] as List<Map<String, Any?>>).map {
                it["supersededFactId"].toString() to it["supersedingFactId"].toString()
            }
        val queries =
            (root["queries"] as List<Map<String, Any?>>).map {
                FixtureQuery(
                    asOf = base.plusSeconds((it["asOfOffsetSec"] as Number).toLong()),
                    recall = it["recall"] as Boolean,
                    expectObject = it["expectObject"].toString(),
                )
            }
        return Fixture(scope, facts, supersedes, queries)
    }
}
