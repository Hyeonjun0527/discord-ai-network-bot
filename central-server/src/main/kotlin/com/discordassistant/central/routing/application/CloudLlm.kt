package com.discordassistant.central.routing.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 클라우드 LLM 토큰 사용량(있으면). 통계/로그용 — 없으면 0. */
data class CloudLlmUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedPromptTokens: Int = 0,
    val cacheWritePromptTokens: Int = 0,
)

/** 비용을 요청 종류별로 분리하기 위한 저카디널리티 라벨. 원문·guild/user 식별자는 절대 담지 않는다. */
enum class CloudLlmPurpose {
    GENERAL,
    ADMIN_ACTION_ROUTER,
    IMAGE_SAFETY,
    IMAGE_TRANSLATION,
    MEMORY_EXTRACTION,
    SOCIAL_APPRAISAL,
    LEGACY_PARTICIPATION_JUDGE,
    NIA_JUDGE,
    NIA_JUDGE_REPAIR,
    NIA_SHADOW_JUDGE_REPAIR,
    NIA_SPEECH,
    NIA_ACTION_EVALUATOR,
    NIA_SHADOW_JUDGE,
}

/**
 * OpenAI prompt cache 정책. 모든 호출은 explicit 모드를 쓰며, 고정 prefix를 알는 호출만 cache write를 허용한다.
 * [stablePrefixChars]가 0이면 cache write를 완전히 끄고, 양수면 그 prefix 하나만 재사용 대상으로 만든다.
 */
data class CloudLlmCachePolicy(
    val stablePrefixChars: Int,
    val key: String?,
) {
    init {
        require(stablePrefixChars >= 0) { "stablePrefixChars 는 음수일 수 없다: $stablePrefixChars" }
        require((stablePrefixChars == 0) == (key == null)) {
            "stable prefix와 cache key는 함께 설정해야 한다"
        }
        key?.let {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))) { "유효하지 않은 prompt cache key다" }
        }
    }

    companion object {
        fun disabled(): CloudLlmCachePolicy = CloudLlmCachePolicy(stablePrefixChars = 0, key = null)

        fun stablePrefix(
            namespace: String,
            prompt: String,
            stablePrefixChars: Int,
        ): CloudLlmCachePolicy {
            require(namespace.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}"))) { "유효하지 않은 cache namespace다" }
            require(stablePrefixChars in 1..prompt.length) {
                "stablePrefixChars 는 prompt 길이 안이어야 한다: $stablePrefixChars/${prompt.length}"
            }
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(prompt.take(stablePrefixChars).toByteArray(Charsets.UTF_8))
                    .take(8)
                    .joinToString("") { byte -> "%02x".format(byte) }
            return CloudLlmCachePolicy(stablePrefixChars, "$namespace:$digest")
        }
    }
}

data class CloudLlmRequestOptions(
    val purpose: CloudLlmPurpose = CloudLlmPurpose.GENERAL,
    val maxOutputTokens: Int? = null,
    val cachePolicy: CloudLlmCachePolicy = CloudLlmCachePolicy.disabled(),
    val requestTimeout: Duration? = null,
    val maxRetries: Int? = null,
) {
    init {
        maxOutputTokens?.let { require(it > 0) { "maxOutputTokens 는 양수여야 한다: $it" } }
        requestTimeout?.let { require(!it.isZero && !it.isNegative) { "requestTimeout 은 양수여야 한다: $it" } }
        maxRetries?.let { require(it >= 0) { "maxRetries 는 음수일 수 없다: $it" } }
    }
}

