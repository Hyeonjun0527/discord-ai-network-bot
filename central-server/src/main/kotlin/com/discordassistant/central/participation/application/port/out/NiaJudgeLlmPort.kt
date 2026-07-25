package com.discordassistant.central.participation.application.port.out

import java.security.MessageDigest

/**
 * NIA participation judge LLM outbound port. The application layer owns only the
 * prompt/output contract; provider selection, HTTP, SDK types, and routing live
 * behind an adapter.
 */
interface NiaJudgeLlmPort {
    fun complete(request: NiaJudgeLlmRequest): NiaJudgeLlmResponse
}

/** provider HTTP 요청 전에 채널 토큰 예산이 소진됐음을 judge 상위 흐름에 원인 보존해 알린다. */
class NiaJudgeTokenBudgetExceededException : RuntimeException("judge channel token budget exhausted")

data class NiaJudgeLlmRequest(
    val prompt: String,
    val promptVersion: String,
    val outputSchema: String = OUTPUT_SCHEMA,
    val seed: Long,
    val timeoutMillis: Long,
    val stablePromptPrefixChars: Int = 0,
    val metadata: Map<String, String> = emptyMap(),
    /** 원문 Discord ID가 아닌 채널 가명 키. provider 호출 직전 토큰 예산을 적용할 때만 채운다. */
    val channelTokenBudgetKey: String? = null,
) {
    init {
        require(prompt.isNotBlank()) { "judge prompt 는 비어 있을 수 없다" }
        require(promptVersion.isStableLabel()) { "judge promptVersion 은 안정 label 이어야 한다: $promptVersion" }
        require(outputSchema == OUTPUT_SCHEMA) { "지원하지 않는 judge output schema 다: $outputSchema" }
        require(seed >= 0) { "judge seed 는 음수일 수 없다: $seed" }
        require(timeoutMillis > 0) { "judge timeoutMillis 는 양수여야 한다: $timeoutMillis" }
        require(stablePromptPrefixChars in 0..prompt.length) {
            "judge stablePromptPrefixChars 는 prompt 길이 안이어야 한다: $stablePromptPrefixChars/${prompt.length}"
        }
        require(metadata.size <= MAX_METADATA) { "judge metadata 는 최대 $MAX_METADATA 개까지만 담는다" }
        metadata.forEach { (key, value) ->
            require(key.isStableLabel()) { "judge metadata key 는 안정 label 이어야 한다: $key" }
            require(value.length <= MAX_METADATA_VALUE_CHARS) { "judge metadata value 가 너무 길다: $key" }
        }
        channelTokenBudgetKey?.let {
            require(it.matches(Regex("[A-Za-z0-9_:.=-]{1,200}"))) { "judge channelTokenBudgetKey 형식이 잘못됐다" }
        }
    }

    val promptHash: String get() = prompt.sha256()

    override fun toString(): String =
        "NiaJudgeLlmRequest(promptVersion=$promptVersion, outputSchema=$outputSchema, seed=$seed, " +
            "timeoutMillis=$timeoutMillis, promptChars=${prompt.length}, promptHash=$promptHash, " +
            "stablePromptPrefixChars=$stablePromptPrefixChars, metadataKeys=${metadata.keys.sorted()}, " +
            "channelTokenBudgeted=${channelTokenBudgetKey != null})"

    companion object {
        const val OUTPUT_SCHEMA: String = "nia.participation-judge-output.v1"
        const val MAX_METADATA: Int = 16
        const val MAX_METADATA_VALUE_CHARS: Int = 256
    }
}

data class NiaJudgeLlmResponse(
    val content: String,
    val modelVersion: String,
    val finishReason: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val latencyMillis: Long? = null,
    val providerRequestId: String? = null,
) {
    init {
        require(content.isNotBlank()) { "judge response content 는 비어 있을 수 없다" }
        require(modelVersion.isStableLabel()) { "judge modelVersion 은 안정 label 이어야 한다: $modelVersion" }
        require(finishReason.isStableLabel()) { "judge finishReason 은 안정 label 이어야 한다: $finishReason" }
        promptTokens?.let { require(it >= 0) { "judge promptTokens 는 음수일 수 없다: $it" } }
        completionTokens?.let { require(it >= 0) { "judge completionTokens 는 음수일 수 없다: $it" } }
        latencyMillis?.let { require(it >= 0) { "judge latencyMillis 는 음수일 수 없다: $it" } }
        providerRequestId?.let { require(it.length <= MAX_PROVIDER_REQUEST_ID_CHARS) { "judge providerRequestId 가 너무 길다" } }
    }

    val contentHash: String get() = content.sha256()

    override fun toString(): String =
        "NiaJudgeLlmResponse(modelVersion=$modelVersion, finishReason=$finishReason, " +
            "contentChars=${content.length}, contentHash=$contentHash, promptTokens=$promptTokens, " +
            "completionTokens=$completionTokens, latencyMillis=$latencyMillis)"

    companion object {
        const val MAX_PROVIDER_REQUEST_ID_CHARS: Int = 256
    }
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun String.isStableLabel(): Boolean = matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))
