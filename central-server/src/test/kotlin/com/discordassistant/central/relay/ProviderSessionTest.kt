package com.discordassistant.central.relay

import com.discordassistant.central.provider.domain.model.ProviderState
import com.discordassistant.central.relay.protocol.ChunkFrame
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
    fun `스트리밍 — ChunkFrame 드레인·조립(#142)`() {
        val conn = FakeConnection()
        val s = session(conn)
        val collected = StringBuilder()
        val fut = s.sendInferStream(prompt = "안녕", model = "m1", onChunk = { collected.append(it) })
        val req = conn.sent.filterIsInstance<InferRequest>().single()
        assertTrue(req.stream, "stream=true 로 요청해야 함")
        // 에이전트가 보낸 청크들이 도착하는 상황 시뮬레이션
        s.handleFrame(
            com.discordassistant.central.relay.protocol
                .ChunkFrame(req.requestId, delta = "안", done = false),
        )
        s.handleFrame(
            com.discordassistant.central.relay.protocol
                .ChunkFrame(req.requestId, delta = "녕!", done = false),
        )
        s.handleFrame(
            com.discordassistant.central.relay.protocol
                .ChunkFrame(req.requestId, delta = "", done = true),
        )
        val res = fut.get(3, TimeUnit.SECONDS)
        assertEquals("안녕!", res.text)
        assertEquals("안녕!", collected.toString()) // onChunk 콜백으로 점진 수신
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

    @Test
    fun `provider_hello capabilities 반영(image)`() {
        val s = session(FakeConnection())
        s.handleFrame(ProviderHelloFrame(models = listOf("m"), capabilities = listOf("text", "image")))
        assertTrue(s.capability.capabilities.contains("image"))
    }

    @Test
    fun `sendImage — task=image 송신 후 청크 재조립으로 PNG 바이트 복원`() {
        val conn = FakeConnection()
        val s = session(conn)
        val original = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val b64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(original)
        val fut = s.sendImage("고양이")
        val req = conn.sent.filterIsInstance<InferRequest>().single()
        assertEquals("image", req.task)
        assertEquals("고양이", req.prompt)
        val mid = b64.length / 2
        s.handleFrame(ChunkFrame(req.requestId, b64.substring(0, mid), done = false))
        s.handleFrame(ChunkFrame(req.requestId, b64.substring(mid), done = false))
        s.handleFrame(ChunkFrame(req.requestId, "", done = true))
        val bytes = fut.get(3, TimeUnit.SECONDS)
        assertTrue(original.contentEquals(bytes))
        assertEquals(ProviderState.ONLINE_IDLE, s.state)
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
    fun `같은 provider라도 다른 길드 세션은 서로 교체하지 않는다`() {
        val reg = ConnectionRegistry()
        val connA = FakeConnection("a")
        val a = newSession(connA, 1, 100)
        val connB = FakeConnection("b")
        val b = newSession(connB, 1, 200)

        reg.register(a)
        reg.register(b)

        assertSame(a, reg.byProvider(100, 1))
        assertSame(b, reg.byProvider(200, 1))
        assertEquals(null, connA.closed)
        assertEquals(2, reg.activeCount())
    }

    @Test
    fun `멤버 이탈 시 해당 길드 provider 세션만 닫는다`() {
        val reg = ConnectionRegistry()
        val connA = FakeConnection("a")
        val a = newSession(connA, 1, 100)
        val connB = FakeConnection("b")
        val b = newSession(connB, 1, 200)
        reg.register(a)
        reg.register(b)

        assertTrue(reg.closeProviderInGuild(100, 1, "member removed"))

        assertEquals(null, reg.byProvider(100, 1))
        assertSame(b, reg.byProvider(200, 1))
        assertTrue(connA.closed!!.contains("member removed"))
        assertEquals(null, connB.closed)
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

    @Test
    fun `길드 제거는 진행 중 요청을 실패 처리한다`() {
        val reg = ConnectionRegistry()
        val conn = FakeConnection("a")
        val a = newSession(conn, 1, 100)
        reg.register(a)
        val fut = a.sendInfer(prompt = "in-flight")

        assertEquals(1, reg.closeGuild(100, "guild removed"))

        val ex =
            org.junit.jupiter.api
                .assertThrows<ExecutionException> { fut.get(2, TimeUnit.SECONDS) }
        assertTrue(ex.cause is ConnectionClosedException)
        assertTrue(ex.cause!!.message!!.contains("guild removed"))
    }

    @Test
    fun `길드 제거 시 해당 길드 세션만 종료`() {
        val reg = ConnectionRegistry()
        val connA = FakeConnection("a")
        val a = newSession(connA, 1, 100)
        val connB = FakeConnection("b")
        val b = newSession(connB, 2, 200)
        reg.register(a)
        reg.register(b)

        assertEquals(1, reg.closeGuild(100, "guild removed"))

        assertEquals(0, reg.byGuild(100).size)
        assertEquals(1, reg.byGuild(200).size)
        assertTrue(connA.closed!!.contains("guild removed"))
        assertEquals(null, connB.closed)
    }
}