/** OpenAI 사용량 관측 포트. 관측 실패가 실제 답변을 막지 않도록 구현은 예외를 밖으로 내보내지 않는다. */
fun interface CloudLlmUsageObserver {
    /** 실제 provider HTTP 요청 직전에 호출한다. 실패·timeout도 요청 수에서 사라지지 않는다. */
    fun recordAttempt(
        model: String,
        purpose: CloudLlmPurpose,
    ) = Unit

    /** 원문 없이 직렬화된 payload 크기와 실제 적용된 cache 정책까지 함께 기록한다. */
    fun recordAttempt(
        model: String,
        purpose: CloudLlmPurpose,
        requestPayloadChars: Int,
        cacheWriteRequested: Boolean,
    ) = recordAttempt(model, purpose)

    fun record(
        model: String,
        purpose: CloudLlmPurpose,
        usage: CloudLlmUsage,
    )

    companion object {
        val NOOP = CloudLlmUsageObserver { _, _, _ -> }
    }
}

/** 클라우드 LLM 추론 1건 결과: 답변 텍스트 + 선택적 사용량. */
data class CloudLlmResult(
    val text: String,
    val usage: CloudLlmUsage = CloudLlmUsage(),
)

/** 멀티턴 대화 한 turn. [role] 은 OpenAI Responses API의 `user`/`assistant` 역할이다. */
data class CloudTurn(
    val role: String,
    val content: String,
)

/** 기존 호출자 호환용 추론 힌트. OpenAI 어댑터는 운영 정책에 따라 항상 reasoning effort `none`을 사용한다. */
enum class CloudThinking(
    val wire: String,
) {
    ENABLED("enabled"),
    DISABLED("disabled"),
}

/** 클라우드 모델이 돌려준 함수 호출 1건(AI 관리 비서용). */
data class CloudToolCall(
    val name: String,
    val argumentsJson: String,
    val id: String? = null,
)

/**
 * tool calling 응답: 모델이 도구를 호출했으면 [toolCalls]가 비어있지 않고, 아니면 [text]만 채워진 일반 답변.
 * 둘 다 비면 빈 응답이다(상위가 일반 처리). 순수 파서로 만들어 테스트 가능하게 한다.
 */
data class CloudToolResponse(
    val text: String? = null,
    val toolCalls: List<CloudToolCall> = emptyList(),
    val usage: CloudLlmUsage = CloudLlmUsage(),
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
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

/** 중앙 서버가 관리자 키로 직접 처리하는 클라우드 LLM 포트. */
interface CloudLlm {
    fun isEnabled(): Boolean

    fun generate(
        prompt: String,
        model: String,
    ): CloudLlmResult

    /**
     * 멀티턴 대화 기억과 추론 힌트를 지원하는 확장 호출. OpenAI 운영 어댑터는 [thinking] 값과 무관하게
     * reasoning effort `none`을 강제한다.
     */
    fun generate(
        prompt: String,
        model: String,
        history: List<CloudTurn>,
        thinking: CloudThinking?,
    ): CloudLlmResult = generate(prompt, model)

    /** cache·출력 상한·관측 목적을 함께 전달하는 확장 호출. 기존 구현은 옵션 없이 동일 프롬프트를 처리한다. */
    fun generate(
        prompt: String,
        model: String,
        history: List<CloudTurn>,
        thinking: CloudThinking?,
        options: CloudLlmRequestOptions,
    ): CloudLlmResult = generate(prompt, model, history, thinking)

    /**
     * 발화 생성 전용 호환 경로. reasoning effort `none` 모델에서는 지원하지 않는 샘플링 파라미터를 보내지 않는다.
     */
    fun generateSampled(
        prompt: String,
        model: String,
        temperature: Double,
    ): CloudLlmResult = generate(prompt, model)

    fun generateSampled(
        prompt: String,
        model: String,
        temperature: Double,
        options: CloudLlmRequestOptions,
    ): CloudLlmResult = generateSampled(prompt, model, temperature)

    /**
     * OpenAI 호환 tool calling 1회(AI 관리 비서). [systemPrompt]+[userPrompt] 로 대화를 만들고 [toolsJson]
     * (OpenAI function schema 배열의 JSON 문자열)을 `tools`+`tool_choice:"auto"` 로 보낸다. 모델이 도구를
     * 호출하면 [CloudToolResponse.toolCalls], 아니면 [CloudToolResponse.text] 가 채워진다. 실패 시 [CloudLlmException].
     */
    fun generateWithTools(
        systemPrompt: String,
        userPrompt: String,
        toolsJson: String,
        model: String,
    ): CloudToolResponse

    /** tool calling에도 일반 생성과 같은 출력 상한·cache·사용량 목적을 적용한다. */
    fun generateWithTools(
        systemPrompt: String,
        userPrompt: String,
        toolsJson: String,
        model: String,
        options: CloudLlmRequestOptions,
    ): CloudToolResponse = generateWithTools(systemPrompt, userPrompt, toolsJson, model)

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

    override fun generateWithTools(
        systemPrompt: String,
        userPrompt: String,
        toolsJson: String,
        model: String,
    ): CloudToolResponse = throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")

    override fun reviewImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): ImageReview = throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")

    override fun translateImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): String = throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")
}

