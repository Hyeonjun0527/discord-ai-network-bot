package com.discordassistant.central.relay

import com.discordassistant.central.ainetwork.application.AiNetworkGrowthService
import com.discordassistant.central.relay.protocol.AuthErrFrame
import com.discordassistant.central.relay.protocol.AuthFrame
import com.discordassistant.central.relay.protocol.AuthOkFrame
import com.discordassistant.central.relay.protocol.ErrorCode
import com.discordassistant.central.relay.protocol.FrameCodec
import com.discordassistant.central.relay.protocol.MAX_FRAME_BYTES
import com.discordassistant.central.relay.protocol.PingFrame
import com.discordassistant.central.relay.protocol.PongFrame
import com.discordassistant.central.relay.protocol.ProtocolException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 에이전트 WebSocket 핸들러(K-차수 3). 연결 직후 첫 프레임=auth 강제 → 토큰 검증 → 세션 등록.
 * 이후 프레임은 ProviderSession.handleFrame 으로 디스패치한다. ping→pong, 잘못된 프레임은 무시.
 *
 * 보안: Origin 검증은 토큰 인증으로 대체한다(같은 신뢰 경계, allowedOrigins 는 config 참고).
 * TLS/wss 는 앞단 리버스 프록시에서 종단(ADR 0002).
 */
@Component
class RelayWebSocketHandler(
    private val registry: ConnectionRegistry,
    private val verifier: TokenVerifier,
    private val growth: AiNetworkGrowthService? = null,
    @param:Value("\${central.relay.request-timeout-seconds:120}") private val requestTimeout: Long,
    @param:Value("\${central.relay.heartbeat-seconds:30}") private val heartbeatSeconds: Long,
    // durable 토큰 발급기(있으면 인증 성공 시 재사용 토큰을 auth_ok 로 내려줌). TokenService 가 구현.
    private val durableIssuer: com.discordassistant.central.provider.application.DurableTokenIssuer? = null,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(RelayWebSocketHandler::class.java)

    private val pendingAuth = ConcurrentHashMap<String, Pair<WebSocketSession, Long>>()
    private val authed = ConcurrentHashMap<String, ProviderSession>()

    private val authTimeoutNanos = TimeUnit.SECONDS.toNanos(10)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        session.textMessageSizeLimit = MAX_FRAME_BYTES
        pendingAuth[session.id] = session to System.nanoTime()
    }

    public override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        val frame =
            try {
                FrameCodec.decode(message.payload)
            } catch (e: ProtocolException) {
                log.debug("잘못된 프레임 수신(무시): {}", e.message)
                return
            }
        val ps = authed[session.id]
        if (ps == null) {
            authenticate(session, frame)
            return
        }
        when (frame) {
            is PingFrame -> {
                WsAgentConnection(session).sendFrame(PongFrame())
                ps.markSeen()
            }
            else -> ps.handleFrame(frame)
        }
    }

    private fun authenticate(
        session: WebSocketSession,
        frame: Any,
    ) {
        if (frame !is AuthFrame) {
            reject(session, "첫 프레임은 auth 여야 합니다")
            return
        }
        val binding = verifier.verify(frame.token)
        if (binding == null) {
            reject(session, "토큰 검증 실패")
            return
        }
        if (binding.guildId == null) {
            reject(session, "서버에 묶이지 않은 토큰입니다. 다시 등록해 주세요.")
            return
        }
        val conn = WsAgentConnection(session)
        val ps =
            ProviderSession(
                conn,
                providerId = binding.providerId,
                guildId = binding.guildId,
                requestTimeoutSeconds = requestTimeout,
                onHello = ::syncProviderHello,
            )
        registry.register(ps)
        authed[session.id] = ps
        pendingAuth.remove(session.id)
        // 재연결·재시작에 재사용할 durable 토큰 발급(시크릿 설정 시). 비면 기존 일회용 동작.
        val durable = durableIssuer?.issueDurable(binding.providerId, binding.guildId).orEmpty()
        conn.sendFrame(AuthOkFrame(sessionId = session.id, providerToken = durable))
        log.info("에이전트 인증 성공: provider={} guild={}", binding.providerId, binding.guildId)
    }

    private fun reject(
        session: WebSocketSession,
        reason: String,
    ) {
        try {
            WsAgentConnection(session).sendFrame(AuthErrFrame(code = ErrorCode.AUTH_FAILED, message = reason))
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason(reason.take(120)))
        } catch (e: Exception) {
            log.debug("인증 거부 처리 실패(무시): {}", e.message)
        }
        pendingAuth.remove(session.id)
        log.info("에이전트 인증 거부: {}", reason)
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        pendingAuth.remove(session.id)
        authed.remove(session.id)?.let {
            it.closeAndFailPending("연결 종료: $status")
            markProviderOffline(it)
            registry.unregister(it)
        }
    }

    private fun syncProviderHello(
        session: ProviderSession,
        hello: com.discordassistant.central.relay.protocol.ProviderHelloFrame,
    ) {
        val guildId = session.guildId ?: return
        try {
            growth?.syncProviderCapabilitiesFromHello(
                guildId = guildId,
                providerUserId = session.providerId,
                modelNames = hello.models,
                maxConcurrency = hello.maxConcurrency,
                remainingDailyRequests = hello.remainingDailyRequests,
            )
        } catch (e: Exception) {
            log.warn("provider {} 능력 동기화 실패(guild={}): {}", session.providerId, guildId, e.message)
        }
    }

    private fun markProviderOffline(session: ProviderSession) {
        val guildId = session.guildId ?: return
        try {
            growth?.markProviderOffline(guildId, session.providerId)
        } catch (e: Exception) {
            log.warn("provider {} 오프라인 동기화 실패(guild={}): {}", session.providerId, guildId, e.message)
        }
    }

    /** 주기적 유지보수: 인증 타임아웃 정리 + heartbeat ping + 좀비 세션 정리. */
    @Scheduled(fixedDelayString = "\${central.relay.maintenance-millis:15000}")
    fun maintenance() {
        val now = System.nanoTime()
        // 인증 타임아웃(10s) 정리
        pendingAuth.entries.removeIf { (_, pair) ->
            val (sess, t) = pair
            if (now - t > authTimeoutNanos) {
                try {
                    sess.close(CloseStatus.POLICY_VIOLATION.withReason("인증 타임아웃"))
                } catch (_: Exception) {
                }
                true
            } else {
                false
            }
        }
        // heartbeat ping(연결로 직접) + 좀비 정리(registry 가 닫고 해제 → afterConnectionClosed 가 authed 정리)
        val staleTimeout = heartbeatSeconds * 3
        authed.values.forEach { ps ->
            try {
                ps.connection.sendFrame(PingFrame())
            } catch (_: Exception) {
            }
        }
        registry.reapStale(staleTimeout)
    }

    val authedCount: Int get() = authed.size
}
