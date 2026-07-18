package com.discordassistant.central.actionruntime.application.port.out

/** 각 Discord SEND·REACT 호출 직전에 channel/global 실행 상한을 원자·멱등 예약하는 경계다. */
interface ExecutionPermitPort {
    fun reserve(
        actionId: String,
        channelKey: String,
        limits: ExecutionLimits,
    ): Boolean

    fun release(actionId: String): Boolean

    /** 순수 단위 테스트용 기본값. production에는 인메모리 또는 Redis adapter가 반드시 주입된다. */
    data object AllowAll : ExecutionPermitPort {
        override fun reserve(
            actionId: String,
            channelKey: String,
            limits: ExecutionLimits,
        ): Boolean = true

        override fun release(actionId: String): Boolean = true
    }
}

data class ExecutionLimits(
    val perChannel: Int,
    val global: Int,
    val windowSeconds: Long = 60,
) {
    init {
        require(perChannel > 0) { "채널 실행 상한은 양수여야 한다" }
        require(global > 0) { "전역 실행 상한은 양수여야 한다" }
        require(windowSeconds > 0) { "실행 상한 윈도우는 양수여야 한다" }
    }
}
