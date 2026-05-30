package com.discordassistant.central.relay

import com.discordassistant.central.domain.ProviderState
import com.discordassistant.central.relay.protocol.CancelFrame
import com.discordassistant.central.relay.protocol.ChunkFrame
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferError
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import com.discordassistant.central.relay.protocol.ProviderHelloFrame
import com.discordassistant.central.relay.protocol.filterOptions
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 프로바이더 제공 능력(provider_hello 로 보고). */
data class ProviderCapability(
    val models: List<String> = emptyList(),
    val maxConcurrency: Int = 1,
    val remainingDailyRequests: Int = 0,
)

/**
 * 단일 에이전트 연결 세션. 상태머신·capability·heartbeat·request↔future 상관관계와
 * per-session 동시 처리 상한(BUSY)·요청 타임아웃(→cancel)을 관리한다.
 *
 * 동시성: request↔future 맵은 ConcurrentHashMap. 진정한 순차 큐(동시 1 초과를 대기시키는)는
 * K-차수 11 에서 다룬다. 여기서는 (maxConcurrency + maxQueue) 하드 캡으로 BUSY 를 낸다.
 */
class ProviderSession(
    val connection: AgentConnection,
    val providerId: Long,
    val guildId: Long?,
    var capability: ProviderCapability = ProviderCapability(),
    private val requestTimeoutSeconds: Long = 120,
    private val maxQueue: Int = 16,
) {
    private val log = LoggerFactory.getLogger(ProviderSession::class.java)

    private val stateRef = AtomicReference(ProviderState.ONLINE_IDLE)
    private val lastSeenNanos = AtomicLong(System.nanoTime())
    private val inFlight = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableFuture<InferResult>>()
    private val streams = ConcurrentHashMap<String, java.util.concurrent.BlockingQueue<ChunkFrame>>()

    val state: ProviderState get() = stateRef.get()
    val activeRequests: Int get() = inFlight.get()

    fun markSeen() = lastSeenNanos.set(System.nanoTime())

    fun isStale(timeoutSeconds: Long, nowNanos: Long = System.nanoTime()): Boolean =
        nowNanos - lastSeenNanos.get() > TimeUnit.SECONDS.toNanos(timeoutSeconds)

    /** 상태 전이(불가 전이는 거부하지 않고 로깅만 — 가드는 도메인 서비스가 K-차수 5 에서). */
    fun transitionTo(next: ProviderState) {
        val prev = stateRef.getAndSet(next)
        if (prev != next) log.debug("provider {} 상태 {} → {}", providerId, prev, next)
    }

    fun applyHello(hello: ProviderHelloFrame) {
        capability = ProviderCapability(
            models = hello.models,
            maxConcurrency = hello.maxConcurrency,
            remainingDailyRequests = hello.remainingDailyRequests,
        )
        markSeen()
    }

    /** 추론 요청을 보내고 결과 future 를 돌려준다. 큐 초과는 BUSY, 무응답은 TIMEOUT. */
    fun sendInfer(
        prompt: String,
        model: String? = null,
        options: Map<String, Any?> = emptyMap(),
    ): CompletableFuture<InferResult> {
        val cap = capability.maxConcurrency + maxQueue
        if (inFlight.get() >= cap) {
            return CompletableFuture.failedFuture(AgentBusyException("대기 큐가 가득 찼습니다."))
        }
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val fut = CompletableFuture<InferResult>()
        pending[requestId] = fut
        inFlight.incrementAndGet()
        transitionTo(ProviderState.ONLINE_BUSY)
        try {
            connection.sendFrame(InferRequest(requestId, model, prompt, filterOptions(options)))
        } catch (e: Exception) {
            cleanup(requestId)
            return CompletableFuture.failedFuture(ConnectionClosedException("전송 실패: ${e.message}"))
        }
        return fut.orTimeout(requestTimeoutSeconds, TimeUnit.SECONDS).handle { res, err ->
            cleanup(requestId)
            when {
                err == null -> res
                isTimeout(err) -> {
                    safeSend(CancelFrame(requestId))
                    throw RemoteTimeoutException("원격 에이전트 응답 시간 초과(${requestTimeoutSeconds}초)")
                }
                else -> throw (unwrap(err))
            }
        }
    }

    private fun cleanup(requestId: String) {
        pending.remove(requestId)
        if (inFlight.decrementAndGet() <= 0) transitionTo(ProviderState.ONLINE_IDLE)
    }

    private fun isTimeout(err: Throwable): Boolean =
        err is TimeoutException || (err is CompletionException && err.cause is TimeoutException)

    private fun unwrap(err: Throwable): Throwable =
        if (err is CompletionException && err.cause != null) err.cause!! else err

    private fun safeSend(frame: Frame) {
        try {
            connection.sendFrame(frame)
        } catch (e: Exception) {
            log.debug("제어 프레임 송신 실패(무시): {}", e.message)
        }
    }

    /** read loop 가 파싱한 프레임을 처리한다. */
    fun handleFrame(frame: Frame) {
        markSeen()
        when (frame) {
            is InferResult -> pending[frame.requestId]?.complete(frame)
            is InferError -> pending[frame.requestId]
                ?.completeExceptionally(RemoteInferException(frame.code, frame.message))
            is ChunkFrame -> streams[frame.requestId]?.offer(frame)
            is ProviderHelloFrame -> applyHello(frame)
            else -> { /* pong/status: markSeen 으로 충분 */ }
        }
    }

    /** 연결 종료: 대기 중 요청을 모두 실패 처리. */
    fun closeAndFailPending(reason: String) {
        transitionTo(ProviderState.OFFLINE)
        pending.values.forEach { it.completeExceptionally(ConnectionClosedException(reason)) }
        pending.clear()
        streams.clear()
    }
}
