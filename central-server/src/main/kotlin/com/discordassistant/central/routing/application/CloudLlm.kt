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

/**
 * 멀티턴 대화 한 turn. [role] 은 OpenAI 호환 `user`/`assistant`(시스템 프롬프트는 prompt 본문에 합쳐 보낸다).
 * /질문 의 채널+유저 단기 대화 기억(인메모리)을 z.ai messages 앞에 붙이는 데 쓴다.
 */
data class CloudTurn(
    val role: String,
    val content: String,
)

/**
 * z.ai GLM thinking(추론) 속도 라우팅 파라미터(공식 형식 `{"thinking":{"type":"enabled"|"disabled"}}`).
 * [ENABLED] = 깊은 추론(느림·정확), [DISABLED] = 즉답(빠름). null 이면 thinking 필드를 보내지 않는다(서버 기본).
 */
enum class CloudThinking(
    val wire: String,
) {
    ENABLED("enabled"),
    DISABLED("disabled"),
}

/** 클라우드 LLM 호출/응답 오류. */
class CloudLlmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 이미지 프롬프트 안전 심사 결과(central 직접 심사, ADR 0006 단계2). `allowed=false` 면 생성 차단.
 * 파싱/호출 실패는 상위가 fail-closed 로 차단한다(이 값으로 통과시키지 않는다).
 */
data class ImageReview(
    val allowed: Boolean,
    val reason: String? = null,
    val category: String? = null,
)

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

    /**
     * 멀티턴 대화 기억(/질문 채널+유저 단기 컨텍스트)과 thinking 속도 라우팅을 지원하는 확장 호출.
     * [history] 는 messages 앞에 시간순으로 붙고(이번 [prompt] 는 마지막 user turn), [thinking] 은 z.ai
     * GLM thinking 파라미터(null 이면 미전송 → 서버 기본). 기본 구현은 history/thinking 을 무시하고 단순
     * [generate] 로 위임하므로 기존 구현(테스트 스텁·speech·socialmemory)은 무변경으로 호환된다 — z.ai 구현만 override.
     */
    fun generate(
        prompt: String,
        model: String,
        history: List<CloudTurn>,
        thinking: CloudThinking?,
    ): CloudLlmResult = generate(prompt, model)

    /**
     * 이미지 프롬프트 안전 심사(ADR 0006 단계2). [systemPrompt] 는 central 소유 안전 정책(IMAGE_SAFETY).
     * 호출/파싱 실패는 [CloudLlmException] 으로 던져 상위가 fail-closed 차단하게 한다.
     */
    fun reviewImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): ImageReview

    /**
     * 이미지 프롬프트 한국어→영어 번역(성인·SFW·품질 prefix 강제). [systemPrompt] 는 central 소유 번역 정책.
     * 실패 시 [CloudLlmException] — 상위는 원문 폴백한다(번역 실패는 차단 사유가 아니다).
     */
    fun translateImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): String
}

/** 기본(비활성) — 클라우드 키 미설정 시. 호출되면 예외(라우팅은 isEnabled() 로 먼저 분기). */
object NoCloudLlm : CloudLlm {
    override fun isEnabled() = false

    override fun generate(
        prompt: String,
        model: String,
    ): CloudLlmResult = throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")

    override fun reviewImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): ImageReview = throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")

    override fun translateImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): String = throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")
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

    /**
     * 이미지 안전 심사 응답(chat/completions)에서 GLM 이 돌려준 JSON 심사 결과를 추출한다(provider-agent
     * glm.py `parse_image_prompt_review` 포팅). 코드펜스가 섞여도 첫 JSON object 만 허용하고,
     * `allowed`(boolean)가 없으면 fail-closed 예외 — 스키마가 조금이라도 깨지면 차단한다.
     */
    fun parseImageReview(
        body: String,
        mapper: ObjectMapper,
    ): ImageReview {
        // chat/completions 봉투에서 content 추출(error·빈 응답은 parse 가 일반화 예외로 던짐).
        val content = parse(body, mapper).text
        val json =
            try {
                mapper.readTree(extractFirstJsonObject(content))
            } catch (e: Exception) {
                throw CloudLlmException("이미지 안전 심사 응답 파싱 실패", e)
            }
        val allowedNode = json.get("allowed")
        if (allowedNode == null || !allowedNode.isBoolean) {
            throw CloudLlmException("이미지 안전 심사 응답에 allowed(boolean)가 없습니다")
        }
        val allowed = allowedNode.asBoolean()
        val category =
            json
                .get("category")
                ?.takeIf { it.isTextual }
                ?.asText()
                ?.trim()
                ?.take(40)
                ?: if (allowed) "safe" else "other"
        val reason =
            json
                .get("reason")
                ?.takeIf { it.isTextual }
                ?.asText()
                ?.trim()
                ?.take(240)
                ?: if (allowed) "허용됨" else "안전 정책상 차단됨"
        return ImageReview(allowed = allowed, reason = reason, category = category)
    }

    /** 코드펜스(```json … ```)를 벗기고 첫 `{ … }` object 만 남긴다(glm.py `_extract_json_object` 포팅). */
    private fun extractFirstJsonObject(text: String): String {
        var cleaned = text.trim()
        if (cleaned.startsWith("```")) {
            cleaned =
                cleaned
                    .removePrefix("```")
                    .removePrefix("json")
                    .trim()
                    .removeSuffix("```")
                    .trim()
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start in 0 until end) cleaned = cleaned.substring(start, end + 1)
        return cleaned
    }
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
    ): CloudLlmResult = CloudLlmResponseParser.parse(postChat(listOf("user" to prompt), model), mapper)

    override fun generate(
        prompt: String,
        model: String,
        history: List<CloudTurn>,
        thinking: CloudThinking?,
    ): CloudLlmResult {
        // 멀티턴: 히스토리(시간순)를 먼저, 이번 질문을 마지막 user turn 으로. thinking 은 z.ai GLM 형식으로 전달.
        val messages = history.map { it.role to it.content } + ("user" to prompt)
        return CloudLlmResponseParser.parse(postChat(messages, model, thinking), mapper)
    }

    override fun reviewImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): ImageReview =
        CloudLlmResponseParser.parseImageReview(
            postChat(listOf("system" to systemPrompt, "user" to prompt), DEFAULT_MODEL),
            mapper,
        )

    override fun translateImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): String =
        CloudLlmResponseParser
            .parse(
                postChat(listOf("system" to systemPrompt, "user" to prompt), DEFAULT_MODEL),
                mapper,
            ).text

    /** chat/completions 한 번 호출 → 원시 응답 body. 연결/HTTP 오류는 일반화 [CloudLlmException]. */
    private fun postChat(
        messages: List<Pair<String, String>>,
        model: String,
        thinking: CloudThinking? = null,
    ): String {
        if (!isEnabled()) throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")
        val base = baseUrl.trimEnd('/')
        val payload =
            mapper
                .createObjectNode()
                .put("model", model.ifBlank { DEFAULT_MODEL })
                .apply {
                    val arr = putArray("messages")
                    messages.forEach { (role, content) -> arr.addObject().put("role", role).put("content", content) }
                    // z.ai GLM thinking 속도 라우팅: {"thinking":{"type":"enabled"|"disabled"}}. null 이면 미전송(서버 기본).
                    thinking?.let { putObject("thinking").put("type", it.wire) }
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
        return resp.body()
    }

    companion object {
        const val DEFAULT_MODEL = "glm-5.1"
    }
}
