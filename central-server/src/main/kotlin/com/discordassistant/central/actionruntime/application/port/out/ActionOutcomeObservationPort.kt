package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import java.time.Instant

/** 실제 SEND·REACT 성공을 이후 사람 반응 귀속용 궤적으로 넘기는 경계다. */
fun interface ActionOutcomeObservationPort {
    fun recordExecuted(
        action: ScheduledSocialAction,
        discordMessageId: String?,
        executedAt: Instant,
    )

    data object Noop : ActionOutcomeObservationPort {
        override fun recordExecuted(
            action: ScheduledSocialAction,
            discordMessageId: String?,
            executedAt: Instant,
        ) = Unit
    }
}
