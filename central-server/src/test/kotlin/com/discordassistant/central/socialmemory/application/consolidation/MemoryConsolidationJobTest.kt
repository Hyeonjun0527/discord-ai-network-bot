package com.discordassistant.central.socialmemory.application.consolidation

import com.discordassistant.central.socialmemory.application.extraction.MemoryCandidateExtractorPort
import com.discordassistant.central.socialmemory.application.extraction.MemoryExtractionQueuePort
import com.discordassistant.central.socialmemory.application.extraction.MemoryExtractionRequest
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.extraction.CandidateKind
import com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.discordassistant.central.socialmemory.domain.service.consolidation.PromotionReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P07-T016/T017 consolidation 서비스+job. acceptance: 모든 결과 reason code(T016), 재시작/중복 실행에도
 * 같은 기억이 중복 생성되지 않는다(T017 멱등 store). lease(batch drain)·retry(빈 후보 재시도) 검증.
 */
class MemoryConsolidationJobTest {
    private val scope = VisibilityScope.Guild("g-1")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun req(scene: String) =
        MemoryExtractionRequest(
            sceneId = scene,
            visibility = scope,
            participants = setOf("p-a"),
            extractionVersion = 1,
            consentGranted = true,
        )

    private fun candidate(scene: String) =
        MemoryCandidate(
            kind = CandidateKind.TEMPORAL_FACT,
            visibility = scope,
            subject = "p-a",
            predicate = "uses_language",
            obj = "python",
            source =
                MemorySource(
                    sourceEventIds = setOf(scene),
                    extractionVersion = 1,
                    consentGranted = true,
                    createdAt = now,
                ),
        )

    /** lease 모사: drain 한 요청은 큐에서 제거(재선점 방지). */
    private class LeasingQueue(
        initial: List<MemoryExtractionRequest>,
    ) : MemoryExtractionQueuePort {
        private val pending = ArrayDeque(initial)

        override fun enqueue(request: MemoryExtractionRequest) = pending.addLast(request)

        override fun drain(batchSize: Int): List<MemoryExtractionRequest> {
            val out = mutableListOf<MemoryExtractionRequest>()
            repeat(minOf(batchSize, pending.size)) { out += pending.removeFirst() }
            return out
        }
    }

    /** 멱등 store 모사: 같은 동일성 키(subject/predicate/object)는 한 번만 저장된다. */
    private class IdempotentStore : PromotedMemoryStorePort {
        val stored = mutableSetOf<String>()

        private fun key(c: MemoryCandidate) = "${c.visibility.guildPseudonym}:${c.subject}:${c.predicate}:${c.obj}"

        override fun findActiveFacts(candidate: MemoryCandidate): List<TemporalFact> = emptyList()

        override fun storeIfAbsent(candidate: MemoryCandidate): Boolean = stored.add(key(candidate))
    }

    @Test
    fun `lease 로 batch 만큼만 처리하고 결과를 저장한다`() {
        val queue = LeasingQueue(listOf(req("s1"), req("s2"), req("s3")))
        val store = IdempotentStore()
        val extractor =
            MemoryCandidateExtractorPort { request -> listOf(candidate(request.sceneId)) }
        val job = MemoryConsolidationJob(queue, extractor, MemoryConsolidationService(store))

        val report = job.runOnce(batchSize = 2)
        assertEquals(2, report.leased)
        assertEquals(2, report.processed)
        // s1·s2 는 같은 (subject/predicate/object) 라 멱등 store 가 한 번만 저장 — 중복 생성 없음.
        assertEquals(1, report.stored)
    }

    @Test
    fun `재시작 중복 실행에도 같은 기억이 중복 생성되지 않는다(멱등)`() {
        val store = IdempotentStore()
        val extractor = MemoryCandidateExtractorPort { request -> listOf(candidate(request.sceneId)) }

        // 같은 요청을 두 번 처리(재시작/중복 실행 모사).
        val job1 = MemoryConsolidationJob(LeasingQueue(listOf(req("s1"))), extractor, MemoryConsolidationService(store))
        val r1 = job1.runOnce()
        val job2 = MemoryConsolidationJob(LeasingQueue(listOf(req("s1"))), extractor, MemoryConsolidationService(store))
        val r2 = job2.runOnce()

        assertEquals(1, r1.stored) // 처음엔 저장
        assertEquals(0, r2.stored) // 두 번째는 멱등으로 중복 생성 안 함
        assertEquals(1, store.stored.size)
    }

    @Test
    fun `빈 후보는 maxAttempts 까지 재시도한다`() {
        var attempts = 0
        val extractor =
            MemoryCandidateExtractorPort { request ->
                attempts += 1
                if (attempts >= 2) listOf(candidate(request.sceneId)) else emptyList()
            }
        val store = IdempotentStore()
        val job = MemoryConsolidationJob(LeasingQueue(listOf(req("s1"))), extractor, MemoryConsolidationService(store))

        val report = job.runOnce(maxAttempts = 2)
        assertEquals(2, attempts) // 첫 시도 빈 결과 → 재시도 → 후보
        assertEquals(1, report.stored)
    }

    @Test
    fun `모든 consolidation 결과에 reason code 가 남는다`() {
        val store = IdempotentStore()
        val results = MemoryConsolidationService(store).consolidate(listOf(candidate("s1")))
        // reason code 는 enum 이라 타입상 항상 존재한다 — 저장 후보의 reason 이 PROVENANCE_OK 임을 확인.
        assertEquals(
            listOf(PromotionReason.PROVENANCE_OK),
            results.map { it.reason },
        )
        assertTrue(results.all { it.stored })
    }
}
