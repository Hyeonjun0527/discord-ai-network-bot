package com.discordassistant.central.actionruntime.domain.model

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import java.time.Instant

/**
 * 예약된 사회적 행동 aggregate(NEXA-P13-T001, 순수 도메인·불변 transition).
 *
 * participation 이 낸 결정(SPEAK/REACT)을 actionruntime 이 **예약**한 단위다. decision ID, action type, target,
 * executeAfter(due 시점), contextVersion(예약 당시 장면 버전), status, attempt(시도 횟수)를 가진다. 실제 Discord
 * 전송은 이 묶음 범위 밖(T015~T017)이며, 이 aggregate 는 **스케줄·상태·취소·재시도 회계**까지만 책임진다.
 *
 * **acceptance(T001) — 상태 전이가 도메인 메서드로만 가능하고 불법 전이는 거부된다**:
 * - [status] 는 `var` 노출이 아니라 의미 있는 도메인 메서드([markScheduled]/[beginReevaluation]/[passReevaluation]/
 *   [beginTyping]/[markPartiallySent]/[complete]/[cancel]/[fail]/[retryTransient])로만 바뀐다.
 * - 모든 메서드는 [ActionStatus.canTransitionTo] 로 합법성을 검사하고, 불법이면 [IllegalActionTransition] 을 던진다.
 * - 모든 transition 은 **새 인스턴스**를 돌려준다(불변 — replay·테스트 안전). 원본은 바뀌지 않는다.
 *
 * **idempotency(T004)**: [identity] 가 안정 key — 같은 결정 재처리는 같은 identity 라 persistence unique 가 막는다.
 *
 * **재시도(T009)**: [attempt] 는 실행 시도 횟수다. transient 실패는 [retryTransient] 로 [maxAttempts] 까지만
 * 재예약하고, 초과하거나 영구 실패면 [fail] 로 [ActionStatus.FAILED] 종결한다(무한 재시도 방지).
 *
 * **재평가(T011)**: 예약 당시 [contextVersion] 과 due 시점 현재 scene version 이 다르면 [beginReevaluation] →
 * (stale 면) [cancel], (유효면) [passReevaluation] 으로 분기한다 — stale 한 action 을 그대로 실행하는 경로가 없다.
 *
 * 순수성: Spring/JPA/JDA 미참조(actionruntime.domain 규칙, NexaArchitectureTest.nexaDomainsArePure).
 */
