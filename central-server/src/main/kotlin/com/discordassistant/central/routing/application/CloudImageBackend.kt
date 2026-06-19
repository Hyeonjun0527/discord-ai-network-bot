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
import java.util.Base64

/** 클라우드 이미지 백엔드 호출/응답 오류(provider-agent ImageBackendError 의 central 대응). */
class CloudImageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 클라우드 SD(Stability/RunPod) 이미지 픽셀을 **중앙 서버가 관리자 키 1개로 직접** 생성하는 백엔드 포트
 * (ADR 0006 단계4 — 완전 앱리스 이미지). 유저가 앱(provider-agent)을 설치하지 않아도, 또 에이전트 이미지
 * 프로바이더 풀이 비어 있어도 central 이 직접 픽셀까지 생성한다(CloudLlm 의 텍스트판 자매).
 *
 * 안전: 픽셀 생성 전에 [CloudLlm] 심사를 반드시 통과해야 한다(fail-closed) — 이 포트는 픽셀 생성만 담당하고
 * 심사는 호출부(AskCommandHandler.imagine)가 CloudLlm 으로 선행한다. 키 미설정이면 [isEnabled]=false →
 * 라우팅이 기존 에이전트 경로(ComfyUI/에이전트 클라우드SD)로 폴백한다.
 */
interface CloudImageBackend {
    fun isEnabled(): Boolean

    /** 정제된 영어 프롬프트로 PNG 바이트를 생성한다(이미 디코드됨). 실패 시 [CloudImageException]. */
    fun txt2img(
        prompt: String,
        width: Int,
        height: Int,
        negativePrompt: String,
    ): ByteArray

    /** 이 백엔드가 생성하는 기본 해상도(폭, 높이). */
    fun defaultResolution(): Pair<Int, Int>
}

/**
 * 구체 클라우드 SD 구현(Stability/RunPod)이 따르는 내부 소스 계약. [CloudImageBackend] 와 메서드는 같지만
 * **별도 타입**이라 셀렉터가 위임 대상으로만 들고 가고, 주입 지점([AskCommandHandler])엔 노출되지 않는다
 * (port 빈은 셀렉터 하나뿐 — 테스트가 fake 로 깔끔히 대체 가능). 두 구현은 항상 키 유무로 isEnabled 를 판단한다.
 */
interface CloudImageSource {
    fun isEnabled(): Boolean

    fun txt2img(
        prompt: String,
        width: Int,
        height: Int,
        negativePrompt: String,
    ): ByteArray

    fun defaultResolution(): Pair<Int, Int>
}

/** 기본(비활성) — 클라우드 이미지 키 미설정 시. 호출되면 예외(라우팅은 isEnabled() 로 먼저 분기). */
object NoCloudImageBackend : CloudImageBackend {
    override fun isEnabled() = false

    override fun txt2img(
        prompt: String,
        width: Int,
        height: Int,
        negativePrompt: String,
    ): ByteArray = throw CloudImageException("클라우드 이미지 백엔드가 비활성 상태입니다.")

    override fun defaultResolution(): Pair<Int, Int> = 1024 to 1024
}

/**
 * 진입점 셀렉터 — 유일한 [CloudImageBackend] 빈(주입 지점이 이 한 타입만 본다). Stability 키가 있으면
 * Stability 를, 없고 RunPod 키+엔드포인트가 있으면 RunPod 를, 둘 다 없으면 비활성으로 위임한다.
 * CloudLlm 과 같은 '관리자 키 1개' 모델 — central 이 픽셀까지 직접 만든다.
 */
@Component
class CloudImageBackendSelector(
    private val stability: StabilityImageBackend,
    private val runpod: RunPodImageBackend,
) : CloudImageBackend {
    private fun active(): CloudImageSource? =
        when {
            stability.isEnabled() -> stability
            runpod.isEnabled() -> runpod
            else -> null
        }

    override fun isEnabled() = active() != null

    override fun txt2img(
        prompt: String,
        width: Int,
        height: Int,
        negativePrompt: String,
    ): ByteArray =
        (active() ?: throw CloudImageException("클라우드 이미지 백엔드가 비활성 상태입니다."))
            .txt2img(prompt, width, height, negativePrompt)

    override fun defaultResolution(): Pair<Int, Int> = active()?.defaultResolution() ?: NoCloudImageBackend.defaultResolution()
}

