package com.discordassistant.central.relay

import com.discordassistant.central.domain.ProviderState
import com.discordassistant.central.relay.protocol.CancelFrame
import com.discordassistant.central.relay.protocol.ChunkFrame
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferError
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import com.discordassistant.central.relay.protocol.ProviderHelloFrame
import com.discordassistant.central.relay.protocol.ProviderStatusFrame
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

/** 프로바이더 실시간 상태(provider_status 로 보고). */
data class LiveStatus(
    val load: String = "idle",
    val battery: String = "",
    val online: Boolean = true,
    val busy: Boolean = false,
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
    private val remainingDaily = AtomicInteger(Int.MAX_VALUE)
    private val consecutiveFailures = AtomicInteger(0)
    @Volatile
    var liveStatus: LiveStatus = LiveStatus()
        private set

    val failures: Int get() = consecutiveFailures.get()
    private val pending = ConcurrentHashMap<String, CompletableFuture<InferResult>>()
    private val streams = ConcurrentHashMap<String, java.util.concurrent.BlockingQueue<ChunkFrame>>()

    val state: ProviderState get() = stateRef.get()
    val activeRequests: Int get() = inFlight.get()

    /**
     * 에이전트 측 대기열 깊이(차수 11 #170). 동시 처리 한도를 넘어 보낸 요청 수 = 에이전트 세마포어에서
     * 순차 대기 중인 수. 0 이면 즉시 처리. 새 요청의 예상 대기 위치 추정에 사용.
     */
    fun queueDepth(): Int = maxOf(0, inFlight.get() - capability.maxConcurrency)

    fun markSeen() = lastSeenNanos.set(System.nanoTime())

    fun isStale(timeoutSeconds: Long, nowNanos: Long = System.nanoTime()): Boolean =
        nowNanos - lastSeenNanos.get() > TimeUnit.SECONDS.toNanos(timeoutSeconds)

    /** 상태 전이. 불가 전이(상태머신 가드 위반)는 거부하고 로깅한다. 성공 시 true. */
    fun transitionTo(next: ProviderState): Boolean {
        val prev = stateRef.get()
        if (prev == next) return true
        if (!prev.canTransitionTo(next)) {
            log.warn("provider {} 불가 전이 거부: {} → {}", providerId, prev, next)
            return false
        }
        stateRef.set(next)
        log.debug("provider {} 상태 {} → {}", providerId, prev, next)
        return true
    }

    val remainingDailyRequests: Int get() = remainingDaily.get()

    fun applyHello(hello: ProviderHelloFrame) {
        capability = ProviderCapability(
            models = hello.models,
            maxConcurrency = hello.maxConcurrency,
            remainingDailyRequests = hello.remainingDailyRequests,
        )
        // hello 의 remaining <= 0 은 "일일 한도 없음(무제한)"을 의미한다(에이전트 daily_limit=0).
        // 내부 무제한 센티넬(Int.MAX_VALUE)로 둔다. 실제 한도가 있으면 양수를 보낸다.
        remainingDaily.set(if (hello.remainingDailyRequests > 0) hello.remainingDailyRequests else Int.MAX_VALUE)
        markSeen()
    }

    fun applyStatus(status: ProviderStatusFrame) {
        liveStatus = LiveStatus(status.load, status.battery, status.online, status.busy)
        // 자동 보호(K-차수 12): 배터리/절전 → PAUSED, 고부하 → LIMITED, 그 외 busy 반영.
        when {
            status.battery == "discharging" || status.battery == "low" -> transitionTo(ProviderState.PAUSED)
            status.load == "high" -> transitionTo(ProviderState.LIMITED)
            status.busy -> transitionTo(ProviderState.ONLINE_BUSY)
            state == ProviderState.ONLINE_BUSY && inFlight.get() == 0 -> transitionTo(ProviderState.ONLINE_IDLE)
        }
        markSeen()
    }

    /** 수동 보호: 일시정지 / 재개. */
    fun pause(): Boolean = transitionTo(ProviderState.PAUSED)

    fun resume(): Boolean = transitionTo(ProviderState.ONLINE_IDLE)

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
        if (remainingDaily.get() != Int.MAX_VALUE) remainingDaily.decrementAndGet()
        transitionTo(ProviderState.ONLINE_BUSY)
        try {
            connection.sendFrame(InferRequest(requestId, model, prompt, filterOptions(options)))
        } catch (e: Exception) {
            cleanup(requestId)
            return CompletableFuture.failedFuture(ConnectionClosedException("전송 실패: ${e.message}"))
        }
        return fut.orTimeout(requestTimeoutSeconds, TimeUnit.SECONDS).handle { res, err ->
            cleanup(requestId)
            if (err == null) {
                consecutiveFailures.set(0)
                res
            } else {
                // 반복 실패 시 자동 비활성화(UNHEALTHY) — 보호(K-차수 12).
                if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
                    transitionTo(ProviderState.UNHEALTHY)
                }
                if (isTimeout(err)) {
                    safeSend(CancelFrame(requestId))
                    throw RemoteTimeoutException("원격 에이전트 응답 시간 초과(${requestTimeoutSeconds}초)")
                }
                throw unwrap(err)
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
            is ProviderStatusFrame -> applyStatus(frame)
            else -> { /* pong: markSeen 으로 충분 */ }
        }
    }

    /** 연결 종료: 대기 중 요청을 모두 실패 처리. */
    fun closeAndFailPending(reason: String) {
        transitionTo(ProviderState.OFFLINE)
        pending.values.forEach { it.completeExceptionally(ConnectionClosedException(reason)) }
        pending.clear()
        streams.clear()
    }

    companion object {
        /** 연속 실패가 이 횟수에 도달하면 자동 비활성화(UNHEALTHY). */
        private const val FAILURE_THRESHOLD = 3
    }
}
