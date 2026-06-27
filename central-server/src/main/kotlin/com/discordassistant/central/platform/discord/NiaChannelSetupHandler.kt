package com.discordassistant.central.platform.discord

import com.discordassistant.central.channelai.application.AutoRespondChannelRegistry
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.global.i18n.I18n
import com.discordassistant.central.guild.application.ChannelAllowListPort
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.Category
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.slf4j.LoggerFactory

/**
 * "🏗️ 니아 채널 자동 만들기" 버튼/슬래시(`setup-channels`) 핸들러.
 * 카미봇의 "채널 자동으로 만들기"처럼 버튼 한 번에 카테고리/채널을 만들고 사용 가이드를 핀으로 단다.
 *
 * 순수 결정(권한 분기·멱등·이름·가이드 문구)은 [NiaChannelSetup] 이 SSOT 로 소유하고(단위 테스트),
 * 이 클래스는 JDA I/O(권한 조회·채널 생성·핀·응답)만 글루한다([OnboardingInteractionHandler] 스타일).
 *
 * **ai채팅에서 니아가 실제로 응답하는 메커니즘**: ai채팅 채널에 ① [ChannelAiProfileService.set] 으로 니아
 * 채널 AI 프로필(페르소나)을 부여하고 ② [AutoRespondChannelRegistry.setAutoRespond] 로 자동응답을 켠다.
 * 그러면 그 채널의 **모든 텍스트 메시지에 멘션 없이** 니아가 자동 응답한다(`DiscordBot.Listener.onMessageReceived`
 * 가 자동응답 캐시로 판정 → `/ask` 와 동일 흐름, 프로필이 있으므로 니아 페르소나 웹훅으로 응답).
 * `.` 로 시작하는 메시지는 제외(카미봇 컨벤션). 캐시 무효화로 생성 즉시 반영된다.
 *
 * **LLM 채널 허용 목록 등록**: 자동응답(auto-respond)만 켜고 LLM 채널 정책에 등록하지 않으면, 길드 allow-list 가
 * non-empty 인 서버에서 자동 생성 ai채팅이 목록에 없어 RequestOrchestrator 가 "이 채널에서는 LLM 을 사용할 수 없습니다"
 * 로 차단한다(핀 가이드와 모순). 그래서 [channelAllowList] 로 자동 생성 ai채팅·ai그림·니아수다를 허용 목록에 추가한다 —
 * 단 빈 목록(=전체 허용)일 때는 건드리지 않는다(등록하면 "그 채널만 허용"으로 좁아져 다른 채널이 막힌다).
 */
