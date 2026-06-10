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
    // 봇이 속한 길드 목록(이름 조회). auth_ok 에 guildName 을 담아 에이전트의 '이름 미상' 수동 라벨링을 없앤다.
    private val botGuilds: com.discordassistant.central.platform.discord.BotGuildLister? = null,
    // 전문가 층: 길드별 포워드 채널 조회(정책 소유) + 채널 게시(JDA). 둘 다 있을 때만 ComfyUI 웹 생성물을 포워드.
    private val forwardPolicy: com.discordassistant.central.guild.application.PolicyService? = null,
    private val imagePoster: com.discordassistant.central.platform.discord.DiscordImagePoster? = null,
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
                onImageBroadcast = ::forwardExpertImage,
            )
        registry.register(ps)
        authed[session.id] = ps
        pendingAuth.remove(session.id)
        // 재연결·재시작에 재사용할 durable 토큰 발급(시크릿 설정 시). 비면 기존 일회용 동작.
        val durable = durableIssuer?.issueDurable(binding.providerId, binding.guildId).orEmpty()
        // 토큰이 묶인 길드 이름(봇이 그 길드에 있으면 조회 가능) → auth_ok 로 내려 자동 표기.
        val guildName = botGuilds?.botGuilds()?.firstOrNull { it.id == binding.guildId }?.name
        conn.sendFrame(
            AuthOkFrame(
                sessionId = session.id,
                providerToken = durable,
                guildId = binding.guildId,
                guildName = guildName,
            ),
        )
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

    /**
     * 전문가 층: 에이전트가 보낸 ComfyUI 웹 생성 이미지를 **길드 지정 채널**에 포워드한다.
     * 채널은 정책(expertForwardChannelId)으로 결정하므로 에이전트가 임의 채널을 지정할 수 없다(보안 화이트리스트).
     * 채널 미설정이거나 봇 미연결이면 조용히 무시(이중 옵트인 — 에이전트 broadcast ON + 관리자 채널 설정).
     *
     * 콘텐츠 안전(이미지 NSFW/미성년) 스크리닝은 여기서 하지 않는다 — 이미지 분류기가 없고(/imagine 등
     * 모든 이미지 경로가 동일), 이 흐름은 **프로바이더 소유자가 자기 ComfyUI 에서 만든 이미지를 자기 서버의
     * 관리자가 지정한 채널로** 보내는 것이라(이중 옵트인) 소유자/관리자 책임 영역이다. 텍스트와 달리 이미지
     * 바이트 검사는 별도 모더레이션 서비스가 필요하므로, 여기에 무력한 가짜 체크를 넣지 않는다(후속: 이미지
     * 모더레이션 도입 시 이 지점에 적용).
     */
    private fun forwardExpertImage(
        guildId: Long?,
        png: ByteArray,
        caption: String,
    ) {
        val gid = guildId ?: return
        val channelId = forwardPolicy?.expertForwardChannelId(gid) ?: return
        val text = caption.ifBlank { "🖼️ ComfyUI 에서 생성된 이미지" }
        imagePoster?.postImage(channelId, png, text)
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
