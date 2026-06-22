package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import java.time.Duration

/**
 * Discord 실제 실행 아웃바운드 포트(NEXA-P13-T015/T016/T017/T018/T021, application 레이어).
 *
 * typing indicator·reaction·message 의 **실제** 실행을 추상한다 — 실제 구현(JDA 어댑터)은 LIVE/CANARY 에서만
 * 와이어되고, **shadow 단계(OBSERVE_ONLY 등)에서는 [com.discordassistant.central.actionruntime.application.execution
 * .ActionExecutionService] 가 [com.discordassistant.central.actionruntime.domain.OutboundGuard] 로 이 포트를 한 번도
 * 호출하지 않는다**(P09 hard block — 전송 0회).
 *
 * 각 메서드는 **던지지 않고** [ExecutionResult] 로 결과를 돌려준다(우아한 실패 — T018: 대상 삭제/권한 상실 시 크래시
 * 금지). 따라서 호출자(application)가 결과를 보고 취소/실패 분류·audit 를 일관되게 처리한다.
 *
 * 순수성 경계: application 레이어 — 값 객체·도메인 enum 만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface DiscordExecutorPort {
    /**
     * [channelId] 에 typing indicator 를 1회 시작한다(JDA sendTyping 은 약 ~10s 유지 — actionruntime 이 maxDuration
     * 안에서 필요하면 재시작). 대상 부재/권한 상실이면 실패 결과를 돌려준다(던지지 않음).
     */
    fun startTyping(channelId: String): ExecutionResult

    /**
     * [targetMessageId] 에 [emoji] reaction 을 단다(REACT — T016). 대상 메시지 삭제/권한 없음/emoji 불가면 실패
     * 결과를 돌려준다. reaction 실패는 호출자가 **SPEAK 로 fallback 하지 않는다**(T016 acceptance — 조용히 실패 종결).
     */
    fun react(
        channelId: String,
        targetMessageId: String,
        emoji: String,
    ): ExecutionResult

    /**
     * 한 버블을 전송한다(SPEAK 의 각 조각 — T017). [replyToMessageId] 가 있으면 reply, 없으면 일반 메시지.
     * 성공 시 [ExecutionResult.Sent] 에 Discord 메시지 ID 를 싣는다(audit 연결 — T017 acceptance). 대상 삭제/권한
     * 상실/rate-limit 이면 그에 맞는 실패 결과를 돌려준다(던지지 않음 — T018/T021).
     */
    fun sendBubble(
        channelId: String,
        speechPlanRef: String,
        bubbleIndex: Int,
        replyToMessageId: String?,
    ): ExecutionResult
}

/**
 * 실행 결과(application sealed 값 객체·불변). 성공/차단·실패를 명시 구분해 호출자가 audit·취소·재시도 분류를 한다.
 */
sealed interface ExecutionResult {
    /** typing/reaction 실행 성공(메시지 ID 없음). */
    data object Ok : ExecutionResult

    /** 버블 전송 성공 — Discord 메시지 ID 를 싣는다(audit 연결, T017). */
    data class Sent(
        val messageId: String,
    ) : ExecutionResult

    /**
     * 실행 실패(우아한 실패 — 던지지 않음). [reason] 으로 재시도 가능 여부를 도메인이 결정(T009/T018). [retryAfter]
     * 는 rate-limit(429) 시 Discord 가 권고한 대기(존중 — T021). 그 외 null.
     */
    data class Failed(
        val reason: ActionFailureReason,
        val retryAfter: Duration? = null,
    ) : ExecutionResult
}
