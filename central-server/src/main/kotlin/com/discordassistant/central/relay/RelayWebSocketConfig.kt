package com.discordassistant.central.relay

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * 에이전트 WebSocket 엔드포인트 등록 + 스케줄링 활성화(heartbeat/유지보수).
 *
 * allowedOrigins("*"): 에이전트는 브라우저가 아니라 임의 호스트의 outbound 클라이언트이므로
 * Origin 으로 막을 수 없다. 접근 제어는 일회용 토큰 인증으로 한다(ADR 0002 보안).
 */
@Configuration
@EnableWebSocket
@EnableScheduling
class RelayWebSocketConfig(
    private val handler: RelayWebSocketHandler,
    @param:Value("\${central.relay.path:/agent}") private val relayPath: String,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, relayPath).setAllowedOrigins("*")
    }
}
