package com.discordassistant.central.speech.adapter.outbound.routing

import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmResult
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
 * timeout·retry·stale 가드는 [CloudCallBudget](T014)에서 가져온다.
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
                maxRetries = modelConfig.maxRetries,
            )
        return generateWithin(request, budget)
    }

    /**
     * [budget] 안에서 호출한다(외부 deadline·retry 가 주어지면 사용 — actionruntime 연동 시). 같은 fail-safe 규칙.
     * 응답 직후 [CloudCallBudget.isStale] 로 늦게 도착한 응답을 폐기한다(acceptance T014).
     */
    fun generateWithin(
        request: SpeechGenerationRequest,
        budget: CloudCallBudget,
    ): SpeechGenerationResult {
        if (!cloudLlm.isEnabled()) return SpeechGenerationResult.EMPTY
        val prompt = combinePrompt(request)
        var attempt = 0
        while (attempt < budget.maxAttempts) {
            attempt++
            if (budget.isStale(clock.instant())) return SpeechGenerationResult.EMPTY // 호출 전 deadline 초과.
            val result =
                try {
                    generateBounded(prompt, budget.perCallTimeout)
                } catch (e: Exception) {
                    // 상세는 로그로만 — 사용자/도메인에 업스트림 노출 금지(예외 원칙). 재시도 가능하면 계속.
                    log.warn("발화 생성 호출 실패(attempt {}/{}): {}", attempt, budget.maxAttempts, e.javaClass.simpleName)
                    continue
                }
            if (budget.isStale(clock.instant())) return SpeechGenerationResult.EMPTY // 응답이 늦게 도착(stale).
            val candidates =
                SpeechCandidateResponseParser.parse(
                    content = result.text,
                    mapper = mapper,
                    candidateIdPrefix = "cand-${UUID.randomUUID()}",
                )
            if (candidates.isEmpty()) continue // malformed/빈 응답 — 재시도 여지 있으면 다시.
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
     * 재시도/EMPTY fail-safe 로 흡수시킨다(호출 실패와 동일 취급).
     */
    private fun generateBounded(
        prompt: String,
        timeout: Duration,
    ): CloudLlmResult {
        val future: Future<CloudLlmResult> =
            CALL_EXECUTOR.submit(Callable { cloudLlm.generateSampled(prompt, modelConfig.model, modelConfig.temperature) })
        return try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true) // 매달린 호출을 끊어 스레드 누수를 줄인다(HTTP 는 인터럽트에 즉시 반응 안 할 수 있음).
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

    /** system + user 를 단일 prompt 로 합친다(CloudLlm.generate 는 단일 prompt·model 계약). */
    private fun combinePrompt(request: SpeechGenerationRequest): String =
        buildString {
            appendLine(request.systemPrompt)
            // 감정 톤 힌트(D2) — 있을 때만 *약하게* 얹는다(기본 "" 면 평소 니아 그대로, 무영향).
            if (request.toneDirective.isNotBlank()) {
                appendLine()
                appendLine(request.toneDirective)
            }
            appendLine()
            appendLine("[맥락]")
            append(request.userPrompt)
        }

    companion object {
        /** perCallTimeout 을 강제하는 bounded daemon 풀(전 인스턴스 공유). 매달린 호출로부터 벽시계 상한을 지킨다. */
        private const val CALL_THREADS: Int = 8
        private val CALL_EXECUTOR: ExecutorService =
            Executors.newFixedThreadPool(
                CALL_THREADS,
                ThreadFactory { r -> Thread(r, "speech-cloud-call").also { it.isDaemon = true } },
            )
    }
}
