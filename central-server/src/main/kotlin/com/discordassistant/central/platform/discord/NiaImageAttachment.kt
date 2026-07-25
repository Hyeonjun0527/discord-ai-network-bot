package com.discordassistant.central.platform.discord

import com.discordassistant.central.speech.domain.model.LocalSpeechTemplate
import com.discordassistant.central.speech.domain.model.SpeechImageInput
import com.discordassistant.central.speech.domain.model.SpeechImageMediaType
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.utils.FileProxy
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** 첨부가 단순히 채널에 올라온 것이 아니라 현재 니아 턴에 보여 준 것인지 판정한다. */
internal fun isAttachmentShownToNia(
    directlyAddressed: Boolean,
    replyToNia: Boolean,
    niaTurnContinuationLikely: Boolean,
): Boolean = directlyAddressed || replyToNia || niaTurnContinuationLikely

internal sealed interface TargetedImagePreparation {
    data object NoImage : TargetedImagePreparation

    data class Ready(
        val image: SpeechImageInput,
    ) : TargetedImagePreparation

    data class Rejected(
        val template: LocalSpeechTemplate,
    ) : TargetedImagePreparation
}

/**
 * Discord 첨부를 최대 1024x1024 안으로 축소해 단일 Vision 입력으로 만든다.
 *
 * 이 컴포넌트는 [isAttachmentShownToNia]가 참인 경우에만 호출해야 한다. URL·파일명·원본 바이트는 반환하거나
 * 저장하지 않고, 재인코딩된 PNG/JPEG만 반환한다.
 */
@Component
class DiscordImageAttachmentPreparer {
    internal fun prepare(attachments: List<Message.Attachment>): TargetedImagePreparation {
        val imageAttachments = attachments.filter { it.isPotentialImage() }
        if (imageAttachments.isEmpty()) return TargetedImagePreparation.NoImage
        if (imageAttachments.size > 1) {
            return TargetedImagePreparation.Rejected(LocalSpeechTemplate.IMAGE_ONE_AT_A_TIME)
        }

        val attachment = imageAttachments.single()
        // Discord proxy는 이미지를 다른 형식으로 변환할 수 있으므로 원본을 받아 아래의 검증된 경로에서 축소한다.
        val download = FileProxy(attachment.url).download()
        return try {
            val bytes =
                download
                    .get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .use { input ->
                        input.readNBytes(MAX_DOWNLOAD_BYTES + 1).also {
                            if (it.size > MAX_DOWNLOAD_BYTES) throw ImagePayloadTooLargeException()
                        }
                    }
            TargetedImagePreparation.Ready(prepareSpeechImage(bytes))
        } catch (_: UnsupportedImageFormatException) {
            TargetedImagePreparation.Rejected(LocalSpeechTemplate.IMAGE_FORMAT_UNSUPPORTED)
        } catch (_: TimeoutException) {
            download.cancel(true)
            TargetedImagePreparation.Rejected(LocalSpeechTemplate.IMAGE_LOAD_FAILED)
        } catch (_: InterruptedException) {
            download.cancel(true)
            Thread.currentThread().interrupt()
            TargetedImagePreparation.Rejected(LocalSpeechTemplate.IMAGE_LOAD_FAILED)
        } catch (_: Exception) {
            TargetedImagePreparation.Rejected(LocalSpeechTemplate.IMAGE_LOAD_FAILED)
        }
    }

    private fun Message.Attachment.isPotentialImage(): Boolean =
        isImage ||
            contentType?.startsWith("image/", ignoreCase = true) == true ||
            fileName.substringAfterLast('.', "").lowercase() in IMAGE_FILE_EXTENSIONS

    companion object {
        private const val DOWNLOAD_TIMEOUT_SECONDS: Long = 5
        private const val MAX_DOWNLOAD_BYTES: Int = 10 * 1024 * 1024
        private val IMAGE_FILE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
    }
}

