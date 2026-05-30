package com.discordassistant.central.relay

import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.FrameCodec
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

/** Spring WebSocketSession 을 AgentConnection 으로 감싼다. */
class WsAgentConnection(private val session: WebSocketSession) : AgentConnection {
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
}
