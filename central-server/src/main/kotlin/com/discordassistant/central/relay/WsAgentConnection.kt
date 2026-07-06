package com.discordassistant.central.relay

import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.FrameCodec
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator

/** Spring WebSocketSession 을 AgentConnection 으로 감싼다. */
class WsAgentConnection(
    rawSession: WebSocketSession,
) : AgentConnection {
    // 단일 WebSocketSession 은 동시 sendMessage 를 지원하지 않는다 — 여러 스레드(요청·타임아웃·드레인)가
    // 동시에 보내면 프레임이 뒤섞여 깨진다. ConcurrentWebSocketSessionDecorator 로 송신을 직렬화한다.
    // sendTimeLimit 초과·buffer 상한 초과 시 세션을 닫아 느린 소비자가 송신 스레드/메모리를 막지 않게 한다.
    private val session: WebSocketSession =
        ConcurrentWebSocketSessionDecorator(rawSession, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES)

    override val remoteId: String = session.id

    override fun sendFrame(frame: Frame) {
        if (session.isOpen) {
            session.sendMessage(TextMessage(FrameCodec.encode(frame)))
        }
    }

    override fun close(reason: String) {
        if (session.isOpen) {
            session.close(CloseStatus.NORMAL.withReason(reason.take(120)))
        }
    }

    companion object {
        /** 단일 send 최대 대기(ms). 초과 시 세션을 닫는다. */
        private const val SEND_TIME_LIMIT_MS = 10_000

        /** 송신 버퍼 상한(bytes). 초과 시 세션을 닫아 메모리 폭주를 막는다. */
        private const val BUFFER_SIZE_LIMIT_BYTES = 512 * 1024
    }
}
