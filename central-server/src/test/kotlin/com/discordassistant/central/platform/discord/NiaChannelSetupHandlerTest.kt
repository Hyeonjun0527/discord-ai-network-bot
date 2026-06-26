package com.discordassistant.central.platform.discord

import com.discordassistant.central.channelai.application.AutoRespondChannelRegistry
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.guild.application.ChannelAllowListPort
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.concrete.Category
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.requests.restaction.ChannelAction
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.any
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.eq
import org.mockito.Mockito.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * [NiaChannelSetupHandler] LLM 채널 허용 목록 등록 검증(버그 수정: 자동 생성 ai채팅이 LLM 차단되던 모순).
 *
 * 채널 생성 JDA 체인은 Mockito 딥스텁(기존 컨벤션, [JdaMessageExtractionTest])으로 fake 한다.
 * 핵심 계약: ① non-empty allow-list 면 chat·image 를 allowChannel 등록 ② 빈 목록이면 미등록(전체 허용 보존)
 * ③ 프로필·auto-respond 는 항상 호출(기존 동작 보존).
 */
class NiaChannelSetupHandlerTest {
    private val guildId = 100L
    private val chatId = 11L
    private val imageId = 22L
    private val memberId = 33L
    private val actorId = 7L

    // Mockito eq()/any() 는 null 을 반환해 Kotlin 비-null 파라미터(예: String) verify 에서 NPE 가 난다.
    // 값을 그대로 통과시키되 반환 타입을 non-null 로 단언하는 얇은 래퍼(별도 mockito-kotlin 의존 없이).
    private fun <T> eqK(value: T): T {
        eq(value)
        return value
    }

    private fun ctx() =
        CommandContext(
            guildId = guildId,
            channelId = 1L,
            userId = actorId,
            roleIds = emptySet(),
            isAdmin = true,
        )

    /** 채널 생성 JDA 체인(카테고리/채널/핀/응답)을 딥스텁으로 세운 버튼 이벤트를 만든다(빈 카테고리 → 생성 분기로 진입). */
    private fun mockCreateEvent(): ButtonInteractionEvent {
        val event = mock(ButtonInteractionEvent::class.java, RETURNS_DEEP_STUBS)
        val guild = mock(Guild::class.java, RETURNS_DEEP_STUBS)
        `when`(event.guild).thenReturn(guild)
        `when`(guild.idLong).thenReturn(guildId)

        // 봇 권한 충족.
        val self = mock(Member::class.java)
        `when`(guild.selfMember).thenReturn(self)
        `when`(self.hasPermission(Permission.MANAGE_CHANNEL)).thenReturn(true)

        // 멱등 분기 회피: 기존 카테고리 없음(딥스텁 categoryCache.iterator() 는 기본 빈) → 생성 분기로 진입.

        // 카테고리/채널 생성 딥스텁. createCategory → complete(Category), createTextChannel → complete(TextChannel).
        val voiceCategory = mock(Category::class.java, RETURNS_DEEP_STUBS)
        val featureCategory = mock(Category::class.java, RETURNS_DEEP_STUBS)

        @Suppress("UNCHECKED_CAST")
        val voiceCatAction = mock(ChannelAction::class.java, RETURNS_DEEP_STUBS) as ChannelAction<Category>

        @Suppress("UNCHECKED_CAST")
        val featureCatAction = mock(ChannelAction::class.java, RETURNS_DEEP_STUBS) as ChannelAction<Category>
        `when`(guild.createCategory(any())).thenReturn(voiceCatAction, featureCatAction)
        `when`(voiceCatAction.complete()).thenReturn(voiceCategory)
        `when`(featureCatAction.complete()).thenReturn(featureCategory)

        @Suppress("UNCHECKED_CAST")
        val voiceChanAction = mock(ChannelAction::class.java, RETURNS_DEEP_STUBS) as ChannelAction<VoiceChannel>
        `when`(voiceCategory.createVoiceChannel(any())).thenReturn(voiceChanAction)

        val chat = mock(TextChannel::class.java, RETURNS_DEEP_STUBS)
        val image = mock(TextChannel::class.java, RETURNS_DEEP_STUBS)
        val member = mock(TextChannel::class.java, RETURNS_DEEP_STUBS)
        `when`(chat.idLong).thenReturn(chatId)
        `when`(image.idLong).thenReturn(imageId)
        `when`(member.idLong).thenReturn(memberId)
        @Suppress("UNCHECKED_CAST")
        val chatAction = mock(ChannelAction::class.java, RETURNS_DEEP_STUBS) as ChannelAction<TextChannel>

        @Suppress("UNCHECKED_CAST")
        val imageAction = mock(ChannelAction::class.java, RETURNS_DEEP_STUBS) as ChannelAction<TextChannel>

        @Suppress("UNCHECKED_CAST")
        val memberAction = mock(ChannelAction::class.java, RETURNS_DEEP_STUBS) as ChannelAction<TextChannel>
        `when`(featureCategory.createTextChannel(any())).thenReturn(chatAction, imageAction, memberAction)
        `when`(chatAction.complete()).thenReturn(chat)
        `when`(imageAction.complete()).thenReturn(image)
        `when`(memberAction.complete()).thenReturn(member)

        return event
    }

