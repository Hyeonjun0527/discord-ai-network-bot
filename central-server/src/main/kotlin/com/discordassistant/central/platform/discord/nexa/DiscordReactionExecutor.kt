package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.emoji.Emoji

/**
 * REACT 실제 실행 JDA executor(NEXA-P13-T016, platform 어댑터).
 *
 * REACT action 을 **권한·target 존재·emoji availability** 를 확인해 실행한다. JDA `addReactionById` 의 결과/예외로
 * 이 세 가지를 판정한다: 채널/메시지 부재(UNKNOWN_CHANNEL/UNKNOWN_MESSAGE)·권한 없음(MISSING_*)·emoji 불가
 * (UNKNOWN_EMOJI 등)는 모두 [ExecutionResult.Failed] 로 환산한다(던지지 않음). 실패가 **SPEAK fallback 을 유발하지
 * 않는** 것은 호출자(application [ReactionExecutionService])의 책임이다 — 이 executor 는 결과만 돌려준다(T016).
 *
 * **shadow 안전**: application 이 OutboundGuard 로 허용을 판정한 뒤에만 호출된다(OBSERVE_ONLY 등에서는 미호출).
 */
class DiscordReactionExecutor(
    private val jda: JDA,
) {
    fun react(
        channelId: String,
        targetMessageId: String,
        emoji: String,
    ): ExecutionResult {
        val channel =
            jda.getTextChannelById(channelId)
                ?: return ExecutionResult.Failed(ActionFailureReason.TARGET_MISSING) // 채널 삭제/접근 불가.
        return runCatching {
            channel.addReactionById(targetMessageId, Emoji.fromFormatted(emoji)).complete()
            ExecutionResult.Ok
        }.getOrElse { JdaExecutionErrors.toFailure(it) }
    }
}
