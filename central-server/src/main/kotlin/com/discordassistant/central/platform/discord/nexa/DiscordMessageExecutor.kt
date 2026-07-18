package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.content.SpeechBurstContentCodec
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.application.port.out.SpeechContentResolver
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import net.dv8tion.jda.api.JDA

/**
 * SPEAK 버블 실제 전송 JDA executor(NEXA-P13-T017, platform 어댑터).
 *
 * SPEAK 버스트의 각 버블을 reply 또는 일반 메시지로 전송한다. 전송 명령은 본문이 아니라 **참조**(speechPlanRef)만
 * 운반하므로([com.discordassistant.central.actionruntime.application.port.out.OutboundSendCommand] 경계와 일관),
 * 전송 직전 [content] 로 참조를 본문으로 푼다. 참조 본문이 없으면(미생성/만료) 전송하지 않고 우아하게 실패한다.
 *
 * **acceptance(T017)**: 전송 성공 시 Discord 메시지 ID 를 [ExecutionResult.Sent.messageId] 로 돌려준다 — 호출자가
 * 전송 nonce/idempotency·audit 에 그 ID 를 연결한다.
 *
 * 우아한 실패(T018)/rate-limit(T021): 대상 삭제·권한 상실·429 는 던지지 않고 [JdaExecutionErrors] 로 환산한다.
 *
 * **shadow 안전**: application 이 OutboundGuard 로 허용을 판정한 뒤에만 호출된다(OBSERVE_ONLY 등에서는 미호출).
 */
class DiscordMessageExecutor(
    private val jda: JDA,
    private val content: SpeechContentResolver,
) {
    fun send(
        channelId: String,
        speechPlanRef: String,
        bubbleIndex: Int,
        replyToMessageId: String?,
    ): ExecutionResult {
        val stored =
            content.resolve(speechPlanRef)
                ?: return ExecutionResult.Failed(ActionFailureReason.TARGET_MISSING) // 본문 미생성/만료 — 전송 안 함.
        val body =
            SpeechBurstContentCodec.decode(stored).getOrNull(bubbleIndex)
                ?: return ExecutionResult.Failed(ActionFailureReason.TARGET_MISSING)
        val channel =
            jda.getTextChannelById(channelId)
                ?: return ExecutionResult.Failed(ActionFailureReason.TARGET_MISSING) // 채널 삭제/접근 불가.
        return runCatching {
            val action =
                if (replyToMessageId.isNullOrBlank()) {
                    channel.sendMessage(body)
                } else {
                    channel.sendMessage(body).setMessageReference(replyToMessageId).failOnInvalidReply(false)
                }
            val sent = action.complete()
            ExecutionResult.Sent(messageId = sent.id)
        }.getOrElse { JdaExecutionErrors.toFailure(it) }
    }
}
