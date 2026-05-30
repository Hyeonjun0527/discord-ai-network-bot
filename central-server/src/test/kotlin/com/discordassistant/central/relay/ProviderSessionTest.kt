package com.discordassistant.central.relay

import com.discordassistant.central.domain.ProviderState
import com.discordassistant.central.relay.protocol.ErrorCode
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferError
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import com.discordassistant.central.relay.protocol.ProviderHelloFrame
import com.discordassistant.central.relay.protocol.Usage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

private class FakeConnection(
    override val remoteId: String = "fake",
) : AgentConnection {
    val sent = mutableListOf<Frame>()
    var closed: String? = null

    override fun sendFrame(frame: Frame) {
        sent.add(frame)
    }

    override fun close(reason: String) {
        closed = reason
    }
}

class ProviderSessionTest {
    private fun session(
        conn: FakeConnection,
        maxQueue: Int = 16,
        timeout: Long = 120,
    ) = ProviderSession(conn, providerId = 1, guildId = 100, requestTimeoutSeconds = timeout, maxQueue = maxQueue)

    @Test
    fun `sendInfer 성공 — handleFrame 으로 future 완료`() {
        val conn = FakeConnection()
        val s = session(conn)
        val fut = s.sendInfer(prompt = "안녕", model = "m1", options = mapOf("temperature" to 0.3, "evil" to "x"))
        val req = conn.sent.filterIsInstance<InferRequest>().single()
        assertEquals("안녕", req.prompt)
        assertEquals(mapOf<String, Any?>("temperature" to 0.3), req.options) // 화이트리스트 필터
        s.handleFrame(InferResult(requestId = req.requestId, text = "답변", usage = Usage(3, 4)))
        val res = fut.get(2, TimeUnit.SECONDS)
        assertEquals("답변", res.text)
        assertEquals(3, res.usage.promptTokens)
        assertEquals(ProviderState.ONLINE_IDLE, s.state) // 완료 후 idle 복귀
    }

    @Test
    fun `에러 프레임 → RemoteInferException`() {
        val conn = FakeConnection()
        val s = session(conn)
        val fut = s.sendInfer(prompt = "x")
        val req = conn.sent.filterIsInstance<InferRequest>().single()
        s.handleFrame(InferError(requestId = req.requestId, code = ErrorCode.OLLAMA_ERROR, message = "model missing"))
        val ex =
            org.junit.jupiter.api
                .assertThrows<ExecutionException> { fut.get(2, TimeUnit.SECONDS) }
        assertTrue(ex.cause is RemoteInferException)
        assertEquals(ErrorCode.OLLAMA_ERROR, (ex.cause as RemoteInferException).code)
    }

    @Test
    fun `장애 주입 — 연속 실패 임계 도달 시 UNHEALTHY(서킷브레이커, #248)`() {
        val conn = FakeConnection()
        val s = session(conn)
        // 3회(FAILURE_THRESHOLD) 연속 에러 주입 → UNHEALTHY 전환
        repeat(3) {
            val fut = s.sendInfer(prompt = "x")
            val req = conn.sent.filterIsInstance<InferRequest>().last()
            s.handleFrame(InferError(requestId = req.requestId, code = ErrorCode.OLLAMA_ERROR, message = "boom"))
            org.junit.jupiter.api
                .assertThrows<ExecutionException> { fut.get(2, TimeUnit.SECONDS) }
        }
        assertEquals(ProviderState.UNHEALTHY, s.state)
        assertTrue(s.failures >= 3)
    }

    @Test
    fun `장애 주입 — 성공이 실패 카운터를 리셋`() {
        val conn = FakeConnection()
        val s = session(conn)
        // 2회 실패(임계 미만)
        repeat(2) {
            val fut = s.sendInfer(prompt = "x")
            val req = conn.sent.filterIsInstance<InferRequest>().last()
            s.handleFrame(InferError(requestId = req.requestId, code = ErrorCode.OLLAMA_ERROR, message = "boom"))
            org.junit.jupiter.api
                .assertThrows<ExecutionException> { fut.get(2, TimeUnit.SECONDS) }
        }
        // 성공 1회 → 카운터 리셋
        val ok = s.sendInfer(prompt = "y")
        val okReq = conn.sent.filterIsInstance<InferRequest>().last()
        s.handleFrame(InferResult(requestId = okReq.requestId, text = "good"))
        ok.get(2, TimeUnit.SECONDS)
        assertEquals(0, s.failures)
        assertTrue(s.state != ProviderState.UNHEALTHY)
    }

