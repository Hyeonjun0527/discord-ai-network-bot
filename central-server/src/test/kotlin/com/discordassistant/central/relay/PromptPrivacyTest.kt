package com.discordassistant.central.relay

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.discordassistant.central.persistence.AiRequestEntity
import com.discordassistant.central.relay.protocol.CancelFrame
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 프라이버시 불변식(서버): **프롬프트 원문은 로그/DB 에 남지 않는다.**
 * 회귀 방지를 위해 sendInfer→handleFrame 경로 전체에서 마커 프롬프트가 어떤 로그에도
 * 나타나지 않음을, 그리고 AiRequestEntity 에 프롬프트 필드가 없음을 단언한다.
 */
class PromptPrivacyTest {
    private val marker = "민감질문-PRIVACY-MARKER-AB12CD34-비밀번호처럼보이는내용"

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var rootLogger: Logger

    /** sendFrame(InferRequest) 를 받으면 즉시 결과를 되먹여 future 를 완료시키는 가짜 연결. */
    private class EchoConnection : AgentConnection {
        lateinit var session: ProviderSession
        override val remoteId = "echo"

        override fun sendFrame(frame: Frame) {
            if (frame is InferRequest) {
                session.handleFrame(InferResult(frame.requestId, "응답"))
            }
        }

        override fun close(reason: String) {}
    }

    @BeforeEach
    fun attachAppender() {
        rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        rootLogger.detachAppender(appender)
    }

    @Test
    fun `프롬프트 원문은 어떤 로그에도 남지 않는다`() {
        val conn = EchoConnection()
        val session = ProviderSession(connection = conn, providerId = 1L, guildId = 100L)
        conn.session = session

        // 정상 경로: 추론 요청을 보내고 결과까지 완료시킨다.
        session.sendInfer(prompt = marker, model = "m").join()
        // 잘못된/취소 경로도 프롬프트를 로그하지 않아야 한다.
        session.handleFrame(CancelFrame("nope"))

        val logged = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
        assertFalse(logged.contains(marker), "프롬프트 원문이 로그에 노출됨: $logged")
        assertFalse(logged.contains("PRIVACY-MARKER"), "프롬프트 일부가 로그에 노출됨")
    }

    @Test
    fun `AiRequestEntity 에는 프롬프트 필드가 없다`() {
        val fields = AiRequestEntity::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            fields.any { it.contains("prompt") || it.contains("question") || it.contains("content") },
            "요청 엔티티에 프롬프트성 필드가 있음: $fields",
        )
    }
}
