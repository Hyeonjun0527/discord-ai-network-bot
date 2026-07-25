package com.discordassistant.central.platform.discord

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class NiaSelfImageMatcher private constructor(
    referenceImage: BufferedImage,
) {
    private val referenceFingerprints = CROP_SCALES.map { fingerprint(referenceImage, it, mirrored = false) }

    fun matches(candidateImage: BufferedImage): Boolean {
        val candidateFingerprints =
            CROP_SCALES.flatMap { cropScale ->
                listOf(
                    fingerprint(candidateImage, cropScale, mirrored = false),
                    fingerprint(candidateImage, cropScale, mirrored = true),
                )
            }
        return candidateFingerprints.any { candidate ->
            referenceFingerprints.any { reference ->
                java.lang.Long.bitCount(candidate.perceptualHash xor reference.perceptualHash) <=
                    MAX_HAMMING_DISTANCE &&
                    correlation(candidate, reference) >= MIN_CORRELATION
            }
        }
    }

    companion object {
        private const val REFERENCE_RESOURCE = "/static/img/mascot-ai.png"
        private const val HASH_INPUT_SIZE = 32
        private const val HASH_LOW_FREQUENCY_SIZE = 8

        // 단일 지각 해시만 느슨하게 쓰지 않고, 실제 변환은 통과하고 다른 브랜드 이미지는 탈락하는 두 조건을 함께 요구한다.
        private const val MAX_HAMMING_DISTANCE = 22
        private const val MIN_CORRELATION = 0.50
        private val CROP_SCALES = listOf(1.0, 0.96, 0.92, 0.88, 0.84, 0.80)
        private val COSINES =
            Array(HASH_LOW_FREQUENCY_SIZE) { frequency ->
                DoubleArray(HASH_INPUT_SIZE) { position ->
                    cos((2 * position + 1) * frequency * PI / (2 * HASH_INPUT_SIZE))
                }
            }

        fun fromReference(referenceImage: BufferedImage): NiaSelfImageMatcher = NiaSelfImageMatcher(referenceImage)

        fun fromClasspathOrNull(): NiaSelfImageMatcher? {
            val input = NiaSelfImageMatcher::class.java.getResourceAsStream(REFERENCE_RESOURCE) ?: return null
            return try {
                input.use { ImageIO.read(it)?.let(::NiaSelfImageMatcher) }
            } catch (_: IOException) {
                null
            }
        }

        private fun fingerprint(
            image: BufferedImage,
            cropScale: Double,
            mirrored: Boolean,
        ): ImageFingerprint {
            val normalized = normalize(image, cropScale, mirrored)
            val luminance = DoubleArray(HASH_INPUT_SIZE * HASH_INPUT_SIZE)
            var luminanceSum = 0.0
            for (y in 0 until HASH_INPUT_SIZE) {
                for (x in 0 until HASH_INPUT_SIZE) {
                    val rgb = normalized.getRGB(x, y)
                    val red = rgb shr 16 and 0xff
                    val green = rgb shr 8 and 0xff
                    val blue = rgb and 0xff
                    val value = (red * 299 + green * 587 + blue * 114) / 1_000.0
                    luminance[y * HASH_INPUT_SIZE + x] = value
                    luminanceSum += value
                }
            }
            val coefficients = ArrayList<Double>(HASH_LOW_FREQUENCY_SIZE * HASH_LOW_FREQUENCY_SIZE - 1)
            for (verticalFrequency in 0 until HASH_LOW_FREQUENCY_SIZE) {
                for (horizontalFrequency in 0 until HASH_LOW_FREQUENCY_SIZE) {
                    if (horizontalFrequency == 0 && verticalFrequency == 0) continue
                    var sum = 0.0
                    for (y in 0 until HASH_INPUT_SIZE) {
                        for (x in 0 until HASH_INPUT_SIZE) {
                            sum +=
                                luminance[y * HASH_INPUT_SIZE + x] *
                                COSINES[horizontalFrequency][x] *
                                COSINES[verticalFrequency][y]
                        }
                    }
                    coefficients +=
                        sum *
                        normalization(horizontalFrequency) *
                        normalization(verticalFrequency)
                }
            }
            val median = coefficients.sorted()[coefficients.size / 2]
            val perceptualHash =
                coefficients.foldIndexed(0L) { index, hash, value ->
                    if (value > median) hash or (1L shl index) else hash
                }
            val mean = luminanceSum / luminance.size
            var squaredMagnitude = 0.0
            for (index in luminance.indices) {
                luminance[index] -= mean
                squaredMagnitude += luminance[index] * luminance[index]
            }
            return ImageFingerprint(
                perceptualHash = perceptualHash,
                centeredLuminance = luminance,
                magnitude = sqrt(squaredMagnitude),
            )
        }

        private fun normalize(
            image: BufferedImage,
            cropScale: Double,
            mirrored: Boolean,
        ): BufferedImage {
            val cropWidth = (image.width * cropScale).roundToInt().coerceIn(1, image.width)
            val cropHeight = (image.height * cropScale).roundToInt().coerceIn(1, image.height)
            val sourceLeft = (image.width - cropWidth) / 2
            val sourceTop = (image.height - cropHeight) / 2
            val output = BufferedImage(HASH_INPUT_SIZE, HASH_INPUT_SIZE, BufferedImage.TYPE_INT_RGB)
            val graphics = output.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, HASH_INPUT_SIZE, HASH_INPUT_SIZE)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                val targetLeft = if (mirrored) HASH_INPUT_SIZE else 0
                val targetRight = if (mirrored) 0 else HASH_INPUT_SIZE
                graphics.drawImage(
                    image,
                    targetLeft,
                    0,
                    targetRight,
                    HASH_INPUT_SIZE,
                    sourceLeft,
                    sourceTop,
                    sourceLeft + cropWidth,
                    sourceTop + cropHeight,
                    null,
                )
            } finally {
                graphics.dispose()
            }
            return output
        }

        private fun normalization(frequency: Int): Double =
            if (frequency == 0) {
                sqrt(1.0 / HASH_INPUT_SIZE)
            } else {
                sqrt(2.0 / HASH_INPUT_SIZE)
            }

        private fun correlation(
            first: ImageFingerprint,
            second: ImageFingerprint,
        ): Double {
            if (first.magnitude == 0.0 || second.magnitude == 0.0) return 0.0
            var dotProduct = 0.0
            for (index in first.centeredLuminance.indices) {
                dotProduct += first.centeredLuminance[index] * second.centeredLuminance[index]
            }
            return dotProduct / (first.magnitude * second.magnitude)
        }
    }

    private data class ImageFingerprint(
        val perceptualHash: Long,
        val centeredLuminance: DoubleArray,
        val magnitude: Double,
    )
}
