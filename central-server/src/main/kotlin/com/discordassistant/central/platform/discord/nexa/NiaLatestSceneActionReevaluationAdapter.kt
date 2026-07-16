package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import org.springframework.stereotype.Component

/** 예약 이후 새 사람 메시지가 도착한 니아 행동을 stale로 판정하는 actionruntime 어댑터. */
@Component
class NiaLatestSceneActionReevaluationAdapter(
    private val generations: NiaTurnGenerationTracker,
) : ActionReevaluationPort {
    override fun currentContextVersion(target: ReevaluationTarget): Long? {
        val channelId = target.channelId.toLongOrNull() ?: return UNTRACKED_CONTEXT_VERSION
        return generations.current(channelId) ?: UNTRACKED_CONTEXT_VERSION
    }

    override fun stillValid(
        decisionId: String,
        target: ReevaluationTarget,
        scheduledContextVersion: Long,
        currentContextVersion: Long,
    ): Boolean = currentContextVersion == UNTRACKED_CONTEXT_VERSION

    private companion object {
        /** tracker 도입 전 예약이나 Discord 밖 행동은 기존 기본 동작처럼 통과시킨다. */
        const val UNTRACKED_CONTEXT_VERSION: Long = Long.MIN_VALUE
    }
}
