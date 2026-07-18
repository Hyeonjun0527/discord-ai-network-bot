package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction

/** WAIT terminal 전이와 새 판단 요청을 잇는 transactional outbox 포트. */
interface WaitReevaluationOutboxPort {
    fun completeAndEnqueue(
        action: ScheduledSocialAction,
        observedContextVersion: Long,
    ): WaitReevaluationCommand?

    /** 여러 publisher가 같은 child를 실행하지 않도록 PENDING 행을 원자적으로 CLAIMED 전이해 돌려준다. */
    fun claimPending(limit: Int): List<WaitReevaluationCommand>

    fun markPublished(childDecisionId: String): Boolean

    fun releaseClaim(childDecisionId: String): Boolean
}

/** outbox가 전달하는 재평가 명령. 원문은 포함하지 않는다. */
data class WaitReevaluationCommand(
    val childDecisionId: String,
    val waitActionIdentity: String,
    val guildPseudonym: String,
    val channelId: String,
    val threadId: String,
    val subjectPseudonym: String?,
    val targetMessageId: String?,
    val routingGuildId: String?,
    val routingChannelId: String?,
    val routingUserId: String?,
    val observedContextVersion: Long,
    val wakeAttempt: Int,
    val wakeUpHint: String?,
    val expiresAt: java.time.Instant,
)

/** participation 경계가 최신 장면으로 새 판단을 만들 때 구현한다. */
fun interface WaitReevaluationHandler {
    fun handle(command: WaitReevaluationCommand): Boolean
}
