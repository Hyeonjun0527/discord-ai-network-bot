package com.discordassistant.central.relay

import com.discordassistant.central.relay.protocol.AuthErrFrame
import com.discordassistant.central.relay.protocol.AuthFrame
import com.discordassistant.central.relay.protocol.AuthOkFrame
import com.discordassistant.central.relay.protocol.FrameCodec
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketExtension
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.net.InetSocketAddress
import java.net.URI
import java.security.Principal
import java.util.concurrent.TimeUnit

/** 테스트용 최소 WebSocketSession. sendMessage 페이로드를 캡처한다. */
private class FakeWebSocketSession(private val sessionId: String = "s1") : WebSocketSession {
    val sent = mutableListOf<String>()
    private var open = true
    override fun getId(): String = sessionId
    override fun sendMessage(message: WebSocketMessage<*>) { sent.add((message as TextMessage).payload) }
    override fun isOpen(): Boolean = open
    override fun close() { open = false }
    override fun close(status: CloseStatus) { open = false }
    override fun getUri(): URI? = null
    override fun getHandshakeHeaders(): HttpHeaders = HttpHeaders.EMPTY
    override fun getAttributes(): MutableMap<String, Any> = mutableMapOf()
    override fun getPrincipal(): Principal? = null
    override fun getLocalAddress(): InetSocketAddress? = null
    override fun getRemoteAddress(): InetSocketAddress? = null
    override fun getAcceptedProtocol(): String? = null
    private var txLimit = 0
    private var binLimit = 0
    override fun setTextMessageSizeLimit(messageSizeLimit: Int) { txLimit = messageSizeLimit }
    override fun getTextMessageSizeLimit(): Int = txLimit
    override fun setBinaryMessageSizeLimit(messageSizeLimit: Int) { binLimit = messageSizeLimit }
    override fun getBinaryMessageSizeLimit(): Int = binLimit
    override fun getExtensions(): MutableList<WebSocketExtension> = mutableListOf()
}

class RelayWebSocketHandlerTest {

    private val verifier = object : TokenVerifier {
        override fun verify(token: String): OwnerBinding? =
            if (token == "good") OwnerBinding(providerId = 1, guildId = 100) else null
    }

    private fun handler(reg: ConnectionRegistry) =
        RelayWebSocketHandler(reg, verifier, requestTimeout = 2, heartbeatSeconds = 30)

    @Test
    fun `인증 실패 → AuthErr 후 종료`() {
        val reg = ConnectionRegistry()
        val h = handler(reg)
        val s = FakeWebSocketSession("bad")
        h.afterConnectionEstablished(s)
        h.handleTextMessage(s, TextMessage(FrameCodec.encode(AuthFrame(token = "wrong"))))
        assertTrue(FrameCodec.decode(s.sent.single()) is AuthErrFrame)
        assertFalse(s.isOpen)
        assertEquals(0, reg.activeCount())
    }

    @Test
    fun `인증 성공 → AuthOk + 등록 + 추론 왕복`() {
        val reg = ConnectionRegistry()
        val h = handler(reg)
        val s = FakeWebSocketSession("good1")
        h.afterConnectionEstablished(s)
        h.handleTextMessage(s, TextMessage(FrameCodec.encode(AuthFrame(token = "good", agentVersion = "0.1"))))
        // AuthOk 송신 + 레지스트리 등록
        assertTrue(FrameCodec.decode(s.sent.last()) is AuthOkFrame)
        val ps = reg.byProvider(1)!!
        assertEquals(1, h.authedCount)

        // 서버(라우터)가 추론 요청 → InferRequest 가 에이전트(WS)로 송신됨
        val fut = ps.sendInfer(prompt = "안녕", model = "m1")
        val req = s.sent.map { FrameCodec.decode(it) }.filterIsInstance<InferRequest>().single()
        assertEquals("안녕", req.prompt)

        // 에이전트가 결과 프레임을 보냄 → 핸들러가 세션으로 디스패치 → future 완료
        h.handleTextMessage(s, TextMessage(FrameCodec.encode(InferResult(requestId = req.requestId, text = "답변입니다"))))
        assertEquals("답변입니다", fut.get(2, TimeUnit.SECONDS).text)

        // 연결 종료 → 해제
        h.afterConnectionClosed(s, CloseStatus.NORMAL)
        assertEquals(0, reg.activeCount())
        assertEquals(0, h.authedCount)
    }
}
