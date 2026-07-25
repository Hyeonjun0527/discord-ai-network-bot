package com.discordassistant.central.speech.adapter.outbound.routing

import com.discordassistant.central.routing.application.ChannelTokenBudgetExceededException
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmCachePolicy
import com.discordassistant.central.routing.application.CloudLlmImageInput
import com.discordassistant.central.routing.application.CloudLlmPurpose
import com.discordassistant.central.routing.application.CloudLlmRequestOptions
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.NiaPromptTemplate
import com.discordassistant.central.speech.application.port.out.SpeechGenerationFailureReason
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * routing anti-corruption adapter(NEXA-P14-T002, adapter/outbound/routing).
 *
 * [SpeechGenerationPort] 를 routing 의 provider-neutral [CloudLlm] 포트로만 구현한다 — provider-agent glm 경로·
 * Z.AI SDK 타입·HTTP endpoint 를 speech 에 노출하지 않는다(ADR 0006·speech-context.md anti-corruption). 모델
 * 라벨은 [SpeechModelConfig](환경 외부화, T003)에서, 응답 파싱은 [SpeechCandidateResponseParser](T012)에서,
 * timeout·stale 가드는 [CloudCallBudget](T014)에서 가져오고 실패 재시도는 이 어댑터에서 한 번으로 제한한다.
 *
 * **acceptance(T002) — provider-agent GLM 경로를 호출하지 않고 requestlog/quota 연동을 유지한다**: 외부 호출은
 * 오직 [CloudLlm] 포트(central 이 OpenAI를 직접 호출하며 provider-agent를 우회)를 거치고,
 * token 사용량은 [com.discordassistant.central.speech.application.port.out.SpeechUsageRecorderPort] 로 central
 * 관측에 남긴다.
 *
 * **fail-safe**: 비활성·호출/파싱 실패·stale·timeout 은 모두 [SpeechGenerationResult.EMPTY] 로 흡수한다(예외를
 * 던지지 않는다) — fallback 정책(T016)이 빈 결과를 무발화/리액션으로 안전 하강한다.
 */