/** 네트워크와 무관한 검증·축소 함수. 테스트와 Discord adapter가 같은 경계를 사용한다. */
internal fun prepareSpeechImage(bytes: ByteArray): SpeechImageInput {
    if (bytes.isEmpty()) throw UnsupportedImageFormatException()
    val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw UnsupportedImageFormatException()
    input.use { imageInput ->
        val readers = ImageIO.getImageReaders(imageInput)
        if (!readers.hasNext()) throw UnsupportedImageFormatException()
        val reader = readers.next()
        return try {
            reader.input = imageInput
            val sourceWidth = reader.getWidth(0)
            val sourceHeight = reader.getHeight(0)
            require(sourceWidth > 0 && sourceHeight > 0) { "이미지 크기가 잘못됐다: ${sourceWidth}x$sourceHeight" }
            if (sourceWidth.toLong() * sourceHeight > MAX_SOURCE_PIXELS) throw ImagePayloadTooLargeException()

            val source = reader.read(0) ?: throw UnsupportedImageFormatException()
            val dimensions = fitWithin(sourceWidth, sourceHeight, SpeechImageInput.MAX_DIMENSION)
            val mediaType =
                when (reader.formatName.lowercase()) {
                    "jpg", "jpeg" -> SpeechImageMediaType.JPEG
                    "png" -> SpeechImageMediaType.PNG
                    "gif" -> {
                        if (reader.getNumImages(true) != 1) throw UnsupportedImageFormatException()
                        SpeechImageMediaType.PNG
                    }
                    else -> throw UnsupportedImageFormatException()
                }
            val resized = resize(source, dimensions.first, dimensions.second, mediaType)
            val isNiaSelfImage = defaultNiaSelfImageMatcher?.matches(resized) == true
            val encoded = encode(resized, mediaType)
            if (encoded.size > MAX_ENCODED_BYTES) throw ImagePayloadTooLargeException()
            SpeechImageInput(
                mediaType = mediaType,
                base64Data = Base64.getEncoder().encodeToString(encoded),
                width = dimensions.first,
                height = dimensions.second,
                isNiaSelfImage = isNiaSelfImage,
            )
        } finally {
            reader.dispose()
        }
    }
}

internal fun fitWithin(
    width: Int,
    height: Int,
    maxDimension: Int,
): Pair<Int, Int> {
    require(width > 0 && height > 0) { "이미지 크기는 양수여야 한다" }
    require(maxDimension > 0) { "maxDimension은 양수여야 한다" }
    val scale = min(1.0, min(maxDimension.toDouble() / width, maxDimension.toDouble() / height))
    return (width * scale).roundToInt().coerceIn(1, maxDimension) to
        (height * scale).roundToInt().coerceIn(1, maxDimension)
}

private fun resize(
    source: BufferedImage,
    width: Int,
    height: Int,
    mediaType: SpeechImageMediaType,
): BufferedImage {
    val outputType =
        if (mediaType == SpeechImageMediaType.JPEG) {
            BufferedImage.TYPE_INT_RGB
        } else {
            BufferedImage.TYPE_INT_ARGB
        }
    val output = BufferedImage(width, height, outputType)
    val graphics = output.createGraphics()
    try {
        if (outputType == BufferedImage.TYPE_INT_RGB) {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
        }
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.drawImage(source, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    return output
}

private fun encode(
    image: BufferedImage,
    mediaType: SpeechImageMediaType,
): ByteArray {
    val output = ByteArrayOutputStream()
    if (mediaType == SpeechImageMediaType.PNG) {
        check(ImageIO.write(image, "png", output)) { "PNG writer를 찾을 수 없다" }
        return output.toByteArray()
    }

    val writers = ImageIO.getImageWritersByFormatName("jpeg")
    check(writers.hasNext()) { "JPEG writer를 찾을 수 없다" }
    val writer = writers.next()
    MemoryCacheImageOutputStream(output).use { imageOutput ->
        try {
            writer.output = imageOutput
            val params = writer.defaultWriteParam
            if (params.canWriteCompressed()) {
                params.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                params.compressionQuality = JPEG_QUALITY
            }
            writer.write(null, IIOImage(image, null, null), params)
        } finally {
            writer.dispose()
        }
    }
    return output.toByteArray()
}

private class UnsupportedImageFormatException : RuntimeException()

private class ImagePayloadTooLargeException : RuntimeException()

private val defaultNiaSelfImageMatcher by lazy(LazyThreadSafetyMode.PUBLICATION) {
    NiaSelfImageMatcher.fromClasspathOrNull()
}

private const val MAX_SOURCE_PIXELS: Long = 20_000_000
private const val MAX_ENCODED_BYTES: Int = 6 * 1024 * 1024
private const val JPEG_QUALITY: Float = 0.9f