class NiaChannelSetupHandler(
    private val channelProfiles: ChannelAiProfileService,
    private val autoRespondChannels: AutoRespondChannelRegistry,
    private val channelAllowList: ChannelAllowListPort,
    private val participationFlags: NexaParticipationFlagService,
) {
    private val log = LoggerFactory.getLogger(NiaChannelSetupHandler::class.java)

    /**
     * 버튼/슬래시 공용 진입점. [event] 는 [IReplyCallback](버튼/슬래시 모두 구현)이어야 한다.
     * 권한 거부/멱등은 즉시 ephemeral reply, 생성은 deferReply(ephemeral) 후 JDA 호출이 끝나면 editOriginal.
     */
    fun handle(
        event: GenericInteractionCreateEvent,
        ctx: CommandContext,
        language: String,
    ) {
        val callback = event as IReplyCallback
        val guild = event.guild
        if (guild == null) {
            callback.reply(I18n.get("niaSetupAdminOnly", language)).setEphemeral(true).queue()
            return
        }
        when (NiaChannelSetup.decide(callerIsAdmin = ctx.isAdmin, botCanManageChannels = guild.botCanManageChannels())) {
            NiaChannelSetup.PermissionDecision.NOT_ADMIN -> {
                callback.reply(I18n.get("niaSetupAdminOnly", language)).setEphemeral(true).queue()
                return
            }
            NiaChannelSetup.PermissionDecision.BOT_MISSING_PERMISSION -> {
                callback.reply(I18n.get("niaSetupBotMissingPermission", language)).setEphemeral(true).queue()
                return
            }
            NiaChannelSetup.PermissionDecision.ALLOWED -> Unit
        }

        // 멱등: 이미 "니아 기능 채널" 카테고리가 있으면 새로 만들지 않고 안내만 한다.
        val existingNames = guild.categoryCache.map { it.name }
        if (NiaChannelSetup.alreadySetUp(existingNames, language)) {
            val featureCategory = guild.categoryCache.firstOrNull { it.name.equals(NiaChannelSetup.featureCategoryName(language), true) }
            val chat = featureCategory?.textChannels?.firstOrNull { it.name.equals(NiaChannelSetup.chatChannelName(language), true) }
            val image = featureCategory?.textChannels?.firstOrNull { it.name.equals(NiaChannelSetup.imageChannelName(language), true) }
            val member =
                featureCategory?.textChannels?.firstOrNull { it.name.equals(NiaChannelSetup.memberChannelName(language), true) }
                    ?: featureCategory?.createTextChannel(NiaChannelSetup.memberChannelName(language))?.complete()?.also {
                        pinGuide(it, NiaChannelSetup.memberGuide(language))
                    }
            // 이미 만들어진 서버라도 과거 버전에서 등록을 누락했을 수 있다 → 같은 가드로 재등록해 기존 서버도 자동 복구.
            if (chat != null && image != null && member != null) {
                registerLlmAllowList(guild.idLong, listOf(chat.idLong, image.idLong, member.idLong), ctx.userId)
                participationFlags.enableChannelLive(guild.idLong, member.idLong)
            }
            callback
                .reply(
                    I18n.get(
                        "niaSetupAlreadyExists",
                        language,
                        chat.mentionOr(language, "niaSetupChannelChat"),
                        image.mentionOr(language, "niaSetupChannelImage"),
                        member.mentionOr(language, "niaSetupChannelMember"),
                    ),
                ).setEphemeral(true)
                .queue()
            return
        }

        // 채널 생성은 여러 REST 왕복이라 게이트웨이 스레드를 막지 않게 deferReply 후 진행. JDA 호출은 동기(complete)로 순서 보장.
        callback.deferReply(true).queue()
        try {
            val created = createNiaChannels(guild, language)
            // ai채팅에 니아 채널 AI 프로필 부여(페르소나) → 응답이 니아 웹훅으로 나간다(메커니즘은 클래스 KDoc).
            channelProfiles.set(
                guildId = guild.idLong,
                channelId = created.chat.idLong,
                displayName = NiaChannelSetup.NIA_PROFILE_NAME,
                avatarUrl = null,
                actorId = ctx.userId,
                purpose = "친구 단톡방의 한 사람처럼 짧게 한마디 거드는 니아",
                tone = "까칠하지만 장난스럽고 솔직하게",
                answerLength = "very_short",
            )
            // 자동응답 켜기 → 생성 즉시 그 채널의 모든 메시지에 멘션 없이 니아가 답한다(캐시 무효화로 즉시 반영).
            autoRespondChannels.setAutoRespond(
                guildId = guild.idLong,
                channelId = created.chat.idLong,
                on = true,
                actorId = ctx.userId,
            )
            // 자동 생성 채널은 무조건 LLM 사용 가능해야 한다(auto-respond 와 LLM 정책 불일치 방지).
            registerLlmAllowList(guild.idLong, listOf(created.chat.idLong, created.image.idLong, created.member.idLong), ctx.userId)
            // 사람처럼 눈치 보고 끼어드는 NEXA participation 전용 채널. ai채팅(항상 답변)과 의도를 분리한다.
            participationFlags.enableChannelLive(guild.idLong, created.member.idLong)
            callback.hook
                .editOriginal(
                    I18n.get(
                        "niaSetupSuccess",
                        language,
                        created.chat.asMention,
                        created.image.asMention,
                        created.member.asMention,
                        created.voiceName,
                    ),
                ).queue({}, {})
        } catch (e: Exception) {
            // 채널 생성 실패(권한 변경·레이트리밋 등)는 사용자에겐 일반 안내, 서버엔 길드·스택을 남긴다.
            log.warn("니아 채널 자동 생성 실패(guild={}): {}", guild.idLong, e.message, e)
            callback.hook.editOriginal(I18n.get("niaSetupFailed", language)).queue({}, {})
        }
    }

    /** 만든 채널 핸들(성공 응답 멘션용). */
    private data class CreatedChannels(
        val chat: TextChannel,
        val image: TextChannel,
        val member: TextChannel,
        val voiceName: String,
    )

    /**
     * 카테고리/채널을 생성하고 가이드를 핀으로 단다(JDA I/O). complete() 로 순서를 보장한다.
     *  - "니아 음성 채널" 카테고리 + 음성채널 1개.
     *  - "니아 기능 채널" 카테고리 + ai채팅·ai그림 텍스트채널 + 각 핀 가이드.
     */
    private fun createNiaChannels(
        guild: Guild,
        language: String,
    ): CreatedChannels {
        // 음성 카테고리 + 음성 채널(단순 음성 — TTS 등 미보유 기능은 약속하지 않는다).
        val voiceCategory: Category = guild.createCategory(NiaChannelSetup.voiceCategoryName(language)).complete()
        val voiceName = NiaChannelSetup.voiceChannelName(language)
        voiceCategory.createVoiceChannel(voiceName).complete()

        // 기능 카테고리 + ai채팅/ai그림.
        val featureCategory: Category = guild.createCategory(NiaChannelSetup.featureCategoryName(language)).complete()
        val chat: TextChannel = featureCategory.createTextChannel(NiaChannelSetup.chatChannelName(language)).complete()
        val image: TextChannel = featureCategory.createTextChannel(NiaChannelSetup.imageChannelName(language)).complete()
        val member: TextChannel = featureCategory.createTextChannel(NiaChannelSetup.memberChannelName(language)).complete()

        pinGuide(chat, NiaChannelSetup.chatGuide(language))
        pinGuide(image, NiaChannelSetup.imageGuide(language))
        pinGuide(member, NiaChannelSetup.memberGuide(language))

        return CreatedChannels(chat = chat, image = image, member = member, voiceName = voiceName)
    }

    /** 가이드 메시지를 보낸 뒤 핀(pin). 핀 실패(권한 등)는 채널 생성 자체를 깨지 않게 graceful. */
    private fun pinGuide(
        channel: TextChannel,
        guide: String,
    ) {
        runCatching {
            channel
                .sendMessage(guide)
                .complete()
                .pin()
                .complete()
        }.onFailure { log.warn("가이드 핀 실패(channel={}): {}", channel.idLong, it.message) }
    }

    /**
     * 자동 생성 ai채팅·ai그림을 LLM 채널 허용 목록에 등록한다.
     * 단 allow-list 가 **비어 있으면 전체 허용 상태**이므로 건드리지 않는다 — 여기서 등록하면 "그 채널만 허용"으로
     * 좁아져 다른 채널(예: 질문 채널)이 막히는 부작용이 생긴다. non-empty 일 때만 ai채팅·ai그림을 추가한다.
     */
    private fun registerLlmAllowList(
        guildId: Long,
        channelIds: Collection<Long>,
        actorId: Long,
    ) {
        if (channelAllowList.allowedChannelIds(guildId).isEmpty()) return
        channelIds.distinct().forEach { channelAllowList.allowChannel(guildId, it, actorId) }
    }

    /** 봇이 채널 관리 권한(MANAGE_CHANNEL)이 있는지. */
    private fun Guild.botCanManageChannels(): Boolean = selfMember.hasPermission(Permission.MANAGE_CHANNEL)

    /** 채널 멘션(`<#id>`) 또는 채널이 없으면 i18n 이름 폴백. */
    private fun TextChannel?.mentionOr(
        language: String,
        nameKey: String,
    ): String = this?.asMention ?: I18n.get(nameKey, language)
}