@Component
class RoutingCloudSpeechGenerationAdapter(
    private val cloudLlm: CloudLlm,
    private val modelConfig: SpeechModelConfig,
    private val usageRecorder: com.discordassistant.central.speech.application.port.out.SpeechUsageRecorderPort =
        com.discordassistant.central.speech.application.port.out.SpeechUsageRecorderPort.Noop,
    private val clock: Clock = Clock.systemUTC(),
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
) : SpeechGenerationPort {
    private val log = LoggerFactory.getLogger(RoutingCloudSpeechGenerationAdapter::class.java)
    private val mapper = ObjectMapper()

    override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult {
        if (!cloudLlm.isEnabled()) return SpeechGenerationResult.EMPTY
        val budget =
            CloudCallBudget.until(
                now = clock.instant(),
                deadline = clock.instant().plus(modelConfig.perCallTimeout.multipliedBy(2)),
                defaultTimeout = modelConfig.perCallTimeout,
            )
        return generateWithin(request, budget)
    }

    /** [budget] 안에서 최초 호출과 실패 시 한 번의 재시도만 허용한다. */
    fun generateWithin(
        request: SpeechGenerationRequest,
        budget: CloudCallBudget,
    ): SpeechGenerationResult {
        if (!cloudLlm.isEnabled()) return SpeechGenerationResult.EMPTY
        val prompt = combinePrompt(request)
        repeat(MAX_ATTEMPTS) { zeroBasedAttempt ->
            val startedAt = clock.instant()
            if (budget.isStale(startedAt)) return SpeechGenerationResult.EMPTY
            val timeout = minOf(budget.perCallTimeout, Duration.between(startedAt, budget.deadline))
            if (timeout.isZero || timeout.isNegative) return SpeechGenerationResult.EMPTY
            val result =
                try {
                    generateBounded(request, prompt, timeout)
                } catch (_: ChannelTokenBudgetExceededException) {
                    return SpeechGenerationResult.failed(SpeechGenerationFailureReason.CHANNEL_TOKEN_BUDGET_EXHAUSTED)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn("발화 생성 호출 중단: {}", e.javaClass.simpleName)
                    return SpeechGenerationResult.EMPTY
                } catch (e: Exception) {
                    log.warn(
                        "발화 생성 호출 실패({}/{}): {}",
                        zeroBasedAttempt + 1,
                        MAX_ATTEMPTS,
                        e.javaClass.simpleName,
                    )
                    return@repeat
                }
            if (budget.isStale(clock.instant())) return SpeechGenerationResult.EMPTY
            val candidates =
                SpeechCandidateResponseParser.parse(
                    content = result.text,
                    mapper = mapper,
                    candidateIdPrefix = "cand-${UUID.randomUUID()}",
                )
            if (candidates.isEmpty()) return@repeat
            recordUsage(request, result.usage.promptTokens, result.usage.completionTokens)
            return SpeechGenerationResult(
                candidates = candidates.take(request.candidateCount),
                modelMetadata = modelConfig.model,
            )
        }
        return SpeechGenerationResult.EMPTY
    }

    /**
     * [CloudLlm.generate] 를 [timeout] 안에서만 기다린다 — 업스트림이 매달려도 파이프라인 스레드를 최대 [timeout]
     * 까지만 점유하도록 벽시계 상한을 **실효화**한다(T014 perCallTimeout). [CloudLlm] 에 timeout 오버로드가 없어
     * bounded executor + [Future.get] 로 감싼다. 초과 시 호출을 취소하고 [TimeoutException] 을 던져 상위
     * 1회 재시도 또는 EMPTY fail-safe 로 흡수시킨다(호출 실패와 동일 취급).
     */
    private fun generateBounded(
        generationRequest: SpeechGenerationRequest,
        prompt: CombinedPrompt,
        timeout: Duration,
    ): CloudLlmResult {
        val cachePolicy =
            if (prompt.stablePrefixChars > 0) {
                CloudLlmCachePolicy.stablePrefix(
                    namespace = SPEECH_CACHE_NAMESPACE,
                    prompt = prompt.text,
                    stablePrefixChars = prompt.stablePrefixChars,
                )
            } else {
                CloudLlmCachePolicy.disabled()
            }
        val future: Future<CloudLlmResult> =
            CALL_EXECUTOR.submit(
                Callable {
                    val options =
                        CloudLlmRequestOptions(
                            purpose = CloudLlmPurpose.NIA_SPEECH,
                            maxOutputTokens = generationRequest.maxOutputTokens,
                            cachePolicy = cachePolicy,
                            requestTimeout = upstreamTimeout(timeout),
                            maxRetries = 0,
                            channelTokenBudgetKey = generationRequest.channelTokenBudgetKey,
                        )
                    generationRequest.speechImageInput?.let { image ->
                        cloudLlm.generateSampledWithImage(
                            prompt = prompt.text,
                            image =
                                CloudLlmImageInput(
                                    mimeType = image.mediaType.mimeType,
                                    base64Data = image.base64Data,
                                    width = image.width,
                                    height = image.height,
                                    estimatedInputTokens = image.estimatedInputTokens,
                                ),
                            model = modelConfig.model,
                            temperature = modelConfig.temperature,
                            options = options,
                        )
                    } ?: cloudLlm.generateSampled(
                        prompt.text,
                        modelConfig.model,
                        modelConfig.temperature,
                        options,
                    )
                },
            )
        return try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true) // 매달린 호출을 끊어 스레드 누수를 줄인다(HTTP 는 인터럽트에 즉시 반응 안 할 수 있음).
            throw e
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is ChannelTokenBudgetExceededException) throw cause
            throw e
        }
    }

    private fun recordUsage(
        request: SpeechGenerationRequest,
        promptTokens: Int,
        completionTokens: Int,
    ) {
        try {
            // guildId 는 요청 계약에 직접 없으므로 관측은 token·model 만 남긴다(provider-neutral). 길드 연동은
            // 상위 유스케이스가 별도 컨텍스트로 호출 시 확장한다 — 여기서는 0 길드 키로 집계(관측 실패는 흡수).
            usageRecorder.recordSpeechGeneration(0L, promptTokens, completionTokens, modelConfig.model)
        } catch (e: Exception) {
            log.debug("발화 생성 사용량 기록 실패(무시): {}", e.javaClass.simpleName)
        }
    }

    private fun upstreamTimeout(totalTimeout: Duration): Duration =
        Duration.ofMillis((totalTimeout.toMillis() - OUTER_TIMEOUT_MARGIN_MILLIS).coerceAtLeast(1L))

    /** system + user 를 단일 prompt 로 합친다(CloudLlm.generate 는 단일 prompt·model 계약). */
    private fun combinePrompt(request: SpeechGenerationRequest): CombinedPrompt {
        val template = promptSource.text(NiaPromptKey.SPEECH_COMBINE_TEMPLATE)
        val values =
            mapOf(
                "systemPrompt" to request.systemPrompt,
                "toneDirective" to request.toneDirective,
                "userPrompt" to request.userPrompt,
            )
        return CombinedPrompt(
            text = NiaPromptTemplate.render(template, values),
            stablePrefixChars =
                NiaPromptTemplate.stablePrefixChars(
                    template = template,
                    values = values,
                    stableValueChars = mapOf("systemPrompt" to request.stableSystemPromptChars),
                ),
        )
    }

    companion object {
        /** perCallTimeout 을 강제하는 bounded daemon 풀(전 인스턴스 공유). 매달린 호출로부터 벽시계 상한을 지킨다. */
        private const val MAX_ATTEMPTS: Int = 2
        private const val CALL_THREADS: Int = 8
        private const val SPEECH_CACHE_NAMESPACE: String = "nia-speech-v1"
        private const val OUTER_TIMEOUT_MARGIN_MILLIS: Long = 250L
        private val CALL_EXECUTOR: ExecutorService =
            Executors.newFixedThreadPool(
                CALL_THREADS,
                ThreadFactory { r -> Thread(r, "speech-cloud-call").also { it.isDaemon = true } },
            )
    }

    private data class CombinedPrompt(
        val text: String,
        val stablePrefixChars: Int,
    )
}
