package com.discordassistant.central.routing.application

/**
 * 채널별 클라우드 LLM 토큰 사용량을 원자적으로 예약하고 실제 사용량으로 정산하는 경계다.
 *
 * 외부 요청 직전에 최대 예상량을 예약해 동시 호출도 상한을 넘지 못하게 하고, 성공 응답의 usage가 있으면
 * 실제 input+output 토큰으로 낮춰 잡는다. timeout처럼 과금 여부를 알 수 없는 실패는 예약량을 유지한다.
 */
interface ChannelTokenBudgetPort {
    fun reserve(
        reservationId: String,
        channelKey: String,
        estimatedTokens: Int,
        limits: ChannelTokenBudgetLimits,
    ): Boolean

    fun settle(
        reservationId: String,
        actualTokens: Int,
    ): Boolean

    /** 예산을 적용하지 않는 단위 테스트·비 NIA 호출용 기본값. */
    data object Unlimited : ChannelTokenBudgetPort {
        override fun reserve(
            reservationId: String,
            channelKey: String,
            estimatedTokens: Int,
            limits: ChannelTokenBudgetLimits,
        ): Boolean = true

        override fun settle(
            reservationId: String,
            actualTokens: Int,
        ): Boolean = true
    }
}

data class ChannelTokenBudgetLimits(
    val perChannel: Int,
    val windowSeconds: Long,
) {
    init {
        require(perChannel > 0) { "채널 토큰 상한은 양수여야 한다" }
        require(windowSeconds > 0) { "채널 토큰 상한 윈도우는 양수여야 한다" }
    }
}

/** 채널 토큰 예산이 외부 HTTP 호출 전에 소진됐음을 구분하는 비용 안전 예외다. */
class ChannelTokenBudgetExceededException : CloudLlmException("이 채널의 AI 토큰 사용 한도를 초과했습니다.")
