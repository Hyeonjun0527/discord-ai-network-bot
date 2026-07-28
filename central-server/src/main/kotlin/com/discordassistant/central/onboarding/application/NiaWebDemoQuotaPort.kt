package com.discordassistant.central.onboarding.application

data class NiaWebDemoQuotaLimits(
    val perMinute: Int,
    val perUserWindow: Int,
    val globalWindow: Int,
    val windowSeconds: Long,
) {
    init {
        require(perMinute > 0) { "웹 체험 분당 한도는 양수여야 합니다." }
        require(perUserWindow > 0) { "웹 체험 사용자 한도는 양수여야 합니다." }
        require(globalWindow > 0) { "웹 체험 전체 한도는 양수여야 합니다." }
        require(windowSeconds > 0) { "웹 체험 한도 윈도우는 양수여야 합니다." }
    }
}

sealed interface NiaWebDemoQuotaDecision {
    data class Allowed(
        val remaining: Int,
    ) : NiaWebDemoQuotaDecision

    data object PerMinuteExceeded : NiaWebDemoQuotaDecision

    data object PerUserWindowExceeded : NiaWebDemoQuotaDecision

    data object GlobalWindowExceeded : NiaWebDemoQuotaDecision

    data object Unavailable : NiaWebDemoQuotaDecision
}

fun interface NiaWebDemoQuotaPort {
    fun tryConsume(
        userId: String,
        limits: NiaWebDemoQuotaLimits,
    ): NiaWebDemoQuotaDecision
}
