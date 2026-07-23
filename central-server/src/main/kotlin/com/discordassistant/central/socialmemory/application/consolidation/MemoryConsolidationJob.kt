package com.discordassistant.central.socialmemory.application.consolidation

import com.discordassistant.central.socialmemory.application.extraction.MemoryCandidateExtractorPort
import com.discordassistant.central.socialmemory.application.extraction.MemoryExtractionQueuePort
import com.discordassistant.central.socialmemory.application.extraction.MemoryExtractionRequest
import org.slf4j.LoggerFactory

/**
 * 주기적 memory consolidation **job**(NEXA-P07-T017, application). 비동기 추출 큐에서 작은 batch 씩 요청을 꺼내
 * 후보를 추출·검증·승격한다.
 *
 * **작은 batch·lease(deliverable T017)**: [runOnce] 는 [MemoryExtractionQueuePort.drain] 으로 최대 [batchSize] 개만
 * 꺼낸다 — drain 이 꺼낸 요청을 처리 중으로 표시(lease)해 동시/중복 실행에서 같은 요청을 두 번 선점하지 않는다.
 *
 * **retry(deliverable T017)**: 호출자가 명시적으로 [maxAttempts]를 늘린 경우에만 빈 후보를 제한적으로 재시도한다.
 * 기본값은 총 1회다. 추출기는 정상적인 "기억 후보 없음"과 공급자 실패를 모두 빈 리스트로 표현하므로, 기본 재시도는
 * 정상적인 빈 장면까지 이중 과금할 수 있다.
 *
 * **중복 생성 없음(acceptance T017)**: 저장은 [MemoryConsolidationService] → [PromotedMemoryStorePort.storeIfAbsent]
 * 의 멱등 저장이라, job 재시작·중복 실행·재시도에도 같은 기억이 두 번 만들어지지 않는다.
 *
 * 순수 application: 포트·도메인 서비스만 본다 — JPA/JDA·glm/Z.AI 타입 미참조. 큐·store·추출 어댑터가 모두 붙으면
 * Spring 빈으로 승격한다(현재는 포트만 정의, 단위 테스트는 fake 포트로 검증).
 */
class MemoryConsolidationJob(
    private val queue: MemoryExtractionQueuePort,
    private val extractor: MemoryCandidateExtractorPort,
    private val consolidation: MemoryConsolidationService,
) {
    private val log = LoggerFactory.getLogger(MemoryConsolidationJob::class.java)

    /**
     * 큐에서 [batchSize] 개를 leasing 으로 꺼내 처리하고 요약을 돌려준다. 각 요청 추출은 후보가 빌 때 [maxAttempts]
     * 까지 재시도한다(transient 흡수). 저장은 멱등이라 중복 생성이 없다. 한 요청 처리 실패는 batch 전체를 막지 않는다.
     */
    fun runOnce(
        batchSize: Int = DEFAULT_BATCH_SIZE,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): ConsolidationRunReport {
        require(batchSize > 0) { "batchSize 는 양수여야 한다" }
        require(maxAttempts > 0) { "maxAttempts 는 양수여야 한다" }

        val leased = queue.drain(batchSize)
        var processed = 0
        var stored = 0
        leased.forEach { request ->
            try {
                val candidates = extractWithRetry(request, maxAttempts)
                val results = consolidation.consolidate(candidates)
                stored += results.count { it.stored }
                processed += 1
            } catch (e: Exception) {
                // 한 요청 실패가 batch 전체를 막지 않는다 — 상세는 로그로만, 다음 요청으로 진행.
                log.warn("consolidation 요청 처리 실패(건너뜀): scene={} {}", request.sceneId, e.javaClass.simpleName)
            }
        }
        return ConsolidationRunReport(leased = leased.size, processed = processed, stored = stored)
    }

    /** 후보가 빌 때 [maxAttempts] 까지 재시도(transient). 추출기는 실패를 빈 리스트로 흡수하므로 빈 결과를 재시도 신호로 본다. */
    private fun extractWithRetry(
        request: MemoryExtractionRequest,
        maxAttempts: Int,
    ): List<com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate> {
        repeat(maxAttempts) { attempt ->
            val candidates = extractor.extract(request)
            if (candidates.isNotEmpty()) return candidates
            if (attempt < maxAttempts - 1) {
                log.debug("추출 빈 결과 재시도: scene={} attempt={}", request.sceneId, attempt + 1)
            }
        }
        return emptyList()
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 16
        const val DEFAULT_MAX_ATTEMPTS = 1
    }
}

/** consolidation 한 번 실행 요약(운영 가시성). 원문 미포함 — 건수만. */
data class ConsolidationRunReport(
    val leased: Int,
    val processed: Int,
    val stored: Int,
)
