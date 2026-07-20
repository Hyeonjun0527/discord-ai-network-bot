package com.discordassistant.central.participation.adapter.outbound.embedding

import com.discordassistant.central.participation.application.port.out.ConversationEmbeddingPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class OpenAiConversationEmbeddingAdapter(
    @param:Value("\${central.cloud.openai-api-key:}") private val apiKey: String,
    @param:Value("\${central.cloud.openai-base-url:https://api.openai.com/v1}") private val baseUrl: String,
    @param:Value("\${central.nexa.conversation-rag.embedding-model:text-embedding-3-small}")
    override val model: String,
    @param:Value("\${central.nexa.conversation-rag.timeout-seconds:15}") private val timeoutSeconds: Long = 15,
) : ConversationEmbeddingPort {
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun isConfigured(): Boolean = apiKey.isNotBlank()

    override fun embed(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "embedding input 은 비어 있을 수 없다" }
        require(texts.size <= MAX_BATCH_SIZE) { "embedding batch 는 최대 $MAX_BATCH_SIZE 개다" }
        check(isConfigured()) { "OpenAI embedding API 가 설정되지 않았다" }
        val body =
            mapper.createObjectNode().apply {
                put("model", model)
                val input = putArray("input")
                texts.forEach(input::add)
            }
        val request =
            HttpRequest
                .newBuilder(URI.create("${baseUrl.trimEnd('/')}/embeddings"))
                .timeout(Duration.ofSeconds(timeoutSeconds.coerceAtLeast(1)))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "OpenAI embedding 호출 실패: HTTP ${response.statusCode()}" }
        val data = mapper.readTree(response.body()).path("data")
        check(data.isArray && data.size() == texts.size) { "OpenAI embedding 응답 개수가 일치하지 않는다" }
        return data
            .sortedBy { it.path("index").asInt() }
            .map { row -> row.path("embedding").map { it.floatValue() }.toFloatArray() }
    }

    companion object {
        const val MAX_BATCH_SIZE = 500
    }
}
