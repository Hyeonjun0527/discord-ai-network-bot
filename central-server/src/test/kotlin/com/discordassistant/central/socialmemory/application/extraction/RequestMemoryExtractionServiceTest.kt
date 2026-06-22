package com.discordassistant.central.socialmemory.application.extraction

import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P07-T014 비동기 기억 추출 요청. acceptance: 추출(적재) 실패가 응답을 막지 않는다 — 예외를 삼키고 false.
 * speech 경로 분리: 서비스는 큐 적재만 하고 외부 GLM 호출을 하지 않는다(여기서 fake 큐로 증명).
 */
class RequestMemoryExtractionServiceTest {
    private val scope = VisibilityScope.Guild("g-1")

    private fun req(consent: Boolean = true) =
        MemoryExtractionRequest(
            sceneId = "scene-1",
            visibility = scope,
            participants = setOf("p-a", "p-b"),
            extractionVersion = 1,
            consentGranted = consent,
        )

    private class RecordingQueue : MemoryExtractionQueuePort {
        val enqueued = mutableListOf<MemoryExtractionRequest>()

        override fun enqueue(request: MemoryExtractionRequest) {
            enqueued += request
        }

        override fun drain(batchSize: Int): List<MemoryExtractionRequest> = enqueued.take(batchSize)
    }

    private class FailingQueue : MemoryExtractionQueuePort {
        override fun enqueue(request: MemoryExtractionRequest): Unit = throw RuntimeException("queue down")

        override fun drain(batchSize: Int): List<MemoryExtractionRequest> = emptyList()
    }

    @Test
    fun `옵트인 요청은 큐에 적재되고 외부 호출 없이 즉시 반환한다`() {
        val queue = RecordingQueue()
        val accepted = RequestMemoryExtractionService(queue).request(req())
        assertTrue(accepted)
        assertEquals(1, queue.enqueued.size)
    }

    @Test
    fun `동의 없는 요청은 적재하지 않는다(옵트아웃 제외)`() {
        val queue = RecordingQueue()
        val accepted = RequestMemoryExtractionService(queue).request(req(consent = false))
        assertFalse(accepted)
        assertTrue(queue.enqueued.isEmpty())
    }

    @Test
    fun `큐 적재 실패는 예외를 던지지 않고 false 로 흡수한다(응답을 막지 않음)`() {
        val accepted = RequestMemoryExtractionService(FailingQueue()).request(req())
        assertFalse(accepted)
    }
}
