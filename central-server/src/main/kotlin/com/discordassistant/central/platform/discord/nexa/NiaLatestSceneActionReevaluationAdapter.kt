package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.conversation.application.scene.ConversationSceneIngress
import org.springframework.stereotype.Component

/** 예약 이후 새 사람 메시지가 도착한 니아 행동을 stale로 판정하는 actionruntime 어댑터. */
@Component
class NiaLatestSceneActionReevaluationAdapter(
    private val generations: NiaTurnGenerationTracker,
    private val conversationScenes: ConversationSceneIngress,
) : ActionReevaluationPort {
    override fun currentContextVersion(target: ReevaluationTarget): Long? {
        val channelId = (target.routingChannelId ?: target.channelId).toLongOrNull() ?: return UNTRACKED_CONTEXT_VERSION
        generations.current(channelId)?.let { return it }

        val scheduledTurn = target.scheduledTurnGeneration ?: return UNTRACKED_CONTEXT_VERSION
        val scheduledScene = target.scheduledSceneContextVersion ?: return UNTRACKED_CONTEXT_VERSION
        val currentScene = conversationScenes.current(channelId)?.contextVersion ?: return null
        return if (currentScene == scheduledScene) scheduledTurn else RESTART_STALE_CONTEXT_VERSION
    }

    override fun currentSceneContextVersion(target: ReevaluationTarget): Long? {
        val channelId = (target.routingChannelId ?: target.channelId).toLongOrNull() ?: return null
        return conversationScenes.current(channelId)?.contextVersion
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

        /** 재시작 후 영속 scene이 전진한 예약은 실제 generation을 몰라도 반드시 stale로 만든다. */
        const val RESTART_STALE_CONTEXT_VERSION: Long = Long.MIN_VALUE + 1
    }
}
