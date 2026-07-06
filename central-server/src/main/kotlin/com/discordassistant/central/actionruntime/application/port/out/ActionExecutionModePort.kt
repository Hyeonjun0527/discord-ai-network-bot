package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode

/**
 * 예약 행동 실행 직전의 현재 shadow/canary/live 모드 조회 포트.
 *
 * 예약 시점 또는 due poller 가 넘긴 모드는 오래됐을 수 있다. 전송 경계는 이 포트로 현재 모드를 다시 읽고,
 * [ShadowMode.allowsRealSend] 가 false 면 Discord executor 를 호출하지 않는다.
 */
fun interface ActionExecutionModePort {
    fun currentMode(
        target: ActionTarget,
        requestedMode: ShadowMode,
    ): ShadowMode

    companion object {
        /**
         * 테스트/미바인딩 환경 기본값. 기존 호출자가 넘긴 모드를 그대로 사용한다.
         * 운영에서는 adapter 가 이 포트를 구현해 저장소의 현재 모드로 덮어쓴다.
         */
        val REQUESTED_MODE: ActionExecutionModePort = ActionExecutionModePort { _, requestedMode -> requestedMode }
    }
}
