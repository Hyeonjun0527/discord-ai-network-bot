package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import net.dv8tion.jda.api.JDA

/**
 * typing indicator 실제 실행 JDA executor(NEXA-P13-T015, platform 어댑터).
 *
 * 정책 계획(P12 TypingPlan)에 따라 actionruntime 이 typing 시작을 요청하면 [start] 로 JDA `sendTyping` 을 1회 보낸다.
 * JDA typing 은 약 ~10s 만 유지되고 자동 만료되므로 "typing 만 남고 메시지가 영원히 안 오는" 상태가 구조적으로 없다
 * (acceptance T015 — max duration). actionruntime 이 maxDuration 안에서 필요하면 다시 [start] 를 호출한다.
 *
 * **shadow 안전**: 이 executor 는 application([ActionExecutionService])이 [com.discordassistant.central.actionruntime
 * .domain.OutboundGuard] 로 전송 허용을 판정한 **뒤에만** 호출한다 — OBSERVE_ONLY 등 차단 단계에서는 호출되지 않는다.
 *
 * 우아한 실패(T018): 대상 채널 부재/권한 상실이면 던지지 않고 [ExecutionResult.Failed] 를 돌려준다.
 */
class DiscordTypingExecutor(
    private val jda: JDA,
) {
    fun start(channelId: String): ExecutionResult {
        val channel =
            jda.getTextChannelById(channelId)
                ?: return ExecutionResult.Failed(ActionFailureReason.TARGET_MISSING) // 채널 삭제/접근 불가.
        return runCatching {
            channel.sendTyping().complete()
            ExecutionResult.Ok
        }.getOrElse { JdaExecutionErrors.toFailure(it) }
    }
}
