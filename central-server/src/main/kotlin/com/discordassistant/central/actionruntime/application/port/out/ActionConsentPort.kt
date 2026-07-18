package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.domain.model.ActionTarget

/** 예약 뒤 실제 Discord SEND/REACT 직전에 현재 발화 동의를 다시 읽는 실행 경계다. */
fun interface ActionConsentPort {
    fun isAllowed(target: ActionTarget): Boolean

    data object AllowForIsolatedTests : ActionConsentPort {
        override fun isAllowed(target: ActionTarget): Boolean = true
    }
}
