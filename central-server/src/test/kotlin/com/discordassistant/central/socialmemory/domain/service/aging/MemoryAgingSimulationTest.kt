package com.discordassistant.central.socialmemory.domain.service.aging

import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.discordassistant.central.socialmemory.domain.service.retention.MemoryRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * 기억 노화·압축 장기 실험 테스트(NEXA-P19-T018). 90일 event volume 에서 TTL 만료·압축·retrieval 을 측정하고,
 * **압축으로 provenance·삭제 가능성·현재성이 사라지지 않는지** 검증한다(acceptance T018).
 */
class MemoryAgingSimulationTest {
    private val now = Instant.parse("2026-06-22T00:00:00Z")

    private fun source(vararg eventIds: String) =
        MemorySource(
            sourceEventIds = eventIds.toSet(),
            extractionVersion = 1,
            consentGranted = true,
            createdAt = now.minus(Duration.ofDays(30)),
        )

    private fun item(
        id: String,
        ageDays: Long,
        key: String,
        source: MemorySource,
        kind: MemoryRetentionPolicy = MemoryRetentionPolicy.EPISODIC,
    ) = AgingMemoryItem(
        id = id,
        kind = kind,
        occurredAt = now.minus(Duration.ofDays(ageDays)),
        observedAt = now.minus(Duration.ofDays(ageDays)),
        consolidationKey = key,
        source = source,
        confidence = 0.6,
    )

    @Test
    fun `TTL 지난 일화는 만료로 분류된다(EPISODIC 90일)`() {
        val items =
            listOf(
                item("old", ageDays = 120, key = "k1", source = source("e1")),
                item("fresh", ageDays = 3, key = "k2", source = source("e2")),
            )
        val partition = MemoryAgingSimulation.classifyExpired(items, now)
        assertThat(partition.expired.map { it.id }).containsExactly("old")
        assertThat(partition.alive.map { it.id }).containsExactly("fresh")
    }

    @Test
    fun `acceptance — 압축은 출처(provenance)를 합집합으로 보존한다(삭제 가능성 유지)`() {
        val items =
            listOf(
                item("a", ageDays = 10, key = "topic-x", source = source("e1", "e2")),
                item("b", ageDays = 5, key = "topic-x", source = source("e3")),
            )
        val consolidated = MemoryAgingSimulation.consolidate(items)
        assertThat(consolidated).hasSize(1)
        val merged = consolidated.first()
        // 출처 합집합 보존 — 어떤 원본 이벤트도 사라지지 않는다.
        assertThat(merged.source.sourceEventIds).containsExactlyInAnyOrder("e1", "e2", "e3")
        // 삭제 전파가 여전히 동작한다(e1 삭제 시 나머지 출처로 잔존).
        val afterDelete = merged.source.withoutEvents(setOf("e1"))
        assertThat(afterDelete).isNotNull
        assertThat(afterDelete!!.sourceEventIds).containsExactlyInAnyOrder("e2", "e3")
    }

    @Test
    fun `acceptance — 압축은 가장 최근 관찰 시각(현재성)을 유지한다`() {
        val items =
            listOf(
                item("a", ageDays = 20, key = "topic-x", source = source("e1")),
                item("b", ageDays = 2, key = "topic-x", source = source("e2")),
            )
        val merged = MemoryAgingSimulation.consolidate(items).first()
        // 오래된 항목과 묶여도 현재성(최근 관찰)을 잃지 않는다.
        assertThat(merged.observedAt).isEqualTo(now.minus(Duration.ofDays(2)))
    }

    @Test
    fun `acceptance — 90일 시뮬레이션 리포트는 출처·현재성 보존을 증명한다`() {
        // 90일에 걸친 합성 volume: 일부 만료, 같은 주제 다수 압축.
        val items =
            buildList {
                for (d in listOf(120L, 100L)) {
                    add(item("expired-$d", ageDays = d, key = "old", source = source("oe-$d")))
                }
                for (i in 0 until 6) {
                    add(item("topicA-$i", ageDays = (i + 1).toLong(), key = "topicA", source = source("ae-$i")))
                }
                add(item("solo", ageDays = 4, key = "solo", source = source("se-1")))
            }
        val report = MemoryAgingSimulation.simulate(items, now, recencyWindow = Duration.ofDays(7))

        assertThat(report.expiredCount).isEqualTo(2)
        // topicA 6개 → 1개로 압축 + solo 1개 = 2개(압축 효과).
        assertThat(report.aliveAfterConsolidation).isLessThan(report.aliveBeforeConsolidation)
        assertThat(report.compressionRatio).isLessThan(1.0)
        // 압축으로 사라진 출처 0 — provenance·삭제 가능성 유지.
        assertThat(report.lostSourceEventIds).isEqualTo(0)
        assertThat(report.provenancePreserved).isTrue()
        // 현재성 보존 — 압축 후에도 최근 항목이 동일하게 조회 가능.
        assertThat(report.recencyPreserved).isTrue()
    }
}
