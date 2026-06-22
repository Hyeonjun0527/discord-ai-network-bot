package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.DiscordExecutorPort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.application.port.out.SpeechContentResolver
import net.dv8tion.jda.api.JDA

/**
 * [DiscordExecutorPort] 의 JDA 합성 어댑터(NEXA-P13-T015/T016/T017, platform 어댑터).
 *
 * typing/reaction/message 세 facet 의 JDA executor 를 하나의 아웃바운드 포트로 묶는다. application 의
 * [com.discordassistant.central.actionruntime.application.execution.ActionExecutionService]/[com.discordassistant
 * .central.actionruntime.application.execution.ReactionExecutionService] 가 **shadow 허용을 판정한 뒤에만** 이 포트를
 * 호출한다 — OBSERVE_ONLY 등 차단 단계에서는 단 한 번도 호출되지 않는다(P09 hard block, 전송 0회).
 *
 * 이 어댑터는 LIVE/CANARY 에서만 빈으로 와이어된다(실제 전송 경로). 테스트는 포트를 mock 으로 대체한다.
 */
class JdaDiscordExecutor(
    jda: JDA,
    contentResolver: SpeechContentResolver,
) : DiscordExecutorPort {
    private val typing = DiscordTypingExecutor(jda)
    private val reaction = DiscordReactionExecutor(jda)
    private val message = DiscordMessageExecutor(jda, contentResolver)

    override fun startTyping(channelId: String): ExecutionResult = typing.start(channelId)

    override fun react(
        channelId: String,
        targetMessageId: String,
        emoji: String,
    ): ExecutionResult = reaction.react(channelId, targetMessageId, emoji)

    override fun sendBubble(
        channelId: String,
        speechPlanRef: String,
        bubbleIndex: Int,
        replyToMessageId: String?,
    ): ExecutionResult = message.send(channelId, speechPlanRef, replyToMessageId)
}
