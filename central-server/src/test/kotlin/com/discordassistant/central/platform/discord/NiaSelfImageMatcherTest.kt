package com.discordassistant.central.platform.discord

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class NiaSelfImageMatcherTest {
    private val officialImage = resourceImage("/static/img/mascot-ai.png")
    private val matcher = NiaSelfImageMatcher.fromReference(officialImage)

    @Test
    fun `공식 외형의 리사이즈 압축 크롭 좌우반전은 자기 이미지로 본다`() {
        val cropped =
            officialImage.getSubimage(
                officialImage.width / 12,
                officialImage.height / 12,
                officialImage.width * 5 / 6,
                officialImage.height * 5 / 6,
            )

        assertThat(matcher.matches(officialImage)).isTrue()
        assertThat(matcher.matches(resize(officialImage, 640, 640))).isTrue()
        assertThat(matcher.matches(jpegRoundTrip(officialImage))).isTrue()
        assertThat(matcher.matches(cropped)).isTrue()
        assertThat(matcher.matches(resize(cropped, 568, 555))).isTrue()
        assertThat(matcher.matches(mirror(officialImage))).isTrue()
    }

    @Test
    fun `색이나 캐릭터 분위기가 비슷해도 다른 이미지는 자기 이미지로 보지 않는다`() {
        assertThat(matcher.matches(resourceImage("/static/img/nexa-logo.png"))).isFalse()
        assertThat(matcher.matches(resourceImage("/static/img/provider-img1.png"))).isFalse()
        assertThat(matcher.matches(resourceImage("/static/img/provider-img2.png"))).isFalse()
        assertThat(matcher.matches(solidImage(256, 256, Color(90, 75, 145)))).isFalse()
    }

    @Test
    fun `배포 JAR의 공식 외형 리소스로 matcher를 만들 수 있다`() {
        assertThat(NiaSelfImageMatcher.fromClasspathOrNull()).isNotNull()
    }

    private fun resourceImage(path: String): BufferedImage =
        checkNotNull(javaClass.getResourceAsStream(path)).use { input ->
            checkNotNull(ImageIO.read(input))
        }

    private fun resize(
        source: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun jpegRoundTrip(source: BufferedImage): BufferedImage {
        val encoded =
            ByteArrayOutputStream().use { output ->
                check(ImageIO.write(resize(source, 384, 384), "jpeg", output))
                output.toByteArray()
            }
        return ByteArrayInputStream(encoded).use { input -> checkNotNull(ImageIO.read(input)) }
    }

    private fun mirror(source: BufferedImage): BufferedImage {
        val output = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        try {
            graphics.drawImage(source, source.width, 0, 0, source.height, 0, 0, source.width, source.height, null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun solidImage(
        width: Int,
        height: Int,
        color: Color,
    ): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.color = color
                graphics.fillRect(0, 0, width, height)
            } finally {
                graphics.dispose()
            }
        }
}