    @Test
    fun `non-empty 허용목록이면 ai채팅·ai그림을 LLM 허용 목록에 등록한다`() {
        val profiles = mock(ChannelAiProfileService::class.java)
        val autoRespond = mock(AutoRespondChannelRegistry::class.java)
        val allowList = mock(ChannelAllowListPort::class.java)
        val participationFlags = mock(NexaParticipationFlagService::class.java)
        `when`(allowList.allowedChannelIds(guildId)).thenReturn(listOf(999L)) // non-empty = 그 채널만 허용

        NiaChannelSetupHandler(profiles, autoRespond, allowList, participationFlags).handle(mockCreateEvent(), ctx(), "ko")

        verify(allowList).allowChannel(eq(guildId), eq(chatId), eq(actorId))
        verify(allowList).allowChannel(eq(guildId), eq(imageId), eq(actorId))
        verify(allowList).allowChannel(eq(guildId), eq(memberId), eq(actorId))
        verify(participationFlags).enableChannelLive(eq(guildId), eq(memberId))
    }

    @Test
    fun `빈 허용목록이면 전체 허용 보존 - allowChannel 호출하지 않는다`() {
        val profiles = mock(ChannelAiProfileService::class.java)
        val autoRespond = mock(AutoRespondChannelRegistry::class.java)
        val allowList = mock(ChannelAllowListPort::class.java)
        val participationFlags = mock(NexaParticipationFlagService::class.java)
        `when`(allowList.allowedChannelIds(guildId)).thenReturn(emptyList()) // 빈 목록 = 전체 허용

        NiaChannelSetupHandler(profiles, autoRespond, allowList, participationFlags).handle(mockCreateEvent(), ctx(), "ko")

        verify(allowList, never()).allowChannel(anyLong(), anyLong(), anyLong())
        verify(participationFlags).enableChannelLive(eq(guildId), eq(memberId))
    }

    @Test
    fun `기존 프로필·auto-respond 호출은 보존된다`() {
        val profiles = mock(ChannelAiProfileService::class.java)
        val autoRespond = mock(AutoRespondChannelRegistry::class.java)
        val allowList = mock(ChannelAllowListPort::class.java)
        val participationFlags = mock(NexaParticipationFlagService::class.java)
        `when`(allowList.allowedChannelIds(guildId)).thenReturn(emptyList())

        NiaChannelSetupHandler(profiles, autoRespond, allowList, participationFlags).handle(mockCreateEvent(), ctx(), "ko")

        // ai채팅에 니아 프로필 부여 + 자동응답 ON(기존 동작) — allow-list 변경과 무관하게 유지.
        // Kotlin 기본인자는 set$default 가 전 인자를 채워 9-arg set 을 호출하므로 mock 은 9-arg 형태를 본다.
        verify(profiles).set(
            eq(guildId),
            eq(chatId),
            eqK(NiaChannelSetup.NIA_PROFILE_NAME), // 비-null String → null 반환 NPE 회피
            isNull(), // avatarUrl = null
            eqK(actorId), // actorId(Long?, 박싱) = ctx.userId
            isNull(), // purpose
            isNull(), // tone
            isNull(), // answerLength
            isNull(), // constitution
        )
        verify(autoRespond).setAutoRespond(eq(guildId), eq(chatId), eq(true), eq(actorId))
    }
}
