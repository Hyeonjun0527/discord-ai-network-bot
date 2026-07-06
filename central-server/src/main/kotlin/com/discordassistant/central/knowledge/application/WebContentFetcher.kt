package com.discordassistant.central.knowledge.application

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * SSRF 방어: 가져와도 되는 **public http(s) URL** 인지 판정.
 * - http/https 만, userinfo 금지.
 * - localhost/.local/.internal 호스트 차단.
 * - 호스트가 해석되는 **모든 주소**가 public 이어야 함(루프백·사설·링크로컬·멀티캐스트 차단).
 * 리다이렉트는 fetcher 가 따라가지 않으므로(NEVER) 리다이렉트-SSRF 도 막힌다.
 */
object UrlSafety {
    fun isFetchAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.rawUserInfo != null) return false
        val host =
            uri.host
                ?.lowercase()
                ?.trimEnd('.')
                ?.removeSurrounding("[", "]")
                ?.ifBlank { null } ?: return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return false
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        if (addresses.isEmpty()) return false
        return addresses.none {
            it.isLoopbackAddress ||
                it.isSiteLocalAddress ||
                it.isLinkLocalAddress ||
                it.isAnyLocalAddress ||
                it.isMulticastAddress
        }
    }
}

/** HTML → 본문 텍스트(순수 함수, 테스트 가능). script/style 제거 + 태그 제거 + 엔티티/공백 정리. */
object HtmlText {
    private val SCRIPT_STYLE = Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>")
    private val TAGS = Regex("(?s)<[^>]+>")
    private val WHITESPACE = Regex("\\s+")

    fun extract(
        html: String,
        maxChars: Int = 1500,
    ): String {
        var t = SCRIPT_STYLE.replace(html, " ")
        t = TAGS.replace(t, " ")
        t =
            t
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
        return WHITESPACE.replace(t, " ").trim().take(maxChars)
    }
}

/**
 * 검색 결과 상위 URL 의 **본문**을 가져와 스니펫보다 깊은 컨텍스트를 만든다(검색 품질의 핵심).
 * SSRF 차단(UrlSafety) + 크기/시간 상한 + 리다이렉트 미추적 + HTML 만.
 */
class WebContentFetcher(
    private val maxBytes: Int = 512 * 1024,
    private val timeoutSeconds: Long = 5,
    private val maxChars: Int = 1500,
) {
    private val log = LoggerFactory.getLogger(WebContentFetcher::class.java)
    private val http =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER) // 리다이렉트-SSRF 방지
            .build()

    /** URL 본문 텍스트. 차단/실패/비HTML/리다이렉트면 null. */
    fun fetchText(url: String): String? {
        if (!UrlSafety.isFetchAllowed(url)) return null
        return try {
            val req =
                HttpRequest
                    .newBuilder(URI(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", "nexa/websearch")
                    .GET()
                    .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() !in 200..299) return null
            val contentType =
                resp
                    .headers()
                    .firstValue("content-type")
                    .orElse("")
                    .lowercase()
            if (!contentType.contains("html") && !contentType.contains("text")) return null
            // 스트리밍 상한: 전체 바디를 메모리에 올리지 않고 최대 maxBytes 바이트만 읽어(OOM/무한바디 방지)
            // UTF-8 로 디코드한다. char 가 아니라 byte 로 상한을 걸어 실제 다운로드 크기를 제한한다.
            val bytes = resp.body().use { it.readAtMost(maxBytes) }
            HtmlText.extract(String(bytes, Charsets.UTF_8), maxChars).ifBlank { null }
        } catch (e: Exception) {
            log.debug("본문 fetch 실패 {}: {}", url, e.javaClass.simpleName)
            null
        }
    }

    /** 스트림에서 최대 [limit] 바이트까지만 읽는다(초과분은 버려 OOM 방지). */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 16 * 1024).coerceAtLeast(64))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - total))
            if (read < 0) break
            out.write(chunk, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}
