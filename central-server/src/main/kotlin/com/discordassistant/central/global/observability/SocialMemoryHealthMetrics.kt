package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * stale memory·conflict metric(NEXA-P18-T009). 사회 기억(socialmemory)의 건강 지표를 **집계** 로 노출한다 —
 * 무효(만료/철회) 기억 검색 차단 수, conflict rate, 삭제 backlog.
 *
 * **acceptance(T009) — 실제 memory object 를 metric 에 노출하지 않는다**:
 *  - 모든 metric 은 **카운트/gauge 집계뿐** — memory 내용·key·소유자 ID 를 label 이나 값으로 노출하지 않는다.
 *  - conflict rate 는 `nexa_memory_conflict_total / nexa_memory_retrieval_total` 로 유도한다(비율 자체를 저장하지
 *    않고 카운터로 — 원문 없이).
 *
 * deletion backlog 는 마지막 게시값을 들고 있는 gauge(삭제 잡이 주기적으로 갱신).
 */
@Component
class SocialMemoryHealthMetrics(
    private val meter: MeterRegistry,
) {
    private val deletionBacklog = AtomicLong(0)

    init {
        meter.gauge("nexa_memory_deletion_backlog", deletionBacklog) { it.get().toDouble() }
    }

    /** 무효(만료/철회) 기억 검색이 차단된 1건을 기록한다(stale 차단이 작동했다는 신호). */
    fun recordInvalidRetrievalBlocked() {
        meter.counter("nexa_memory_invalid_retrieval_blocked_total").increment()
    }

    /**
     * 한 기억 검색을 기록한다. [conflicted] 면 conflict 도 함께 센다 — conflict rate 는 소비자가
     * `nexa_memory_conflict_total / nexa_memory_retrieval_total` 로 유도한다(원문 없이 비율만).
     */
    fun recordRetrieval(conflicted: Boolean) {
        meter.counter("nexa_memory_retrieval_total").increment()
        if (conflicted) {
            meter.counter("nexa_memory_conflict_total").increment()
        }
    }

    /** 삭제 대기 backlog 크기를 게시한다(삭제 잡이 주기적으로 갱신). 음수 불가. */
    fun publishDeletionBacklog(size: Long) {
        require(size >= 0) { "deletion backlog 는 음수일 수 없다" }
        deletionBacklog.set(size)
    }
}