/**
 * Stability AI 직접 호출(provider-agent stability.py 포팅). `central.cloud.stability-api-key` 미설정이면
 * 비활성. 키가 있으면 `POST /v2beta/stable-image/generate/{path}` 에 multipart form 으로 요청하고 바이너리
 * PNG 를 그대로 ByteArray 로 받는다(base64 불필요 — central 이 Discord 첨부로 바로 들고 간다).
 * Stability 는 폭/높이 직접 지정이 아니라 aspect_ratio 로 크기를 잡으므로 정사각(1024≈1MP) 기본을 쓴다.
 */
@Component
class StabilityImageBackend(
    @param:Value("\${central.cloud.stability-api-key:}") private val apiKey: String,
    @param:Value("\${central.cloud.stability-model:core}") private val model: String,
    @param:Value("\${central.cloud.timeout-seconds:120}") private val timeoutSeconds: Long,
    @param:Value("\${central.cloud.stability-base-url:https://api.stability.ai}") private val baseUrl: String,
) : CloudImageSource {
    private val log = LoggerFactory.getLogger(StabilityImageBackend::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun isEnabled() = apiKey.isNotBlank()

    override fun defaultResolution(): Pair<Int, Int> = 1024 to 1024

    override fun txt2img(
        prompt: String,
        width: Int,
        height: Int,
        negativePrompt: String,
    ): ByteArray {
        if (!isEnabled()) throw CloudImageException("Stability 백엔드가 비활성 상태입니다.")
        val m = model.trim()
        val path = MODEL_PATHS[m] ?: "core"
        val boundary = "----central-stability-${java.util.UUID.randomUUID()}"
        val req =
            HttpRequest
                .newBuilder(URI.create("${baseUrl.trimEnd('/')}/v2beta/stable-image/generate/$path"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "image/*")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, buildFields(prompt, negativePrompt, m))))
                .build()
        val resp =
            try {
                http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: Exception) {
                log.warn("Stability 연결 실패: {}", e.javaClass.simpleName)
                throw CloudImageException(USER_ERROR_MESSAGE, e)
            }
        return handleResponse(
            resp.statusCode(),
            resp.headers().firstValue("content-type").orElse(""),
            resp.body(),
            log,
        )
    }

    companion object {
        /** 모델 라벨 → /generate/<path>. core=빠르고 저렴, ultra=고품질, sd3=SD3.5. */
        val MODEL_PATHS = mapOf("core" to "core", "ultra" to "ultra", "sd3" to "sd3")

        const val USER_ERROR_MESSAGE = "클라우드 이미지 생성 일시 오류"

        /** Stability multipart form 필드(stability.py FormData 포팅). negative 가 비면 생략, sd3 는 model 명시. */
        fun buildFields(
            prompt: String,
            negativePrompt: String,
            model: String,
        ): List<Pair<String, String>> {
            val fields = mutableListOf("prompt" to prompt)
            if (negativePrompt.isNotBlank()) fields.add("negative_prompt" to negativePrompt)
            fields.add("aspect_ratio" to "1:1")
            fields.add("output_format" to "png")
            if (model == "sd3") fields.add("model" to "sd3.5-large")
            return fields
        }

        /** 응답 분기(순수): 200 + image content-type 면 PNG 바이트, 아니면 일반화 예외(상세는 로그로만). */
        fun handleResponse(
            statusCode: Int,
            contentType: String,
            body: ByteArray,
            log: org.slf4j.Logger,
        ): ByteArray {
            if (statusCode in 200..299 && contentType.startsWith("image/")) return body
            // 비-200 또는 JSON 오류 본문(content moderation·잔액 부족·키 오류 등) — 상세는 로그로만.
            log.warn("Stability HTTP {}: {}", statusCode, String(body).take(500))
            throw CloudImageException(USER_ERROR_MESSAGE)
        }

        /** RFC 7578 multipart/form-data 본문을 직접 구성한다(텍스트 필드만 — Stability 는 prompt 등 form 필드). */
        fun multipartBody(
            boundary: String,
            fields: List<Pair<String, String>>,
        ): ByteArray {
            val sb = StringBuilder()
            for ((name, value) in fields) {
                sb.append("--").append(boundary).append("\r\n")
                sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                sb.append(value).append("\r\n")
            }
            sb.append("--").append(boundary).append("--\r\n")
            return sb.toString().toByteArray(Charsets.UTF_8)
        }
    }
}

