package com.discordassistant.central.routing.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 클라우드 LLM 토큰 사용량(있으면). 통계/로그용 — 없으면 0. */
data class CloudLlmUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

/** 클라우드 LLM 추론 1건 결과: 답변 텍스트 + 선택적 사용량. */
data class CloudLlmResult(
    val text: String,
    val usage: CloudLlmUsage = CloudLlmUsage(),
)

/** 클라우드 LLM 호출/응답 오류. */
class CloudLlmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 무료질문(glm-*)을 **중앙 서버가 관리자 키 1개로 직접** 처리하는 백엔드 포트(ADR 0006).
 * 유저가 앱(provider-agent)을 설치하지 않아도 /질문 을 쓸 수 있게, 풀 라우팅 대신 central 이
 * 직접 클라우드(z.ai)에 추론을 요청한다. 외부 HTTP 는 **중앙 서버**에서만 일어나며(에이전트
 * 임의 URL 호출 금지 원칙 유지·강화), WebSearchAugmenter 의 선례와 같은 형태로 정의한다.
 */
interface CloudLlm {
    fun isEnabled(): Boolean

    fun generate(
        prompt: String,
        model: String,
    ): CloudLlmResult
}

/** 기본(비활성) — 클라우드 키 미설정 시. 호출되면 예외(라우팅은 isEnabled() 로 먼저 분기). */
object NoCloudLlm : CloudLlm {
    override fun isEnabled() = false

    override fun generate(
        prompt: String,
        model: String,
    ): CloudLlmResult = throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")
}

/**
 * 순수 함수 파서(테스트 가능, 외부 서비스 불필요). z.ai 는 OpenAI 호환이라 chat/completions
 * 응답의 `choices[0].message.content` 를 답변으로, `usage` 를 사용량으로 추출한다.
 * 비정상/빈 응답이면 [CloudLlmException].
 */
object CloudLlmResponseParser {
    fun parse(
        body: String,
        mapper: ObjectMapper,
    ): CloudLlmResult {
        val root =
            try {
                mapper.readTree(body)
            } catch (e: Exception) {
                throw CloudLlmException("클라우드 AI 응답 파싱 실패", e)
            }
        val error = root.get("error")
        if (error != null && !error.isNull) {
            // 업스트림 에러 원문은 노출하지 않고 일반화 메시지만(예외 원칙 — 내부 상세 미노출).
            throw CloudLlmException(USER_ERROR_MESSAGE)
        }
        val message =
            root
                .get("choices")
                ?.takeIf { it.isArray && it.size() > 0 }
                ?.get(0)
                ?.get("message")
        val content =
            message
                ?.get("content")
                ?.takeIf { it.isTextual }
                ?.asText()
                ?.takeIf { it.isNotBlank() }
                ?: throw CloudLlmException("클라우드 AI 응답에 텍스트가 없습니다(안전 필터 차단 또는 빈 응답).")
        val usageNode = root.get("usage")
        val usage =
            CloudLlmUsage(
                promptTokens = usageNode?.get("prompt_tokens")?.asInt(0) ?: 0,
                completionTokens = usageNode?.get("completion_tokens")?.asInt(0) ?: 0,
            )
        return CloudLlmResult(content.trim(), usage)
    }

    /** 사용자(디스코드)에게 노출되는 일반화 메시지. 업스트림 status·body 등 상세는 로그로만 남긴다. */
    const val USER_ERROR_MESSAGE = "클라우드 AI 일시 오류"
}

/**
 * z.ai(GLM, OpenAI 호환) 구현. `central.cloud.zai-api-key` 미설정이면 비활성(isEnabled=false →
 * 라우팅이 기존 에이전트 경로로 폴백). 키가 있으면 `POST {base}/chat/completions` 로 직접 호출한다.
 * WebSearchAugmenter 의 SearxngWebSearch 와 **동일한 HTTP 클라이언트(java.net.http)** · @Value 키
 * 주입 · @Component 배치를 따른다.
 */
@Component
class ZaiCloudLlm(
    @param:Value("\${central.cloud.zai-api-key:}") private val apiKey: String,
    @param:Value("\${central.cloud.zai-base-url:https://api.z.ai/api/paas/v4}") private val baseUrl: String,
    @param:Value("\${central.cloud.timeout-seconds:120}") private val timeoutSeconds: Long,
) : CloudLlm {
    private val log = LoggerFactory.getLogger(ZaiCloudLlm::class.java)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun isEnabled() = apiKey.isNotBlank()

    override fun generate(
        prompt: String,
        model: String,
    ): CloudLlmResult {
        if (!isEnabled()) throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")
        val base = baseUrl.trimEnd('/')
        val payload =
            mapper
                .createObjectNode()
                .put("model", model.ifBlank { DEFAULT_MODEL })
                .apply {
                    putArray("messages").addObject().put("role", "user").put("content", prompt)
                }
        val req =
            HttpRequest
                .newBuilder(URI.create("$base/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build()
        val resp =
            try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                // 연결 실패 상세는 로그로만, 사용자에겐 일반화 메시지.
                log.warn("클라우드 LLM 연결 실패: {}", e.javaClass.simpleName)
                throw CloudLlmException(CloudLlmResponseParser.USER_ERROR_MESSAGE, e)
            }
        if (resp.statusCode() !in 200..299) {
            // 업스트림 status·body 는 정보 노출 소지가 있어 로그로만 남긴다(예외 원칙).
            log.warn("클라우드 LLM HTTP {}: {}", resp.statusCode(), resp.body().take(500))
            throw CloudLlmException(CloudLlmResponseParser.USER_ERROR_MESSAGE)
        }
        return CloudLlmResponseParser.parse(resp.body(), mapper)
    }

    companion object {
        const val DEFAULT_MODEL = "glm-5.1"
    }
}
