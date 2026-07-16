package com.discordassistant.central.actionruntime.support

import com.discordassistant.central.actionruntime.application.port.out.ActionAuditPort
import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.DiscordExecutorPort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.actionruntime.domain.model.ActionAuditEvent
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import java.time.Instant

// NEXA-P13-T015~T024 — 실행/감사 테스트 공유 fake.
//
// shadow 안전 핵심(P09): executor fake 는 **호출 횟수**를 정확히 센다 — OBSERVE_ONLY 등 차단 단계에서 전송 0회를
// 검증한다(손으로 만든 카운트 fake, ShadowOutboundDispatcherTest 의 CountingDiscordSend 관례와 일관).

/**
 * [DiscordExecutorPort] 의 기록형 fake. 각 호출(typing/react/send)을 정확히 세고, 스크립트한 결과를 순서대로 돌려준다.
 * 전송 0회(shadow hard block)·잔여 버블 미전송(T020)·rate-limit 재시도(T021) 검증에 쓴다.
 */
open class RecordingDiscordExecutor(
    /** sendBubble 호출 순서대로 돌려줄 결과(없으면 매 호출마다 새 message ID 로 성공). */
    private val sendResults: ArrayDeque<ExecutionResult> = ArrayDeque(),
    /** startTyping 결과(기본 성공). */
    private val typingResult: ExecutionResult = ExecutionResult.Ok,
    /** react 결과(기본 성공). */
    private val reactResult: ExecutionResult = ExecutionResult.Ok,
) : DiscordExecutorPort {
    var typingCalls = 0
        private set
    var reactCalls = 0
        private set
    val sentBubbleIndexes = mutableListOf<Int>()
    val sentMessageIds = mutableListOf<String>()

    /** 모든 실제 전송류 호출의 총합(전송 0회 검증용 — typing 포함 어떤 JDA 호출도 일어나지 않았는지). */
    val totalExecutorCalls: Int
        get() = typingCalls + reactCalls + sentBubbleIndexes.size

    override fun startTyping(channelId: String): ExecutionResult {
        typingCalls++
        return typingResult
    }

    override fun react(
        channelId: String,
        targetMessageId: String,
        emoji: String,
    ): ExecutionResult {
        reactCalls++
        return reactResult
    }

    override fun sendBubble(
        channelId: String,
        speechPlanRef: String,
        bubbleIndex: Int,
        replyToMessageId: String?,
    ): ExecutionResult {
        val result = if (sendResults.isNotEmpty()) sendResults.removeFirst() else ExecutionResult.Sent("msg-$bubbleIndex")
        if (result is ExecutionResult.Sent) {
            sentBubbleIndexes += bubbleIndex
            sentMessageIds += result.messageId
        }
        return result
    }
}

/**
 * [ActionAuditPort] 의 in-memory fake(append-only). phase 시퀀스·message ID 연결을 검증한다(T022).
 */
class InMemoryActionAudit : ActionAuditPort {
    private val events = mutableListOf<ActionAuditEvent>()

    override fun append(event: ActionAuditEvent) {
        events += event
    }

    override fun findByAction(actionId: String): List<ActionAuditEvent> =
        events.filter { it.actionId == actionId }.sortedBy { it.occurredAt }

    /** 모든 사건(삽입순) — 시각이 같아도 순서 보존. */
    fun all(): List<ActionAuditEvent> = events.toList()

    /** [actionId] 의 phase 시퀀스(삽입순). */
    fun phasesOf(actionId: String): List<ActionAuditPhase> = events.filter { it.actionId == actionId }.map { it.phase }
}

/**
 * [ActionReevaluationPort] 의 제어형 fake. [currentVersion] 을 테스트가 바꿔 mid-burst contextVersion 변경(T020)·
 * stale 재평가를 흉내낸다. [stillValid] 는 [validOnReevaluate] 로 제어한다.
 */
class ControllableReevaluation(
    var currentVersion: Long? = 1L,
    var validOnReevaluate: Boolean = true,
) : ActionReevaluationPort {
    override fun currentContextVersion(target: ReevaluationTarget): Long? = currentVersion

    override fun stillValid(
        decisionId: String,
        target: ReevaluationTarget,
        scheduledContextVersion: Long,
        currentContextVersion: Long,
    ): Boolean = validOnReevaluate
}

/** 테스트용 TYPING 상태 SPEAK 행동을 만든다(실행 진입 상태). */
fun typingSpeakAction(
    decisionId: String = "dec-1",
    index: Int = 0,
    contextVersion: Long = 1L,
    channelId: String = "chan-1",
    threadId: String = "thread-1",
    replyToMessageId: String? = null,
): ScheduledSocialAction =
    ScheduledSocialAction
        .create(
            decisionId = decisionId,
            sampledActionIndex = index,
            type = ScheduledActionType.SPEAK,
            target =
                ActionTarget(
                    guildPseudonym = "guild-1",
                    channelId = channelId,
                    threadId = threadId,
                    replyToMessageId = replyToMessageId,
                ),
            executeAfter = Instant.parse("2026-01-01T00:00:00Z"),
            contextVersion = contextVersion,
            originRolloutMode = ShadowMode.LIVE,
        ).markScheduled()
        .beginReevaluation()
        .passReevaluation()