/**
 * RunPod Serverless 직접 호출(provider-agent runpod.py 포팅). `central.cloud.runpod-api-key` +
 * `runpod-endpoint-id` 가 있으면 활성. `POST {base}/{endpoint}/runsync` 로 동기 호출하되, 워커가
 * 콜드스타트/장시간이면 RunPod 가 IN_PROGRESS 로 즉시 반환하므로 `/status/{id}` 를 폴링해 완료를 기다린다.
 * 워커 출력 `{"image_base64": "..."}` 를 디코드해 ByteArray 로 반환한다(계약은 runpod-worker 핸들러와 동일).
 */
@Component
class RunPodImageBackend(
    @param:Value("\${central.cloud.runpod-api-key:}") private val apiKey: String,
    @param:Value("\${central.cloud.runpod-endpoint-id:}") private val endpointId: String,
    @param:Value("\${central.cloud.runpod-timeout-seconds:300}") private val timeoutSeconds: Long,
    @param:Value("\${central.cloud.runpod-base-url:https://api.runpod.ai/v2}") private val baseUrl: String,
) : CloudImageSource {
    private val log = LoggerFactory.getLogger(RunPodImageBackend::class.java)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val base = baseUrl.trimEnd('/')

    override fun isEnabled() = apiKey.isNotBlank() && endpointId.isNotBlank()

    override fun defaultResolution(): Pair<Int, Int> = 1024 to 1024

    override fun txt2img(
        prompt: String,
        width: Int,
        height: Int,
        negativePrompt: String,
    ): ByteArray {
        if (!isEnabled()) throw CloudImageException("RunPod 백엔드가 비활성 상태입니다.")
        val endpoint = endpointId.trim()
        val payload = RunPodResponseParser.buildPayload(mapper, prompt, negativePrompt, width, height)
        val data = postJson("$base/$endpoint/runsync", mapper.writeValueAsString(payload), "runsync")
        // 완료면 즉시 이미지, IN_QUEUE/IN_PROGRESS 면 /status 폴링(전체 타임아웃 안에서). 폴링 fetch 만 HTTP 에 위임.
        return RunPodResponseParser.resolve(data, timeoutSeconds, POLL_INTERVAL_MS) { jobId ->
            Thread.sleep(POLL_INTERVAL_MS)
            sendJson(
                HttpRequest
                    .newBuilder(URI.create("$base/$endpoint/status/$jobId"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer $apiKey")
                    .GET()
                    .build(),
                "status",
            )
        }
    }

    private fun postJson(
        url: String,
        body: String,
        what: String,
    ): com.fasterxml.jackson.databind.JsonNode =
        sendJson(
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            what,
        )

    private fun sendJson(
        req: HttpRequest,
        what: String,
    ): com.fasterxml.jackson.databind.JsonNode {
        val resp =
            try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                log.warn("RunPod {} 연결 실패: {}", what, e.javaClass.simpleName)
                throw CloudImageException(RunPodResponseParser.USER_ERROR_MESSAGE, e)
            }
        return RunPodResponseParser.parseEnvelope(resp.body(), resp.statusCode(), what, mapper, log)
    }

    companion object {
        const val POLL_INTERVAL_MS = 2000L
    }
}

/**
 * RunPod 응답의 순수 파서(테스트 가능, 외부 호출 불필요). 봉투(HTTP status·error)와 워커 출력
 * (`{"image_base64": ...}` 외 호환 형태)을 분리해 검증한다 — runpod.py `_json_or_error`/`_parse_output` 포팅.
 */
object RunPodResponseParser {
    /** 사용자(디스코드)에게 노출되는 일반화 메시지. 업스트림 status·body 상세는 로그로만. */
    const val USER_ERROR_MESSAGE = "클라우드 이미지 생성 일시 오류"

    const val DEFAULT_STEPS = 30
    const val DEFAULT_CFG = 7.0

    /** runsync 입력 payload(runpod-worker diffusers 핸들러 계약). seed=0 고정(단발 생성). */
    fun buildPayload(
        mapper: ObjectMapper,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
    ): com.fasterxml.jackson.databind.node.ObjectNode =
        mapper.createObjectNode().apply {
            putObject("input")
                .put("prompt", prompt)
                .put("negative_prompt", negativePrompt)
                .put("width", width)
                .put("height", height)
                .put("num_inference_steps", DEFAULT_STEPS)
                .put("guidance_scale", DEFAULT_CFG)
                .put("seed", 0)
        }

    /**
     * runsync 응답 해석: 완료면 이미지 추출, IN_QUEUE/IN_PROGRESS 면 [fetchStatus] 로 /status 폴링
     * (전체 타임아웃 안에서). HTTP/대기는 [fetchStatus] 람다에 위임해 이 분기 로직은 순수하게 테스트한다.
     */
    fun resolve(
        runsync: com.fasterxml.jackson.databind.JsonNode,
        timeoutSeconds: Long,
        pollIntervalMs: Long,
        fetchStatus: (jobId: String) -> com.fasterxml.jackson.databind.JsonNode,
    ): ByteArray {
        val status =
            runsync
                .get("status")
                ?.asText()
                .orEmpty()
                .uppercase()
        if ((status == "COMPLETED" || status.isEmpty()) && runsync.get("output")?.isNull == false) {
            return parseOutput(runsync.get("output"))
        }
        if (status == "FAILED") throw CloudImageException(USER_ERROR_MESSAGE)
        val jobId =
            runsync.get("id")?.takeIf { it.isTextual }?.asText()
                ?: throw CloudImageException("RunPod 응답에 작업 id/출력이 없습니다.")
        val deadlineMs = timeoutSeconds * 1000
        var waitedMs = 0L
        while (waitedMs < deadlineMs) {
            waitedMs += pollIntervalMs
            val st = fetchStatus(jobId)
            when (
                st
                    .get("status")
                    ?.asText()
                    .orEmpty()
                    .uppercase()
            ) {
                "COMPLETED" -> return parseOutput(st.get("output"))
                "FAILED" -> throw CloudImageException(USER_ERROR_MESSAGE)
            }
        }
        throw CloudImageException("RunPod 생성 시간 초과")
    }

    fun parseEnvelope(
        body: String,
        statusCode: Int,
        what: String,
        mapper: ObjectMapper,
        log: org.slf4j.Logger,
    ): com.fasterxml.jackson.databind.JsonNode {
        val node =
            try {
                mapper.readTree(body)
            } catch (e: Exception) {
                throw CloudImageException("RunPod $what 응답 파싱 실패", e)
            }
        if (statusCode >= 400) {
            log.warn("RunPod {} HTTP {}: {}", what, statusCode, body.take(500))
            throw CloudImageException(USER_ERROR_MESSAGE)
        }
        return node
    }

    /**
     * 워커 출력에서 base64 PNG 를 추출해 디코드한 ByteArray 반환. 계약: `{"image_base64": "..."}`.
     * 호환을 위해 `{"image": b64}`·`{"images":[b64,...]}`·문자열(b64) 형태도 받는다. data URL 접두는 제거.
     */
    fun parseOutput(output: com.fasterxml.jackson.databind.JsonNode?): ByteArray {
        val candidate =
            when {
                output == null || output.isNull -> null
                output.isTextual -> output.asText()
                output.isObject ->
                    output.get("image_base64")?.takeIf { it.isTextual }?.asText()
                        ?: output.get("image")?.takeIf { it.isTextual }?.asText()
                        ?: output
                            .get("images")
                            ?.takeIf { it.isArray && it.size() > 0 }
                            ?.get(0)
                            ?.takeIf { it.isTextual }
                            ?.asText()
                output.isArray && output.size() > 0 -> output.get(0)?.takeIf { it.isTextual }?.asText()
                else -> null
            }
        if (candidate.isNullOrBlank()) throw CloudImageException("RunPod 출력에서 이미지를 찾지 못했습니다.")
        val b64 = if (candidate.startsWith("data:")) candidate.substringAfter(",", candidate) else candidate
        return try {
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw CloudImageException("RunPod 출력이 올바른 base64 가 아닙니다.", e)
        }
    }
}
