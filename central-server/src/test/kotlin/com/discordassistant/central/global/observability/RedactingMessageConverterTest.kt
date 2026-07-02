package com.discordassistant.central.global.observability

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.helpers.MessageFormatter
import java.nio.file.Files
import java.nio.file.Path

/**
 * NEXA-P17-T013 enforcement: redaction 이 **실제 logback 출력 경로**에 바인딩됐는지 검증한다.
 *
 * logback-spring.xml 과 동일하게 `%redactedMsg`(=[RedactingMessageConverter]) 를 conversion word 로 등록한
 * 실제 [PatternLayout] 으로 로그 이벤트를 렌더링해, snowflake·API key·Bearer 토큰이 출력 문자열에 남지 않음을
 * 확인한다. 단위 redact() 호출이 아니라 logback 패턴 → 컨버터 파이프라인을 구동한다(security-reviewer H3 갭 해소 증명).
 */
class RedactingMessageConverterTest {
    private fun renderThrough(vararg messages: Pair<String, Array<Any>>): String {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger("nexa.redaction.test")
        val layout = redactingPatternLayout(context)
        return try {
            buildString {
                messages.forEach { (msg, args) ->
                    val formatted = MessageFormatter.arrayFormat(msg, args).message
                    val event = LoggingEvent("FQCN", logger, ch.qos.logback.classic.Level.INFO, formatted, null, null)
                    append(layout.doLayout(event))
                }
            }
        } finally {
            layout.stop()
        }
    }

    @Test
    fun `snowflake API key bearer 토큰이 실제 로그 출력에서 마스킹된다`() {
        val rendered =
            renderThrough(
                "user 123456789012345678 with key sk-ABCDEFGHIJKLMNOP1234 and Bearer abcdefgh1234" to emptyArray(),
                "param snowflake {} and key {}" to arrayOf<Any>("987654321098765432", "AIzaABCDEFGHIJKLMNOP1234"),
            )
        // 출력에 금지 패턴이 한 건도 남지 않아야 한다(redactor 와 동일 패턴).
        assertThat(SensitiveLogRedactor.containsSensitive(rendered)).isFalse()
        assertThat(rendered).doesNotContain("123456789012345678")
        assertThat(rendered).doesNotContain("987654321098765432")
        assertThat(rendered).contains("[redacted-id]")
        assertThat(rendered).contains("[redacted-key]")
        assertThat(rendered).contains("[redacted-token]")
    }

    @Test
    fun `허용 필드(가명 라벨 status)는 영향받지 않는다`() {
        val rendered = renderThrough("decision user_3 status=SPEAK correlationId-short" to emptyArray())
        assertThat(rendered).contains("user_3")
        assertThat(rendered).contains("status=SPEAK")
    }

    @Test
    fun `file appender output is redacted and scanner-visible`() {
        val logDir = logDirectory()
        Files.createDirectories(logDir)
        val logFile = logDir.resolve("redacting-message-converter-test.log")
        Files.deleteIfExists(logFile)

        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger("nexa.redaction.file-test")
        val layout = redactingPatternLayout(context)
        val encoder =
            LayoutWrappingEncoder<ILoggingEvent>().apply {
                this.context = context
                this.layout = layout
                start()
            }
        val appender =
            FileAppender<ILoggingEvent>().apply {
                this.context = context
                file = logFile.toString()
                isAppend = false
                this.encoder = encoder
                start()
            }

        try {
            val rendered =
                "file log user 123456789012345678 key sk-ABCDEFGHIJKLMNOP1234 " +
                    "and Bearer abcdefgh1234"
            appender.doAppend(LoggingEvent("FQCN", logger, ch.qos.logback.classic.Level.INFO, rendered, null, null))
        } finally {
            appender.stop()
            encoder.stop()
            layout.stop()
        }

        val output = Files.readString(logFile)
        assertThat(output).contains("[redacted-id]", "[redacted-key]", "[redacted-token]")
        assertThat(SensitiveLogRedactor.containsSensitive(output)).isFalse()
    }

    private fun logDirectory(): Path {
        val configured = System.getenv("LOG_DIR")
        return if (configured.isNullOrBlank()) {
            Files.createTempDirectory("nexa-redaction-file-test")
        } else {
            Path.of(configured)
        }
    }

    private fun redactingPatternLayout(context: LoggerContext): PatternLayout =
        PatternLayout().apply {
            this.context = context
            // 운영 logback-spring.xml 의 <conversionRule conversionWord="redactedMsg" .../> 과 동치 바인딩.
            // logback 1.5.13+ 에서 instanceConverterMap 값 타입이 클래스명(String)→Supplier<DynamicConverter>
            // 로 바뀌어, 컨버터 생성자를 supplier 로 등록한다(동일 바인딩, 동작 불변).
            instanceConverterMap["redactedMsg"] = java.util.function.Supplier { RedactingMessageConverter() }
            pattern = "%redactedMsg%n"
            start()
        }
}
