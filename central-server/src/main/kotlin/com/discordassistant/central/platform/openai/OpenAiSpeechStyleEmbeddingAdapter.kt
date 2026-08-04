package com.discordassistant.central.platform.openai

import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Speech 말투 RAG 전용 OpenAI Embeddings 어댑터.
 *
 * 이 호출은 실제 SPEAK 경로에서만 일어나며, Judge에는 배선되지 않는다. 외부 응답/오류 본문은 로그에 남기지 않는다.
 */
@Component
class OpenAiSpeechStyleEmbeddingAdapter(
    @param:Value("\${central.cloud.openai-api-key:}") private val apiKey: String,
    @param:Value("\${central.cloud.openai-base-url:https://api.openai.com/v1}") private val baseUrl: String,
    @param:Value("\${central.nexa.speech-style-rag.embedding-model:text-embedding-3-small}")
    private val model: String,
    @Value("\${central.nexa.speech-style-rag.embedding-timeout-seconds:8}")
    timeoutSeconds: Long,
    private val metrics: SpeechStyleEmbeddingMetrics = SpeechStyleEmbeddingMetrics.Noop,
) : SpeechStyleEmbeddingPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()
    private val requestTimeout = Duration.ofSeconds(timeoutSeconds.coerceAtLeast(1))

    override fun embedAll(texts: List<String>): List<FloatArray>? {
        if (apiKey.isBlank() || texts.isEmpty() || texts.any { it.isBlank() }) return null
        val embeddings = mutableListOf<FloatArray>()
        texts.chunked(MAX_INPUTS_PER_REQUEST).forEach { inputs ->
            val batch = requestEmbeddings(inputs) ?: return null
            embeddings += batch
        }
        return embeddings
    }

    private fun requestEmbeddings(inputs: List<String>): List<FloatArray>? {
        val payload = requestPayloadJson(inputs)
        metrics.recordAttempt(model, payload.length)
        val request =
            HttpRequest
                .newBuilder(URI.create("${baseUrl.trimEnd('/')}/embeddings"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                log.warn("Speech style embedding request unavailable: httpStatus={}", response.statusCode())
                null
            } else {
                parseEmbeddings(response.body(), inputs.size)
                    ?.also { parsed ->
                        metrics.recordInputTokens(model, parsed.inputTokens)
                    }?.vectors
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("Speech style embedding request interrupted")
            null
        } catch (error: java.io.IOException) {
            log.warn("Speech style embedding request failed: {}", error.javaClass.simpleName)
            null
        } catch (error: IllegalArgumentException) {
            log.warn("Speech style embedding response was invalid: {}", error.javaClass.simpleName)
            null
        }
    }

    internal fun requestPayloadJson(inputs: List<String>): String =
        mapper.writeValueAsString(
            mapper
                .createObjectNode()
                .apply {
                    put("model", model)
                    put("encoding_format", "float")
                    putArray("input").apply { inputs.forEach(::add) }
                },
        )

    private fun parseEmbeddings(
        body: String,
        expectedSize: Int,
    ): ParsedEmbeddings? =
        try {
            val root = mapper.readTree(body)
            val values =
                root
                    .path("data")
                    .map { row ->
                        row
                            .path("embedding")
                            .map { value -> value.floatValue() }
                            .toFloatArray()
                    }
            if (values.size == expectedSize && values.all { it.isNotEmpty() && it.all(Float::isFinite) }) {
                ParsedEmbeddings(values, root.path("usage").path("prompt_tokens").asInt(0))
            } else {
                null
            }
        } catch (error: com.fasterxml.jackson.core.JsonProcessingException) {
            log.warn("Speech style embedding response could not be parsed")
            null
        }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS: Long = 5
        private const val MAX_INPUTS_PER_REQUEST: Int = 64
    }

    private data class ParsedEmbeddings(
        val vectors: List<FloatArray>,
        val inputTokens: Int,
    )
}