    @Test
    fun `큐 깊이 — 동시한도 초과분이 대기 수(#170)`() {
        val conn = FakeConnection()
        // maxConcurrency=1(기본 capability) + maxQueue=3 → cap 4
        val s = ProviderSession(conn, providerId = 1, guildId = 100, maxQueue = 3)
        assertEquals(0, s.queueDepth())
        s.sendInfer(prompt = "a") // inFlight 1 == maxConcurrency → 대기 0
        assertEquals(0, s.queueDepth())
        s.sendInfer(prompt = "b") // inFlight 2 → 대기 1
        s.sendInfer(prompt = "c") // inFlight 3 → 대기 2
        assertEquals(2, s.queueDepth())
    }

    @Test
    fun `큐 초과 → BUSY`() {
        val conn = FakeConnection()
        val s = session(conn, maxQueue = 0) // cap = capability.maxConcurrency(1) + 0 = 1
        s.sendInfer(prompt = "first") // in-flight 1
        val busy = s.sendInfer(prompt = "second")
        val ex =
            org.junit.jupiter.api
                .assertThrows<ExecutionException> { busy.get(2, TimeUnit.SECONDS) }
        assertTrue(ex.cause is AgentBusyException)
    }

    @Test
    fun `무응답 → 타임아웃 후 RemoteTimeoutException + cancel 송신`() {
        val conn = FakeConnection()
        val s = session(conn, timeout = 1)
        val fut = s.sendInfer(prompt = "y")
        val ex =
            org.junit.jupiter.api
                .assertThrows<ExecutionException> { fut.get(3, TimeUnit.SECONDS) }
        assertTrue(ex.cause is RemoteTimeoutException)
        assertTrue(conn.sent.any { it.type == "cancel" }) // 취소 프레임 송신
    }

    @Test
    fun `provider_hello → capability 반영`() {
        val s = session(FakeConnection())
        s.handleFrame(ProviderHelloFrame(models = listOf("llama3.1:8b"), maxConcurrency = 2, remainingDailyRequests = 42))
        assertEquals(listOf("llama3.1:8b"), s.capability.models)
        assertEquals(2, s.capability.maxConcurrency)
    }
}

class ConnectionRegistryTest {
    private fun newSession(
        conn: FakeConnection,
        providerId: Long,
        guildId: Long?,
    ) = ProviderSession(conn, providerId, guildId)

    @Test
    fun `등록·조회·길드 풀`() {
        val reg = ConnectionRegistry()
        val a = newSession(FakeConnection("a"), 1, 100)
        val b = newSession(FakeConnection("b"), 2, 100)
        reg.register(a)
        reg.register(b)
        assertSame(a, reg.byProvider(1))
        assertEquals(2, reg.byGuild(100).size) // 같은 길드 풀에 2개
        assertEquals(2, reg.activeCount())
    }

    @Test
    fun `같은 provider 재연결 시 이전 세션 축출`() {
        val reg = ConnectionRegistry()
        val connA = FakeConnection("a")
        val a = newSession(connA, 1, 100)
        reg.register(a)
        val b = newSession(FakeConnection("b"), 1, 100)
        reg.register(b)
        assertSame(b, reg.byProvider(1))
        assertTrue(connA.closed != null) // 이전 연결 graceful close
        assertEquals(ProviderState.OFFLINE, a.state)
    }

    @Test
    fun `좀비 청소(heartbeat 만료)`() {
        val reg = ConnectionRegistry()
        val a = newSession(FakeConnection("a"), 1, 100)
        reg.register(a)
        // 강제로 last_seen 을 과거로: timeout 0 으로 즉시 stale 판정
        Thread.sleep(5)
        val n = reg.reapStale(timeoutSeconds = 0)
        assertEquals(1, n)
        assertEquals(0, reg.activeCount())
    }
}
