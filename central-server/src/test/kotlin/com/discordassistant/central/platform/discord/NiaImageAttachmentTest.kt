package com.discordassistant.central.platform.discord

import com.discordassistant.central.speech.domain.model.SpeechImageMediaType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

class NiaImageAttachmentTest {
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
        assertDecodedDimensions(result.base64Data, 300, 200)
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
