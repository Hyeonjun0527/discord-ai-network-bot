package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode

/**
 * 예약 행동 실행 직전의 현재 shadow/canary/live 모드 조회 포트.
 *
 * [originRolloutMode]는 예약 당시 확보한 최대 전송 권한이며, 현재 모드는 그 권한을 넓힐 수 없다. 전송 경계는 이
 * 포트로 현재 모드를 다시 읽어 두 모드의 제한적인 교집합을 만들고, [ShadowMode.allowsRealSend] 가 false 면 Discord
 * executor 를 호출하지 않는다.
 */
fun interface ActionExecutionModePort {
    fun currentMode(
        target: ActionTarget,
        originRolloutMode: ShadowMode,
    ): ShadowMode

    companion object {
        /**
         * 테스트/미바인딩 환경 기본값. 호출자가 넘긴 origin ceiling을 그대로 사용한다.
         * 운영에서는 adapter 가 현재 모드와 교집합을 낸다.
         */
        val REQUESTED_MODE: ActionExecutionModePort = ActionExecutionModePort { _, originRolloutMode -> originRolloutMode }
    }
}
