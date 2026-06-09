package com.discordassistant.central.relay

import com.discordassistant.central.relay.protocol.ChunkFrame
import com.discordassistant.central.relay.protocol.InferResult
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * 진행 중 요청의 상관관계 표 — requestId → 완료 핸들(비스트리밍 future / 스트리밍 청크 큐).
 *
 * ProviderSession 에서 분리(SE-137): 세션은 상태머신·전송 오케스트레이션·inFlight 카운팅에 집중하고,
 * "어떤 요청이 어떤 완료 핸들에 매달려 있는가"의 등록·완료·정리만 여기서 소유한다.
 *
 * 동시성: 두 맵 모두 ConcurrentHashMap — read-loop 스레드(complete/failPending/offer)와
 * 요청 스레드(register/remove)가 락 없이 안전하게 핸드오프한다. 개별 연산은 원자적이고,
 * CompletableFuture.complete/completeExceptionally 는 1회만 성공한다(중복은 무시).
 * inFlight 카운터·상태 전이는 ProviderSession 이 소유하므로, 정리 시 세션이 removePending 과
 * inFlight/상태 조정을 함께 수행한다(분산 불변식은 세션 한 곳에서 조율).
 */
class RequestLifecycleManager {
    private val pending = ConcurrentHashMap<String, CompletableFuture<InferResult>>()
    private val streams = ConcurrentHashMap<String, BlockingQueue<ChunkFrame>>()

    /** 비스트리밍 요청 등록(전송 전 — 응답이 먼저 도착해도 잡도록). */
    fun registerPending(
        requestId: String,
        future: CompletableFuture<InferResult>,
    ) {
        pending[requestId] = future
    }

    /** 스트리밍 요청의 청크 큐 등록(청크 도착 전에 먼저 — 유실 방지). */
    fun registerStream(
        requestId: String,
        queue: BlockingQueue<ChunkFrame>,
    ) {
        streams[requestId] = queue
    }

    /** 결과 도착 → 해당 future 완료(없으면 무시 — 이미 정리/타임아웃됨). */
    fun complete(
        requestId: String,
        result: InferResult,
    ) {
        pending[requestId]?.complete(result)
    }

    /** 에러 도착 → 해당 future 예외 완료(없으면 무시). */
    fun failPending(
        requestId: String,
        error: Throwable,
    ) {
        pending[requestId]?.completeExceptionally(error)
    }

    /** 청크 도착 → 해당 스트림 큐에 적재(없으면 무시 — 이미 종료/정리됨). */
    fun offer(
        requestId: String,
        chunk: ChunkFrame,
    ) {
        streams[requestId]?.offer(chunk)
    }

    /** 비스트리밍 요청 표에서 제거(정리). inFlight·상태 조정은 호출자(ProviderSession)가 수행. */
    fun removePending(requestId: String) {
        pending.remove(requestId)
    }

    /** 스트림 큐 제거(드레인 완료/종료 finally). */
    fun removeStream(requestId: String) {
        streams.remove(requestId)
    }

    /** 연결 종료: 대기 중 모든 future 를 실패 처리하고 두 표를 비운다. */
    fun failAll(reason: String) {
        pending.values.forEach { it.completeExceptionally(ConnectionClosedException(reason)) }
        pending.clear()
        streams.clear()
    }
}
