package com.discordassistant.central.actionruntime.application.content

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 하나의 영속 content 행에 Discord 버블 목록을 보존하는 하위 호환 codec.
 * 기존 plain text 행은 단일 버블로 읽고, 새 행만 버전 marker와 Base64 줄을 사용한다.
 */
object SpeechBurstContentCodec {
    private const val MARKER = "NEXA_BURST_V1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bubbles: List<String>): String {
        require(bubbles.isNotEmpty()) { "발화 버블은 최소 1개여야 한다" }
        require(bubbles.all { it.isNotBlank() }) { "빈 발화 버블은 저장할 수 없다" }
        return buildString {
            appendLine(MARKER)
            bubbles.forEachIndexed { index, bubble ->
                append(encoder.encodeToString(bubble.toByteArray(StandardCharsets.UTF_8)))
                if (index < bubbles.lastIndex) appendLine()
            }
        }
    }

    /** marker가 없는 과거 데이터는 단일 버블로 반환한다. 손상된 marker 데이터는 전송하지 않도록 빈 목록으로 내린다. */
    fun decode(stored: String): List<String> {
        if (!stored.startsWith("$MARKER\n")) return listOf(stored)
        val encoded =
            stored
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() }
                .toList()
        if (encoded.isEmpty()) return emptyList()
        return encoded.map { part ->
            runCatching { String(decoder.decode(part), StandardCharsets.UTF_8) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return emptyList()
        }
    }
}
