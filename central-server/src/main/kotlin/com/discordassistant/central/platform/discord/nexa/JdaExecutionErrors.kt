package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.exceptions.RateLimitedException
import net.dv8tion.jda.api.requests.ErrorResponse
import java.time.Duration

/**
 * JDA 예외 → [ExecutionResult.Failed] 매핑(NEXA-P13-T018/T021, platform JDA 어댑터 공용).
 *
 * 실제 실행 executor(typing/reaction/message)가 던진 JDA 예외를 **우아한 실패** 결과로 환산한다(크래시 금지 — T018,
 * 다른 채널 fallback 없음). rate-limit(429)은 권고 retryAfter 를 실어 백프레셔를 존중하게 한다(T021).
 *
 * - UNKNOWN_CHANNEL/UNKNOWN_MESSAGE → TARGET_MISSING(대상 삭제 — 영구).
 * - MISSING_ACCESS/MISSING_PERMISSIONS/InsufficientPermissionException → PERMISSION_DENIED(권한 상실 — 영구).
 * - UNKNOWN_EMOJI/REACTION_BLOCKED/TOO_MANY_REACTIONS → TARGET_MISSING(emoji 불가 — 영구, reaction 한정).
 * - RateLimitedException → DISCORD_TRANSIENT + retryAfter(존중 — T021).
 * - 그 외 → DISCORD_TRANSIENT(일시 — bounded retry).
 */
internal object JdaExecutionErrors {
    fun toFailure(t: Throwable): ExecutionResult.Failed =
        when (t) {
            is RateLimitedException ->
                ExecutionResult.Failed(ActionFailureReason.DISCORD_TRANSIENT, retryAfter = Duration.ofMillis(t.retryAfter))
            is InsufficientPermissionException ->
                ExecutionResult.Failed(ActionFailureReason.PERMISSION_DENIED)
            is ErrorResponseException -> fromErrorResponse(t.errorResponse)
            else -> ExecutionResult.Failed(ActionFailureReason.DISCORD_TRANSIENT)
        }

    private fun fromErrorResponse(response: ErrorResponse): ExecutionResult.Failed =
        when (response) {
            ErrorResponse.UNKNOWN_CHANNEL,
            ErrorResponse.UNKNOWN_MESSAGE,
            ErrorResponse.UNKNOWN_EMOJI,
            ErrorResponse.REACTION_BLOCKED,
            ErrorResponse.TOO_MANY_REACTIONS,
            -> ExecutionResult.Failed(ActionFailureReason.TARGET_MISSING)
            ErrorResponse.MISSING_ACCESS,
            ErrorResponse.MISSING_PERMISSIONS,
            -> ExecutionResult.Failed(ActionFailureReason.PERMISSION_DENIED)
            else -> ExecutionResult.Failed(ActionFailureReason.DISCORD_TRANSIENT)
        }
}
