package com.discordassistant.central.platform.discord

import com.discordassistant.central.channelai.application.ChannelAiProfileService
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.interactions.InteractionHook
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * AI 답변을 Discord 로 내보내는 렌더링 어댑터(god class 분해 — verbatim 이동).
 * 의사 스트리밍 편집 스케줄·채널 AI 프로필 웹훅·일반 봇 메시지 폴백을 담당한다.
 * 본문 로직(JDA 호출 순서·편집 간격·문구)은 DiscordBot.Listener 에서 1바이트 불변으로 이동했다.
 * DiscordBot.Listener 가 동일 인자로 위임 호출한다.
 */
class DiscordAnswerRenderer(
    private val channelProfiles: ChannelAiProfileService,
) {
    private val log = LoggerFactory.getLogger(DiscordAnswerRenderer::class.java)

    fun completePublicAnswerWithProfileFallback(
        hook: InteractionHook,
        channelUnion: MessageChannelUnion?,
        ctx: CommandContext,
        reply: Reply,
    ) {
        if (sendAnswerWebhook(channelUnion, ctx, reply)) {
            editOriginalText(hook, "✅ 답변을 채널 AI 프로필로 보냈어요.")
            return
        }
        if (sendBotChannelAnswer(channelUnion, reply)) {
            hook
                .editOriginal(
                    "⚠️ 채널 AI 이름/아이콘으로 보내려면 봇에 `웹후크 관리` 권한이 필요해요. " +
                        "이번 답변은 기본 봇 이름으로 보냈습니다.",
                ).queue()
            return
        }
        editOriginalWithPseudoStream(hook, reply)
    }

    private fun sendBotChannelAnswer(
        channelUnion: MessageChannelUnion?,
        reply: Reply,
    ): Boolean {
        if (reply.ephemeral) return false
        channelUnion ?: return false
        return runCatching {
            val snapshots = reply.publicPseudoStreamSnapshots()
            val action = channelUnion.asTextChannel().sendMessage(snapshots?.first() ?: reply.content)
            val sent = action.complete()
            if (snapshots != null) scheduleMessageEdits(sent, reply, snapshots, 1)
            true
        }.onFailure { e ->
            log.warn("일반 봇 메시지 폴백 전송 실패: {}", e.message)
        }.getOrDefault(false)
    }

    fun editOriginalWithPseudoStream(
        hook: InteractionHook,
        reply: Reply,
    ) {
        // 이미지 첨부(SD /imagine): 생성된 PNG 를 파일로 붙여 응답.
        val image = reply.imagePng
        if (image != null) {
            hook
                .editOriginal(reply.content.ifBlank { "🖼️ 생성된 이미지" })
                .setFiles(
                    net.dv8tion.jda.api.utils.FileUpload
                        .fromData(image, "image.png"),
                ).queue({}, { e ->
                    log.warn("이미지 첨부 응답 실패: {}", e.message)
                    hook
                        .editOriginal("⚠️ 이미지를 전송하지 못했어요.")
                        .queue({}, { fe -> log.warn("이미지 실패 안내 응답도 실패: {}", fe.message) })
                })
            return
        }
        val snapshots = reply.publicPseudoStreamSnapshots()
        if (snapshots == null) {
            editOriginalText(hook, reply.content)
            return
        }
        hook.editOriginal(snapshots.first()).queue(
            { scheduleOriginalEdits(hook, reply, snapshots, 1) },
            { e ->
                log.warn("의사 스트리밍 초기 응답 편집 실패: {}", e.message)
                hook.editOriginal(reply.content).queue({}, {})
            },
        )
    }

    private fun scheduleOriginalEdits(
        hook: InteractionHook,
        reply: Reply,
        snapshots: List<String>,
        index: Int,
    ) {
        if (index >= snapshots.size) return
        val action = hook.editOriginal(snapshots[index])
        action.queueAfter(
            reply.pseudoStream?.editIntervalMs ?: DEFAULT_PSEUDO_STREAM_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
            { scheduleOriginalEdits(hook, reply, snapshots, index + 1) },
            { e ->
                log.warn("의사 스트리밍 응답 편집 실패(index={}): {}", index, e.message)
                hook
                    .editOriginal(reply.content)
                    .queue({}, { fe -> log.warn("의사 스트리밍 폴백 편집도 실패(index={}): {}", index, fe.message) })
            },
        )
    }

    fun replyToMessageWithPseudoStream(
        source: Message,
        reply: Reply,
    ) {
        val snapshots = reply.publicPseudoStreamSnapshots()
        if (snapshots == null) {
            source.reply(reply.content).mentionRepliedUser(false).queue({}, {})
            return
        }
        val action =
            source
                .reply(snapshots.first())
                .mentionRepliedUser(false)
        action
            .queue(
                { sent -> scheduleMessageEdits(sent, reply, snapshots, 1) },
                { e ->
                    log.warn("멘션 의사 스트리밍 초기 답변 실패: {}", e.message)
                    source.reply(reply.content).mentionRepliedUser(false).queue({}, {})
                },
            )
    }

    private fun scheduleMessageEdits(
        message: Message,
        reply: Reply,
        snapshots: List<String>,
        index: Int,
    ) {
        if (index >= snapshots.size) return
        val action = message.editMessage(snapshots[index])
        action.queueAfter(
            reply.pseudoStream?.editIntervalMs ?: DEFAULT_PSEUDO_STREAM_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
            { scheduleMessageEdits(message, reply, snapshots, index + 1) },
            { e -> log.warn("의사 스트리밍 메시지 수정 실패(index={}): {}", index, e.message) },
        )
    }

    private fun Reply.publicPseudoStreamSnapshots(): List<String>? =
        pseudoStream
            ?.snapshots
            ?.filter { it.isNotBlank() }
            ?.takeIf { !ephemeral && it.size > 1 }

    /**
     * 채널별 AI 프로필이 설정된 경우, 답변을 Discord Webhook 으로 보내 표시 이름/아이콘을 채널 단위로 바꾼다.
     * 실패하면 false 를 반환해 일반 인터랙션 응답으로 안전하게 폴백한다.
     */
    fun sendAnswerWebhook(
        channelUnion: MessageChannelUnion?,
        ctx: CommandContext,
        reply: Reply,
    ): Boolean {
        if (reply.ephemeral) return false
        channelUnion ?: return false
        val profile = channelProfiles.get(ctx.guildId, ctx.channelId) ?: return false
        val channel = runCatching { channelUnion.asTextChannel() }.getOrNull() ?: return false
        return runCatching {
            val webhook =
                channel
                    .retrieveWebhooks()
                    .complete()
                    .firstOrNull { it.name == WEBHOOK_NAME }
                    ?: channel.createWebhook(WEBHOOK_NAME).complete()
            val action = webhook.sendMessage(reply.content).setUsername(profile.displayName)
            if (!profile.avatarUrl.isNullOrBlank()) {
                action.setAvatarUrl(profile.avatarUrl)
            }
            action.complete()
            true
        }.onFailure { e ->
            log.warn("채널 AI 프로필 웹훅 전송 실패(channel={}): {}", ctx.channelId, e.message)
        }.getOrDefault(false)
    }

    private fun editOriginalText(
        hook: InteractionHook,
        content: String,
    ) {
        hook.editOriginal(content).queue()
    }

    companion object {
        private const val DEFAULT_PSEUDO_STREAM_INTERVAL_MS = 1200L
        private const val WEBHOOK_NAME = "discord-ai-channel-profile"
    }
}
