package com.discordassistant.central.platform.discord

import com.discordassistant.central.speech.domain.model.SpeechImageMediaType
import com.sun.net.httpserver.HttpServer
import net.dv8tion.jda.api.entities.Message
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

class NiaImageAttachmentTest {
    @Test
    fun `Discord PNG는 변환 프록시 대신 원본을 받아 로컬에서 축소한다`() {
        val original = png(width = 2_048, height = 512)
        val requestedUri = AtomicReference<String?>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/source.png") { exchange ->
                    requestedUri.set(exchange.requestURI.toString())
                    val response = original
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                createContext("/proxy.png") { exchange ->
                    requestedUri.set(exchange.requestURI.toString())
                    val response = "proxy-format".toByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }
        try {
            val sourceUrl = "http://127.0.0.1:${server.address.port}/source.png?signed=1"
            val proxyUrl = "http://127.0.0.1:${server.address.port}/proxy.png"
            val attachment =
                Message.Attachment(
                    1L,
                    sourceUrl,
                    proxyUrl,
                    "image.png",
                    "image/png",
                    null,
                    original.size,
                    512,
                    2_048,
                    false,
                    null,
                    0.0,
                    null,
                )

            val result = DiscordImageAttachmentPreparer().prepare(listOf(attachment))

            assertThat(result).isInstanceOf(TargetedImagePreparation.Ready::class.java)
            val image = (result as TargetedImagePreparation.Ready).image
            assertThat(image.width).isEqualTo(1_024)
            assertThat(image.height).isEqualTo(256)
            assertThat(requestedUri.get()).isEqualTo("/source.png?signed=1")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `가로로 긴 이미지는 비율을 유지해 1024x256으로 줄인다`() {
        val result = prepareSpeechImage(png(width = 2_048, height = 512))

        assertThat(result.width).isEqualTo(1_024)
        assertThat(result.height).isEqualTo(256)
        assertThat(result.mediaType).isEqualTo(SpeechImageMediaType.PNG)
        assertThat(result.toString()).doesNotContain(result.base64Data)
        assertDecodedDimensions(result.base64Data, 1_024, 256)
    }

    @Test
    fun `세로로 긴 이미지는 비율을 유지해 256x1024로 줄인다`() {
        val result = prepareSpeechImage(png(width = 512, height = 2_048))

        assertThat(result.width).isEqualTo(256)
        assertThat(result.height).isEqualTo(1_024)
        assertDecodedDimensions(result.base64Data, 256, 1_024)
    }

    @Test
    fun `1024보다 작은 이미지는 확대하지 않는다`() {
        val result = prepareSpeechImage(png(width = 300, height = 200))

        assertThat(result.width).isEqualTo(300)
        assertThat(result.height).isEqualTo(200)
        assertThat(result.isNiaSelfImage).isFalse()
        assertDecodedDimensions(result.base64Data, 300, 200)
    }

    @Test
    fun `공식 외형 이미지는 축소 준비 단계에서 자기 이미지로 표시한다`() {
        val officialBytes = checkNotNull(javaClass.getResourceAsStream("/static/img/mascot-ai.png")).use { it.readAllBytes() }

        val result = prepareSpeechImage(officialBytes)

        assertThat(result.isNiaSelfImage).isTrue()
    }

    @Test
    fun `실제 이미지가 아닌 바이트는 거절한다`() {
        assertThatThrownBy { prepareSpeechImage("not-an-image".toByteArray()) }
            .isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `니아 호명 답장 또는 현재 니아 턴 연속일 때만 보여 준 이미지로 본다`() {
        assertThat(isAttachmentShownToNia(directlyAddressed = true, replyToNia = false, niaTurnContinuationLikely = false))
            .isTrue()
        assertThat(isAttachmentShownToNia(directlyAddressed = false, replyToNia = true, niaTurnContinuationLikely = false))
            .isTrue()
        assertThat(isAttachmentShownToNia(directlyAddressed = false, replyToNia = false, niaTurnContinuationLikely = true))
            .isTrue()
        assertThat(isAttachmentShownToNia(directlyAddressed = false, replyToNia = false, niaTurnContinuationLikely = false))
            .isFalse()
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(40, 80, 120)
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun assertDecodedDimensions(
        base64: String,
        width: Int,
        height: Int,
    ) {
        val decoded =
            ByteArrayInputStream(Base64.getDecoder().decode(base64)).use { input ->
                checkNotNull(ImageIO.read(input))
            }
        assertThat(decoded.width).isEqualTo(width)
        assertThat(decoded.height).isEqualTo(height)
    }
}