/** OpenAI Responses API 응답을 파싱하는 순수 함수 모음. */
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
            // 업스트림 에러(모델 없음·인증·잔액 등)의 사유를 운영자가 바로 진단할 수 있게 노출한다
            // (이 봇은 서버 관리자가 운영하므로 "model not found" 같은 실제 사유가 보여야 한다).
            val reason = errorReasonOf(error)
            throw CloudLlmException(if (reason != null) "클라우드 AI 오류: $reason" else USER_ERROR_MESSAGE)
        }
        val content =
            outputTexts(root).joinToString("\n").trim().takeIf { it.isNotBlank() }
                ?: throw CloudLlmException("클라우드 AI 응답에 텍스트가 없습니다(안전 필터 차단 또는 빈 응답).")
        val usage = root.get("usage")?.takeIf { it.isObject }?.let(::usageOf) ?: CloudLlmUsage()
        return CloudLlmResult(content, usage)
    }

    /** HTTP 성공 body에 usage가 있으면 후속 응답 의미 파싱 성공 여부와 무관하게 과금량을 복원한다. */
    fun parseUsage(
        body: String,
        mapper: ObjectMapper,
    ): CloudLlmUsage? =
        runCatching { mapper.readTree(body) }
            .getOrNull()
            ?.get("usage")
            ?.takeIf { it.isObject }
            ?.let(::usageOf)

    /** OpenAI `error` 노드에서 code·message 를 한 줄로 요약(운영자 진단용 — 개행 제거·길이 캡). */
    fun errorReasonOf(errorNode: com.fasterxml.jackson.databind.JsonNode): String? {
        val code = errorNode.get("code")?.asText()?.takeIf { it.isNotBlank() }
        val message = errorNode.get("message")?.asText()?.takeIf { it.isNotBlank() }
        return listOfNotNull(code, message)
            .joinToString(" ")
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(180)
            .ifBlank { null }
    }

    /** 응답 body(JSON)에서 업스트림 에러 사유를 뽑는다(파싱 실패면 null). */
    fun upstreamErrorReason(
        body: String,
        mapper: ObjectMapper,
    ): String? = runCatching { mapper.readTree(body).get("error")?.let(::errorReasonOf) }.getOrNull()

    /** HTTP status → 운영자용 짧은 카테고리. */
    fun statusCategory(status: Int): String =
        when (status) {
            401, 403 -> "인증 오류·키 확인"
            402 -> "잔액 부족"
            404 -> "모델 없음·모델명 확인"
            429 -> "요청 한도 초과"
            in 500..599 -> "OpenAI 서버 오류"
            else -> "HTTP $status"
        }

    /** 사용자(디스코드)에게 노출되는 일반화 메시지(원인 추출 실패 시 폴백). */
    const val USER_ERROR_MESSAGE = "클라우드 AI 일시 오류"

    /**
     * Responses API의 `output[]`에서 `function_call`과 일반 `output_text`를 추출한다.
     * 도구 호출이 있으면 [CloudToolResponse.toolCalls], 없으면 일반 답변 [CloudToolResponse.text]. error/빈 응답은
     * [CloudLlmException]. content 가 비어도 tool_calls 가 있으면 정상(도구만 호출하는 케이스)으로 본다.
     */
    fun parseToolResponse(
        body: String,
        mapper: ObjectMapper,
    ): CloudToolResponse {
        val root =
            try {
                mapper.readTree(body)
            } catch (e: Exception) {
                throw CloudLlmException("클라우드 AI 응답 파싱 실패", e)
            }
        val error = root.get("error")
        if (error != null && !error.isNull) {
            throw CloudLlmException(USER_ERROR_MESSAGE)
        }
        val toolCalls =
            root
                .get("output")
                ?.takeIf { it.isArray }
                ?.mapNotNull { item ->
                    if (item.get("type")?.asText() != "function_call") return@mapNotNull null
                    val name =
                        item
                            .get("name")
                            ?.takeIf { it.isTextual }
                            ?.asText()
                            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val args =
                        item.get("arguments")?.let { if (it.isTextual) it.asText() else it.toString() }?.takeIf { it.isNotBlank() } ?: "{}"
                    val id =
                        item.get("call_id")?.takeIf { it.isTextual }?.asText()
                            ?: item.get("id")?.takeIf { it.isTextual }?.asText()
                    CloudToolCall(name = name, argumentsJson = args, id = id)
                }.orEmpty()
        val content = outputTexts(root).joinToString("\n").trim().takeIf { it.isNotBlank() }
        if (toolCalls.isEmpty() && content == null) {
            throw CloudLlmException("클라우드 AI 응답에 텍스트가 없습니다(안전 필터 차단 또는 빈 응답).")
        }
        return CloudToolResponse(
            text = content,
            toolCalls = toolCalls,
            usage = root.get("usage")?.takeIf { it.isObject }?.let(::usageOf) ?: CloudLlmUsage(),
        )
    }

    private fun usageOf(usageNode: com.fasterxml.jackson.databind.JsonNode): CloudLlmUsage {
        val inputDetails = usageNode.get("input_tokens_details")
        return CloudLlmUsage(
            promptTokens = usageNode.get("input_tokens")?.asInt(0) ?: 0,
            completionTokens = usageNode.get("output_tokens")?.asInt(0) ?: 0,
            cachedPromptTokens = inputDetails?.get("cached_tokens")?.asInt(0) ?: 0,
            cacheWritePromptTokens = inputDetails?.get("cache_write_tokens")?.asInt(0) ?: 0,
        )
    }

    private fun outputTexts(root: com.fasterxml.jackson.databind.JsonNode): List<String> =
        root
            .get("output")
            ?.takeIf { it.isArray }
            ?.flatMap { item ->
                item
                    .get("content")
                    ?.takeIf { it.isArray }
                    ?.mapNotNull { content ->
                        content.get("text")?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
                    }.orEmpty()
            }.orEmpty()

    /**
     * 이미지 안전 심사 응답에서 모델이 돌려준 JSON 결과를 추출한다. 코드펜스가 섞여도 첫 JSON object만 허용하고,
     * `allowed`(boolean)가 없으면 fail-closed 예외 — 스키마가 조금이라도 깨지면 차단한다.
     */
    fun parseImageReview(
        body: String,
        mapper: ObjectMapper,
    ): ImageReview = parseImageReviewText(parse(body, mapper).text, mapper)

    /** Responses 봉투에서 이미 꺼낸 이미지 심사 JSON 텍스트를 검증한다. */
    fun parseImageReviewText(
        content: String,
        mapper: ObjectMapper,
    ): ImageReview {
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

    /** 코드펜스(```json … ```)를 벗기고 첫 `{ … }` object만 남긴다. */
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

/** OpenAI Responses API 구현. 모든 호출은 운영 정책에 따라 Luna와 reasoning effort `none`을 사용한다. */
@Component
class OpenAiCloudLlm(
    @param:Value("\${central.cloud.openai-api-key:}") private val apiKey: String,
    @param:Value("\${central.cloud.openai-base-url:https://api.openai.com/v1}") private val baseUrl: String,
    @param:Value("\${central.cloud.llm-timeout-seconds:20}") private val timeoutSeconds: Long,
    @param:Value("\${central.cloud.llm-max-retries:0}") private val maxRetries: Int = 0,
    @param:Value("\${central.cloud.prompt-cache-writes-enabled:false}")
    private val promptCacheWritesEnabled: Boolean = false,
    private val usageObserver: CloudLlmUsageObserver = CloudLlmUsageObserver.NOOP,
) : CloudLlm {
    private val log = LoggerFactory.getLogger(OpenAiCloudLlm::class.java)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val requestTimeout = Duration.ofSeconds(timeoutSeconds.coerceAtLeast(1))

    override fun isEnabled() = apiKey.isNotBlank()

    override fun generate(
        prompt: String,
        model: String,
    ): CloudLlmResult = generate(prompt, model, emptyList(), null, CloudLlmRequestOptions())

    override fun generateSampled(
        prompt: String,
        model: String,
        temperature: Double,
    ): CloudLlmResult = generate(prompt, model)

    override fun generateSampled(
        prompt: String,
        model: String,
        temperature: Double,
        options: CloudLlmRequestOptions,
    ): CloudLlmResult = generate(prompt, model, emptyList(), null, options)

    override fun generate(
        prompt: String,
        model: String,
        history: List<CloudTurn>,
        thinking: CloudThinking?,
    ): CloudLlmResult = generate(prompt, model, history, thinking, CloudLlmRequestOptions())

    override fun generate(
        prompt: String,
        model: String,
        history: List<CloudTurn>,
        thinking: CloudThinking?,
        options: CloudLlmRequestOptions,
    ): CloudLlmResult {
        val effectiveModel = model.ifBlank { DEFAULT_MODEL }
        return parse(
            postResponses(
                messages = history.map { it.role to it.content } + ("user" to prompt),
                model = effectiveModel,
                options = options,
            ),
        )
    }

    override fun generateWithTools(
        systemPrompt: String,
        userPrompt: String,
        toolsJson: String,
        model: String,
    ): CloudToolResponse = generateWithTools(systemPrompt, userPrompt, toolsJson, model, CloudLlmRequestOptions())

    override fun generateWithTools(
        systemPrompt: String,
        userPrompt: String,
        toolsJson: String,
        model: String,
        options: CloudLlmRequestOptions,
    ): CloudToolResponse {
        val effectiveModel = model.ifBlank { DEFAULT_MODEL }
        return CloudLlmResponseParser.parseToolResponse(
            postResponses(
                listOf("user" to userPrompt),
                effectiveModel,
                instructions = systemPrompt,
                toolsJson = toolsJson,
                options = options,
            ),
            mapper,
        )
    }

    override fun reviewImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): ImageReview {
        val options =
            CloudLlmRequestOptions(
                purpose = CloudLlmPurpose.IMAGE_SAFETY,
                maxOutputTokens = IMAGE_REVIEW_MAX_OUTPUT_TOKENS,
                maxRetries = 0,
            )
        val body = postResponses(listOf("user" to prompt), DEFAULT_MODEL, instructions = systemPrompt, options = options)
        val result = parse(body)
        return CloudLlmResponseParser.parseImageReviewText(result.text, mapper)
    }

    override fun translateImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): String {
        val options =
            CloudLlmRequestOptions(
                purpose = CloudLlmPurpose.IMAGE_TRANSLATION,
                maxOutputTokens = IMAGE_TRANSLATION_MAX_OUTPUT_TOKENS,
                maxRetries = 0,
            )
        val result = parse(postResponses(listOf("user" to prompt), DEFAULT_MODEL, instructions = systemPrompt, options = options))
        return result.text
    }

    private fun parse(body: String): CloudLlmResult = CloudLlmResponseParser.parse(body, mapper)

    private fun recordUsage(
        model: String,
        purpose: CloudLlmPurpose,
        usage: CloudLlmUsage,
    ) {
        runCatching { usageObserver.record(model, purpose, usage) }
            .onFailure { error -> log.debug("OpenAI 사용량 관측 실패(무시): {}", error.javaClass.simpleName) }
    }

    private fun recordAttempt(
        model: String,
        purpose: CloudLlmPurpose,
        requestPayloadChars: Int,
        cacheWriteRequested: Boolean,
    ) {
        runCatching {
            usageObserver.recordAttempt(
                model = model,
                purpose = purpose,
                requestPayloadChars = requestPayloadChars,
                cacheWriteRequested = cacheWriteRequested,
            )
        }.onFailure { error -> log.debug("OpenAI 요청 관측 실패(무시): {}", error.javaClass.simpleName) }
    }

    private fun postResponses(
        messages: List<Pair<String, String>>,
        model: String,
        instructions: String? = null,
        toolsJson: String? = null,
        options: CloudLlmRequestOptions = CloudLlmRequestOptions(),
    ): String {
        if (!isEnabled()) throw CloudLlmException("클라우드 LLM 이 비활성 상태입니다.")
        val startedAt = System.nanoTime()
        val effectiveCachePolicy =
            if (promptCacheWritesEnabled) options.cachePolicy else CloudLlmCachePolicy.disabled()
        val payload =
            mapper.createObjectNode().apply {
                put("model", model.ifBlank { DEFAULT_MODEL })
                put("store", false)
                putObject("reasoning").put("effort", REASONING_EFFORT)
                options.maxOutputTokens?.let { put("max_output_tokens", it) }
                effectiveCachePolicy.let { cache ->
                    putObject("prompt_cache_options").put("mode", "explicit")
                    cache.key?.let { put("prompt_cache_key", it) }
                }
                instructions?.takeIf { it.isNotBlank() }?.let { put("instructions", it) }
                val input = putArray("input")
                messages.forEachIndexed { index, (role, content) ->
                    val message = input.addObject().put("role", role)
                    val cache = effectiveCachePolicy.takeIf { index == messages.lastIndex && role == "user" }
                    if (cache != null && cache.stablePrefixChars > 0) {
                        require(cache.stablePrefixChars <= content.length) {
                            "stablePrefixChars 가 최종 user prompt 길이를 초과한다: ${cache.stablePrefixChars}/${content.length}"
                        }
                        val blocks = message.putArray("content")
                        blocks
                            .addObject()
                            .put("type", "input_text")
                            .put("text", content.take(cache.stablePrefixChars))
                            .putObject("prompt_cache_breakpoint")
                            .put("mode", "explicit")
                        content.drop(cache.stablePrefixChars).takeIf { it.isNotEmpty() }?.let { suffix ->
                            blocks.addObject().put("type", "input_text").put("text", suffix)
                        }
                    } else {
                        message.put("content", content)
                    }
                }
                if (!toolsJson.isNullOrBlank()) {
                    set<com.fasterxml.jackson.databind.JsonNode>("tools", toResponsesTools(toolsJson))
                    put("tool_choice", "auto")
                }
            }
        val requestBody = mapper.writeValueAsString(payload)
        val effectiveRequestTimeout = options.requestTimeout ?: requestTimeout
        val effectiveMaxAttempts = 1 + (options.maxRetries ?: maxRetries).coerceAtLeast(0)
        var lastTimeout: HttpTimeoutException? = null
        for (attempt in 1..effectiveMaxAttempts) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("${baseUrl.trimEnd('/')}/responses"))
                    .timeout(effectiveRequestTimeout)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
            val response =
                try {
                    recordAttempt(
                        model = model.ifBlank { DEFAULT_MODEL },
                        purpose = options.purpose,
                        requestPayloadChars = requestBody.length,
                        cacheWriteRequested = effectiveCachePolicy.stablePrefixChars > 0,
                    )
                    http.send(request, HttpResponse.BodyHandlers.ofString())
                } catch (e: HttpTimeoutException) {
                    lastTimeout = e
                    log.warn(
                        "OpenAI 호출 timeout model={} attempt={}/{} timeoutSeconds={} elapsedMs={}",
                        model.ifBlank { DEFAULT_MODEL },
                        attempt,
                        effectiveMaxAttempts,
                        effectiveRequestTimeout.seconds,
                        elapsedMs(startedAt),
                    )
                    if (attempt < effectiveMaxAttempts) continue
                    throw CloudLlmException(
                        "클라우드 AI 시간 초과(${effectiveRequestTimeout.seconds}초·${effectiveMaxAttempts}회)",
                        e,
                    )
                } catch (e: Exception) {
                    log.warn(
                        "OpenAI 호출 실패 model={} attempt={}/{} timeoutSeconds={} elapsedMs={} error={}",
                        model.ifBlank { DEFAULT_MODEL },
                        attempt,
                        effectiveMaxAttempts,
                        effectiveRequestTimeout.seconds,
                        elapsedMs(startedAt),
                        e.javaClass.simpleName,
                    )
                    throw CloudLlmException("클라우드 AI 연결 실패(${e.javaClass.simpleName})", e)
                }
            if (response.statusCode() !in 200..299) {
                log.warn(
                    "OpenAI HTTP {} model={} attempt={}/{} elapsedMs={}: {}",
                    response.statusCode(),
                    model.ifBlank { DEFAULT_MODEL },
                    attempt,
                    effectiveMaxAttempts,
                    elapsedMs(startedAt),
                    response.body().take(500),
                )
                val reason = CloudLlmResponseParser.upstreamErrorReason(response.body(), mapper)
                val category = CloudLlmResponseParser.statusCategory(response.statusCode())
                throw CloudLlmException("클라우드 AI 오류($category)" + (reason?.let { ": $it" } ?: ""))
            }
            val responseBody = response.body()
            CloudLlmResponseParser.parseUsage(responseBody, mapper)?.let { usage ->
                recordUsage(model.ifBlank { DEFAULT_MODEL }, options.purpose, usage)
            }
            return responseBody
        }
        throw CloudLlmException(CloudLlmResponseParser.USER_ERROR_MESSAGE, lastTimeout)
    }

    private fun toResponsesTools(toolsJson: String): com.fasterxml.jackson.databind.JsonNode {
        val source = mapper.readTree(toolsJson)
        require(source.isArray) { "toolsJson 은 배열이어야 한다" }
        return mapper.createArrayNode().apply {
            source.forEach { tool ->
                val function = tool.get("function")
                if (function == null) {
                    add(tool)
                } else {
                    addObject().apply {
                        put("type", "function")
                        put("name", function.path("name").asText())
                        function.get("description")?.let { set<com.fasterxml.jackson.databind.JsonNode>("description", it) }
                        function.get("parameters")?.let { set<com.fasterxml.jackson.databind.JsonNode>("parameters", it) }
                        put("strict", false)
                    }
                }
            }
        }
    }

    private fun elapsedMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    companion object {
        const val DEFAULT_MODEL = "gpt-5.6-luna"
        private const val IMAGE_REVIEW_MAX_OUTPUT_TOKENS = 256
        private const val IMAGE_TRANSLATION_MAX_OUTPUT_TOKENS = 2_048
        const val REASONING_EFFORT = "none"
    }
}
