package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.participation.domain.model.action.SocialAction
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import java.time.Instant

/**
 * participation 결정 → actionruntime 예약 라우터(NEXA-P15-T006, application 레이어).
 *
 * participation 이 낸 [SocialAction](IGNORE/WAIT/REACT/SPEAK/CANCEL)을 actionruntime 의 예약/무시/리액션 경로로
 * 보낸다. **실제 전송(executor)의 shadow hard-block 은 send 경계**(
 * [com.discordassistant.central.actionruntime.application.ShadowOutboundDispatcher] /
 * [com.discordassistant.central.actionruntime.domain.OutboundGuard])가 책임진다 — 이 라우터는 "무엇을 예약할지"
 * 만 정한다.
 *
 * **acceptance(T006) — sampled action 을 예약/ignore/react 경로로 전달, shadow 는 schedule audit 만**:
 *  - [SocialAction.Ignore]/[SocialAction.Wait]: **예약하지 않는다**([RouteResult.Ignored]) — LLM 미호출·전송 없음.
 *  - [SocialAction.React]: REACT 예약([ScheduledActionType.REACT]).
 *  - [SocialAction.Speak]: SPEAK 예약([ScheduledActionType.SPEAK]) — 실제 발화 시점에 executor 가 shadow 면 차단.
 *  - [SocialAction.CancelPending]: 예약 취소(다른 인간 응답 등).
 *  shadow/live 모두 같은 예약 경로를 쓴다 — 차이는 **전송 차단**(executor)이라, shadow 에서도 schedule audit
 *  (예약+감사)는 남고 실제 전송만 막힌다. 멱등은 [ActionSchedulerPort.schedule](identity unique)이 보장한다.
 *
 * 순수성 경계: application — 포트·도메인 타입·표준 [Instant] 만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
class ParticipationActionRouter(
    private val scheduler: ActionSchedulerPort,
) {
    /**
     * [decisionId] 가 낸 [action] 을 [target] 으로 [executeAfter] 에 예약한다(또는 무시/취소). [contextVersion] 은
     * 예약 당시 장면 버전(stale 재평가 키), [originRolloutMode]는 이 행동이 얻은 최대 전송 권한이다. 결과로 무엇을
     * 했는지 돌려준다(테스트·감사).
     */
    fun route(
        decisionId: String,
        sampledActionIndex: Int,
        action: SocialAction,
        target: ActionTarget,
        executeAfter: Instant,
        contextVersion: Long,
        originRolloutMode: ShadowMode,
    ): RouteResult =
        when (action.kind) {
            SocialActionKind.IGNORE, SocialActionKind.WAIT ->
                RouteResult.Ignored(action.kind) // 예약 없음 — 전송도 quota 소모도 없음
            SocialActionKind.REACT ->
                schedule(
                    decisionId,
                    sampledActionIndex,
                    ScheduledActionType.REACT,
                    target,
                    executeAfter,
                    contextVersion,
                    originRolloutMode,
                )
            SocialActionKind.SPEAK ->
                schedule(
                    decisionId,
                    sampledActionIndex,
                    ScheduledActionType.SPEAK,
                    target,
                    executeAfter,
                    contextVersion,
                    originRolloutMode,
                )
            SocialActionKind.CANCEL_PENDING -> {
                // 취소: 같은 decision 의 sampled index 예약을 취소(idempotent — 이미 terminal 이면 무시).
                scheduler.find(actionIdentityOf(decisionId, sampledActionIndex))?.let { scheduler.cancel(it.identity) }
                RouteResult.Cancelled
            }
        }

    private fun schedule(
        decisionId: String,
        sampledActionIndex: Int,
        type: ScheduledActionType,
        target: ActionTarget,
        executeAfter: Instant,
        contextVersion: Long,
        originRolloutMode: ShadowMode,
    ): RouteResult {
        val action =
            ScheduledSocialAction
                .create(
                    decisionId = decisionId,
                    sampledActionIndex = sampledActionIndex,
                    type = type,
                    target = target,
                    executeAfter = executeAfter,
                    contextVersion = contextVersion,
                    originRolloutMode = originRolloutMode,
                ).markScheduled()
        val scheduled = scheduler.schedule(action)
        return RouteResult.Scheduled(type = type, newlyScheduled = scheduled)
    }

    private fun actionIdentityOf(
        decisionId: String,
        sampledActionIndex: Int,
    ) = com.discordassistant.central.actionruntime.domain.model.ActionIdentity
        .of(decisionId, sampledActionIndex)
}

/** [ParticipationActionRouter.route] 결과 — 무엇을 했는지(예약/무시/취소) 명시(감사·테스트). */
sealed interface RouteResult {
    /** IGNORE/WAIT — 예약하지 않음(전송·quota 무소모). */
    data class Ignored(
        val kind: SocialActionKind,
    ) : RouteResult

    /** SPEAK/REACT 예약됨. [newlyScheduled] false 면 멱등(이미 예약된 결정의 재처리). */
    data class Scheduled(
        val type: ScheduledActionType,
        val newlyScheduled: Boolean,
    ) : RouteResult

    /** CANCEL_PENDING — 예약 취소 처리. */
    data object Cancelled : RouteResult
}
