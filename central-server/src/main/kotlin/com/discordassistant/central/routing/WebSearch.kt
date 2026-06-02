package com.discordassistant.central.routing

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** 웹 검색 1건. */
data class WebResult(
    val title: String,
    val url: String,
    val snippet: String,
)

/** 증강 결과: 모델에 보낼 프롬프트 + 인용 출처 URL. */
data class WebAugmentation(
    val prompt: String,
    val sources: List<String>,
)

/**
 * 웹검색으로 프롬프트를 **서버측에서** 증강한다(로컬 모델이 웹 정보로 답하게).
 * 로컬 LLM 은 웹을 못 보므로, 서버가 검색해 결과를 프롬프트에 주입한다.
 * 검색은 프로바이더 PC 가 아니라 **중앙 서버**에서 수행한다(에이전트 임의 URL 호출 금지 원칙 유지).
 */
interface WebSearchAugmenter {
    fun isEnabled(): Boolean

    fun augment(prompt: String): WebAugmentation
}

/** 기본(비활성) — 검색 백엔드 미설정 시. 원본 프롬프트 그대로 사용. */
object NoWebSearch : WebSearchAugmenter {
    override fun isEnabled() = false

    override fun augment(prompt: String) = WebAugmentation(prompt, emptyList())
}

/** 순수 함수: 검색 결과로 프롬프트를 증강(테스트 가능). 결과가 없으면 원본 그대로. */
object WebSearchPromptBuilder {
    fun build(
        query: String,
        results: List<WebResult>,
        maxResults: Int = 5,
    ): WebAugmentation {
        if (results.isEmpty()) return WebAugmentation(query, emptyList())
        val top = results.take(maxResults.coerceAtLeast(1))
        val prompt =
            buildString {
                appendLine("다음은 웹 검색 결과입니다. 이 정보를 근거로 질문에 답하고, 사용한 출처를 [n] 형식으로 인용하세요.")
                appendLine("검색 결과로 답할 수 없으면 모른다고 답하세요. 추측하지 마세요.")
                appendLine()
                top.forEachIndexed { i, r ->
                    appendLine("[${i + 1}] ${r.title}")
                    appendLine("URL: ${r.url}")
                    if (r.snippet.isNotBlank()) appendLine(r.snippet.take(500))
                    appendLine()
                }
                appendLine("질문: $query")
            }
        return WebAugmentation(prompt, top.map { it.url })
    }
}

/**
 * SearXNG(JSON API) 기반 구현. `central.search.url` 미설정이면 비활성(원본 그대로).
 * 외부 검색 API 키 불필요 — 자체 호스팅 SearXNG 를 가리킨다.
 */
@Component
class SearxngWebSearch(
    @param:Value("\${central.search.url:}") private val searchUrl: String,
    @param:Value("\${central.search.max-results:5}") private val maxResults: Int,
    @param:Value("\${central.search.fetch-content:true}") private val fetchContent: Boolean,
) : WebSearchAugmenter {
    private val log = LoggerFactory.getLogger(SearxngWebSearch::class.java)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val fetcher = if (fetchContent) WebContentFetcher() else null

    // 본문 fetch 병렬용 작은 풀(IO 바운드, 소수 동시).
    private val fetchPool =
        java.util.concurrent.Executors
            .newFixedThreadPool(4)

    override fun isEnabled() = searchUrl.isNotBlank()

    override fun augment(prompt: String): WebAugmentation {
        if (!isEnabled()) return WebAugmentation(prompt, emptyList())
        return try {
            WebSearchPromptBuilder.build(prompt, enrich(search(prompt)), maxResults)
        } catch (e: Exception) {
            // 검색 실패는 치명적이지 않다 — 증강 없이 원본으로 진행(질문 원문은 로그하지 않음).
            log.warn("웹검색 실패 — 증강 없이 진행: {}", e.javaClass.simpleName)
            WebAugmentation(prompt, emptyList())
        }
    }

    /** 상위 결과의 본문을 병렬로 가져와 스니펫보다 깊은 컨텍스트로 대체(실패 시 스니펫 유지). */
    private fun enrich(results: List<WebResult>): List<WebResult> {
        val f = fetcher ?: return results
        val futures =
            results.map { r ->
                java.util.concurrent.CompletableFuture
                    .supplyAsync({ r to f.fetchText(r.url) }, fetchPool)
                    .exceptionally { r to null }
            }
        return futures.map { it.join() }.map { (r, text) -> if (!text.isNullOrBlank()) r.copy(snippet = text) else r }
    }

    private fun search(query: String): List<WebResult> {
        val base = searchUrl.trimEnd('/')
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val uri = URI.create("$base/search?format=json&safesearch=1&q=$q")
        val req =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) return emptyList()
        val arr = mapper.readTree(resp.body()).get("results") ?: return emptyList()
        return arr
            .take(maxResults)
            .map { n ->
                WebResult(
                    title = n.get("title")?.asText().orEmpty(),
                    url = n.get("url")?.asText().orEmpty(),
                    snippet = n.get("content")?.asText().orEmpty(),
                )
            }.filter { it.url.isNotBlank() }
    }
}
