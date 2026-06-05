package com.discordassistant.central.multiresponse.adapter.inbound.web.dto

// 다중응답 인바운드 어댑터(컨트롤러)가 파싱하는 요청 DTO 모음.
// 시그니처/기본값은 컨트롤러 분해 이전과 동일하게 보존(클라이언트 계약 불변).

data class SaveMultiResponsePolicyRequest(
    val channelId: Long? = null,
    val channelAiId: Long? = null,
    val mode: String = "single",
    val maxCandidates: Int = 1,
    val requireDistinctModels: Boolean = false,
    val providerDailyLimit: Int = 0,
    val timeoutSeconds: Int = 120,
    val synthesisEnabled: Boolean = false,
    val disabledReason: String? = null,
)

data class StartMultiResponseRunRequest(
    val channelId: Long,
    val requestId: String,
    val promptPreview: String? = null,
    val responseMode: String = "balanced",
)

data class RecordCandidateRequest(
    val answerRef: String? = null,
    val status: String = "completed",
    val latencyMs: Int? = null,
    val safetyFlags: List<String> = emptyList(),
    val qualityScore: Int? = null,
)

data class SynthesizeRunRequest(
    val answerRef: String,
    val selectedCandidateIds: List<Long> = emptyList(),
    val strategy: String = "best_by_heuristic",
    val qualitySummary: String? = null,
    val safetySummary: String? = null,
)

data class AdoptCandidateRequest(
    val userId: Long? = null,
    val rating: Int? = null,
    val reason: String? = null,
)

data class CompleteBestMultiResponseRunRequest(
    val strategy: String = "best_successful_candidate",
)

data class FailMultiResponseRunRequest(
    val reason: String,
)

data class PseudoStreamPlanRequest(
    val answer: String,
    val steps: List<Int> = emptyList(),
    val maxDiscordChars: Int = 1_900,
)
