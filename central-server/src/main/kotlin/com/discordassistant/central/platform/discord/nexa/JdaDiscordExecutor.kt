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
 *
 * **JDA 지연 해석**: [jdaProvider] 로 JDA 를 **첫 전송 시점에** 얻는다(by lazy). JDA 는 시작 시 비동기로 연결되므로
 * 빈 생성 시점(context refresh)엔 아직 없을 수 있다 — 그때 해석하면 앱이 시작에 실패한다. 스케줄러 tick 은 JDA 연결
 * 후에야 실제 전송을 시도하므로, 첫 사용 시 해석하면 안전하다(미연결이면 [DiscordBot.requireActiveJda] 가 명확히 실패,
 * 스케줄러 runCatching 이 흡수해 다음 tick 에 재시도).
 */
class JdaDiscordExecutor(
    private val jdaProvider: () -> JDA,
    private val contentResolver: SpeechContentResolver,
) : DiscordExecutorPort {
    private val typing by lazy { DiscordTypingExecutor(jdaProvider()) }
    private val reaction by lazy { DiscordReactionExecutor(jdaProvider()) }
    private val message by lazy { DiscordMessageExecutor(jdaProvider(), contentResolver) }

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
    ): ExecutionResult = message.send(channelId, speechPlanRef, bubbleIndex, replyToMessageId)
}