data class ScheduledSocialAction(
    /** 안정 idempotency key(T004) — 같은 결정 재처리는 같은 값(중복 예약 차단). */
    val identity: ActionIdentity,
    /** 이 예약을 만든 participation 결정의 식별자(decision/correlation ID — 원문 비포함). */
    val decisionId: String,
    /** 예약된 행동 종류(SPEAK/REACT). */
    val type: ScheduledActionType,
    /** 전송 대상(길드 가명/채널/thread 식별 참조 — content 비포함). */
    val target: ActionTarget,
    /** due 시점 — 이 시각 이후에 scheduler 가 claim 한다(executeAfter). */
    val executeAfter: Instant,
    /** 예약 당시 장면 contextVersion — due 시점 현재 버전과 비교해 stale 여부 판정(T011). */
    val contextVersion: Long,
    /** 이 예약을 만든 참여 판단의 rollout 모드 — 실행 시점에 승격으로 send 권한이 넓어지지 않게 하는 immutable ceiling. */
    val originRolloutMode: ShadowMode,
    /** 현재 상태(초기 CONSIDERING). 도메인 메서드로만 바뀐다. */
    val status: ActionStatus = ActionStatus.CONSIDERING,
    /** 실행 시도 횟수(transient 재시도마다 증가 — bounded, T009). */
    val attempt: Int = 0,
    /** 영구 실패 원인(FAILED 일 때만 non-null — 사후 분석용). */
    val failureReason: ActionFailureReason? = null,
    /** 허용 최대 시도 횟수(이 횟수에 도달하면 transient 라도 더 재시도하지 않음). */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(decisionId.isNotBlank()) { "decisionId 는 비어 있을 수 없다" }
        require(contextVersion >= 0) { "contextVersion 은 음수일 수 없다: $contextVersion" }
        require(attempt >= 0) { "attempt 는 음수일 수 없다: $attempt" }
        require(maxAttempts >= 1) { "maxAttempts 는 1 이상이어야 한다: $maxAttempts" }
    }

    /** CONSIDERING → SCHEDULED: due index 에 예약한다. */
    fun markScheduled(): ScheduledSocialAction = transitionTo(ActionStatus.SCHEDULED)

    /** SCHEDULED → REEVALUATING: due 도래·claim 후 재평가를 시작한다(T011). */
    fun beginReevaluation(): ScheduledSocialAction = transitionTo(ActionStatus.REEVALUATING)

    /** REEVALUATING → TYPING: 재평가 통과 — 전송 직전 typing 표시 단계로 진행한다(P12). */
    fun passReevaluation(): ScheduledSocialAction = transitionTo(ActionStatus.TYPING)

    /** TYPING → PARTIALLY_SENT: 멀티 버블 중 일부만 전송됨(부분 진행 — recovery 검사 대상 T010). */
    fun markPartiallySent(): ScheduledSocialAction = transitionTo(ActionStatus.PARTIALLY_SENT)

    /** (TYPING|PARTIALLY_SENT) → COMPLETED: 모든 버블 전송 완료(성공 종결). */
    fun complete(): ScheduledSocialAction = transitionTo(ActionStatus.COMPLETED)

    /**
     * 현재 상태 → CANCELLED: 취소(다른 인간 응답/주제 전환/동의 철회/stale — T012~T014). terminal 에서는 거부된다.
     */
    fun cancel(): ScheduledSocialAction = transitionTo(ActionStatus.CANCELLED)

    /**
     * 현재 상태 → FAILED: 영구 실패로 종결한다([reason] 기록). 무한 재시도 금지(T009) — retryable 이 아니거나
     * [maxAttempts] 초과 시 호출한다. terminal 에서는 거부된다.
     */
    fun fail(reason: ActionFailureReason): ScheduledSocialAction = transitionTo(ActionStatus.FAILED).copy(failureReason = reason)

    /**
     * transient 실패를 **bounded** 재시도한다(T009). [attempt] 를 1 올려 [ActionStatus.SCHEDULED] 로 되돌린다.
     * 단 [reason] 이 retryable 이 아니거나 다음 시도가 [maxAttempts] 를 넘으면 재시도하지 않고 [fail] 로 종결한다.
     * 따라서 영구 실패·재시도 소진은 **항상 FAILED 로 수렴**한다(무한 재시도 경로 없음).
     */
    fun retryTransient(reason: ActionFailureReason): ScheduledSocialAction {
        val next = attempt + 1
        return if (reason.isRetryable && next < maxAttempts) {
            transitionTo(ActionStatus.SCHEDULED).copy(attempt = next)
        } else {
            fail(reason)
        }
    }

    /** 이 예약이 [now] 기준 due 인가(executeAfter 도래). scheduler 의 due index 조건과 일치. */
    fun isDue(now: Instant): Boolean = !now.isBefore(executeAfter)

    /**
     * 예약 당시 [contextVersion] 과 [currentContextVersion] 이 다르면 **stale**(맥락이 바뀜 — 재평가 필요, T011).
     * 같으면 직전 판단을 그대로 써도 안전하다.
     */
    fun isStale(currentContextVersion: Long): Boolean = currentContextVersion != contextVersion

    private fun transitionTo(target: ActionStatus): ScheduledSocialAction {
        if (!status.canTransitionTo(target)) {
            throw IllegalActionTransition(identity, status, target)
        }
        return copy(status = target)
    }

    companion object {
        /** 기본 최대 시도 횟수(transient 재시도 상한 — 무한 재시도 방지의 기본값). */
        const val DEFAULT_MAX_ATTEMPTS: Int = 3

        /**
         * 새 예약 행동을 만든다(초기 [ActionStatus.CONSIDERING]). 같은 결정·같은 index 면 같은 [ActionIdentity] 라
         * 중복 예약이 persistence 에서 차단된다(T004).
         */
        fun create(
            decisionId: String,
            sampledActionIndex: Int,
            type: ScheduledActionType,
            target: ActionTarget,
            executeAfter: Instant,
            contextVersion: Long,
            originRolloutMode: ShadowMode,
            maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        ): ScheduledSocialAction =
            ScheduledSocialAction(
                identity = ActionIdentity.of(decisionId, sampledActionIndex),
                decisionId = decisionId,
                type = type,
                target = target,
                executeAfter = executeAfter,
                contextVersion = contextVersion,
                originRolloutMode = originRolloutMode,
                maxAttempts = maxAttempts,
            )
    }
}

/**
 * 불법 상태 전이 시도(NEXA-P13-T001, 순수 도메인 예외). [ScheduledSocialAction] 의 도메인 메서드가 [ActionStatus]
 * 전이 그래프에 없는 전이를 거부할 때 던진다(acceptance: 불법 전이는 거부된다).
 */
class IllegalActionTransition(
    val identity: ActionIdentity,
    val from: ActionStatus,
    val to: ActionStatus,
) : IllegalStateException("불법 상태 전이: $from → $to (action=${identity.value})")
