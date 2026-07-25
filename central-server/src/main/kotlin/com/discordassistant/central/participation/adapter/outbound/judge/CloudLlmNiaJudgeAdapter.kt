package com.discordassistant.central.participation.adapter.outbound.judge

import com.discordassistant.central.participation.application.judge.NiaJudgePromptAssembler
import com.discordassistant.central.participation.application.judge.NiaParticipationJudge
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmPort
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmResponse
import com.discordassistant.central.participation.application.port.out.NiaJudgeOutputContract
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmCachePolicy
import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmJsonSchema
import com.discordassistant.central.routing.application.CloudLlmPurpose
import com.discordassistant.central.routing.application.CloudLlmRequestOptions
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.CloudThinking
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@Component
class CloudLlmNiaJudgeAdapter(
    private val cloudLlm: CloudLlm,
    @param:Value("\${central.nexa.participation.judge.model:gpt-5.6-luna}")
    private val model: String = DEFAULT_MODEL,
    @param:Value("\${central.nexa.participation.judge.structured-output-enabled:false}")
    private val structuredOutputEnabled: Boolean = false,
) : NiaJudgeLlmPort {
    private val callExecutor = newCallExecutor()

    override fun complete(request: NiaJudgeLlmRequest): NiaJudgeLlmResponse {
        if (!cloudLlm.isEnabled()) {
            throw CloudLlmException("니아 판단용 클라우드 LLM이 비활성 상태입니다.")
        }

        val startedAtNanos = System.nanoTime()
        val result = completeWithin(request)
        val latencyMillis =
            TimeUnit.NANOSECONDS.toMillis(
                (System.nanoTime() - startedAtNanos).coerceAtLeast(0L),
            )
        return NiaJudgeLlmResponse(
            content = result.text,
            modelVersion = model,
            finishReason = FINISH_REASON_COMPLETED,
            promptTokens = result.usage.promptTokens.takeIf { it > 0 },
            completionTokens = result.usage.completionTokens.takeIf { it > 0 },
            latencyMillis = latencyMillis,
        )
    }

    private fun completeWithin(request: NiaJudgeLlmRequest): CloudLlmResult {
        val future: Future<CloudLlmResult> =
            try {
                callExecutor.submit(
                    Callable {
                        cloudLlm.generate(
                            prompt = request.prompt,
                            model = model,
                            history = emptyList(),
                            thinking = CloudThinking.DISABLED,
                            options =
                                CloudLlmRequestOptions(
                                    purpose =
                                        when {
                                            request.metadata[NiaJudgePromptAssembler.EXECUTION_PURPOSE_METADATA_KEY] == "shadow" &&
                                                request.metadata[NiaParticipationJudge.REPAIR_ATTEMPT_METADATA_KEY] == "true" ->
                                                CloudLlmPurpose.NIA_SHADOW_JUDGE_REPAIR
                                            request.metadata[NiaJudgePromptAssembler.EXECUTION_PURPOSE_METADATA_KEY] == "shadow" ->
                                                CloudLlmPurpose.NIA_SHADOW_JUDGE
                                            request.metadata[NiaParticipationJudge.REPAIR_ATTEMPT_METADATA_KEY] == "true" ->
                                                CloudLlmPurpose.NIA_JUDGE_REPAIR
                                            else -> CloudLlmPurpose.NIA_JUDGE
                                        },
                                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                                    requestTimeout = upstreamTimeout(request.timeoutMillis),
                                    maxRetries = 0,
                                    jsonSchema =
                                        if (structuredOutputEnabled) {
                                            CloudLlmJsonSchema(
                                                name = NiaJudgeOutputContract.FORMAT_NAME,
                                                schemaJson = NiaJudgeOutputContract.JSON_SCHEMA,
                                            )
                                        } else {
                                            null
                                        },
                                    cachePolicy =
                                        if (request.stablePromptPrefixChars > 0) {
                                            CloudLlmCachePolicy.stablePrefix(
                                                namespace = "nia-judge:${request.promptVersion}",
                                                prompt = request.prompt,
                                                stablePrefixChars = request.stablePromptPrefixChars,
                                            )
                                        } else {
                                            CloudLlmCachePolicy.disabled()
                                        },
                                ),
                        )
                    },
                )
            } catch (e: RejectedExecutionException) {
                throw CloudLlmException("니아 판단용 클라우드 LLM 호출 풀이 포화 상태입니다.", e)
            }
        return try {
            future.get(request.timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw CloudLlmException("니아 판단용 클라우드 LLM 호출 시간이 초과되었습니다.", e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw CloudLlmException("니아 판단용 클라우드 LLM 호출이 중단되었습니다.", e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is CloudLlmException) throw cause
            throw CloudLlmException("니아 판단용 클라우드 LLM 호출에 실패했습니다.", cause)
        }
    }

    private fun upstreamTimeout(totalTimeoutMillis: Long): Duration =
        Duration.ofMillis((totalTimeoutMillis - OUTER_TIMEOUT_MARGIN_MILLIS).coerceAtLeast(1L))

    @PreDestroy
    fun close() {
        callExecutor.shutdownNow()
    }

    companion object {
        const val DEFAULT_MODEL: String = "gpt-5.6-luna"
        const val FINISH_REASON_COMPLETED: String = "completed"
        private const val CALL_THREADS: Int = 8
        private const val CALL_QUEUE_CAPACITY: Int = 16
        private const val OUTER_TIMEOUT_MARGIN_MILLIS: Long = 250L
        private const val MAX_OUTPUT_TOKENS: Int = 2_048
        private val THREAD_SEQUENCE = AtomicInteger()

        private fun newCallExecutor(): ThreadPoolExecutor =
            ThreadPoolExecutor(
                CALL_THREADS,
                CALL_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(CALL_QUEUE_CAPACITY),
                ThreadFactory { runnable ->
                    Thread(runnable, "nia-judge-cloud-call-${THREAD_SEQUENCE.incrementAndGet()}").also { it.isDaemon = true }
                },
                ThreadPoolExecutor.AbortPolicy(),
            )
    }
}
