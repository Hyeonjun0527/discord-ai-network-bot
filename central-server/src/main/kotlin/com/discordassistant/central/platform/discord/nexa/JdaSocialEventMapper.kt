package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EmojiIdentity
import com.discordassistant.central.conversation.domain.model.event.EventIdentity
import com.discordassistant.central.conversation.domain.model.event.EventType
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.IdentityChange
import com.discordassistant.central.conversation.domain.model.event.MemberIdentityChanged
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import com.discordassistant.central.conversation.domain.model.event.Reaction
import com.discordassistant.central.conversation.domain.model.event.ReactionChange
import com.discordassistant.central.conversation.domain.model.event.ReactionIntensity
import com.discordassistant.central.conversation.domain.model.event.TypingStarted

/**
 * 리액션/타이핑/멤버 정체성 매퍼(NEXA-P03-T005). reaction add/remove, typing start, nickname/display 변경을 정규화.
 *
 * **acceptance(T005)**: 미지원/미관측 필드는 **explicit unavailable 상태로 기록**(단일 null 로 뭉개지 않음).
 *  - 멤버 정체성: [IdentityFieldSnapshot] 가 `Unchanged`(관측됐고 변경 없음) vs `Changed(old,new)`(이전 값 모르면
 *    old=null 로 명시)를 타입으로 구분한다 — "변경 없음" 과 "이전 값 모름" 을 같은 null 로 합치지 않는다.
 *  - 리액션 버스트 여부는 [ReactionIntensity] 로 보존(single vs burst)한다.
 *
 * 스냅샷이 receivedAt/sourceSequence 를 운반하므로 toEvent 들은 순수 함수다(시각/순번 하드코딩 없음).
 */
class JdaSocialEventMapper {
    /** 리액션 스냅샷 → 도메인 [Reaction]. privacyClass LOW(원문 없음). */
    fun toReaction(snapshot: ReactionSnapshot): Reaction =
        Reaction(
            eventId =
                EventIdentity(
                    discordId = snapshot.messageId,
                    type = EventType.REACTION,
                    // 같은 메시지·이모지·actor 의 add/remove 가 충돌하지 않도록 방향을 revision 으로 분리.
                    revision = if (snapshot.change == ReactionChangeSnapshot.ADDED) 0L else 1L,
                ).toEventId(),
            guildId = GuildId(snapshot.guildId),
            channelId = ChannelId(snapshot.channelId),
            occurredAt = snapshot.occurredAt,
            receivedAt = snapshot.receivedAt,
            sourceSequence = snapshot.sourceSequence,
            privacyClass = PrivacyClass.LOW,
            messageId = MessageId(snapshot.messageId),
            actorId = AuthorId(snapshot.actorId),
            emoji = snapshot.emoji.toEmojiIdentity(),
            change =
                when (snapshot.change) {
                    ReactionChangeSnapshot.ADDED -> ReactionChange.ADDED
                    ReactionChangeSnapshot.REMOVED -> ReactionChange.REMOVED
                },
            intensity = if (snapshot.burst) ReactionIntensity.BURST else ReactionIntensity.SINGLE,
        )

    /** 타이핑 스냅샷 → 도메인 [TypingStarted]. privacyClass LOW(원문 없음, 응답 의무 아님). */
    fun toTyping(snapshot: TypingSnapshot): TypingStarted =
        TypingStarted(
            eventId =
                EventIdentity(
                    // 타이핑은 메시지 id 가 없으므로 actor 를 대상 키로 쓰고 startedAt 으로 revision 분리.
                    discordId = snapshot.actorId,
                    type = EventType.TYPING_STARTED,
                    revision = snapshot.startedAt.epochSecond,
                ).toEventId(),
            guildId = GuildId(snapshot.guildId),
            channelId = ChannelId(snapshot.channelId),
            occurredAt = snapshot.startedAt,
            receivedAt = snapshot.receivedAt,
            sourceSequence = snapshot.sourceSequence,
            privacyClass = PrivacyClass.LOW,
            actorId = AuthorId(snapshot.actorId),
            startedAt = snapshot.startedAt,
            expiresAt = snapshot.expiresAt,
        )

    /** 멤버 정체성 스냅샷 → 도메인 [MemberIdentityChanged]. privacyClass MEDIUM(안정 식별자/표시 텍스트). */
    fun toMemberIdentity(snapshot: MemberIdentitySnapshot): MemberIdentityChanged =
        MemberIdentityChanged(
            eventId =
                EventIdentity(
                    discordId = snapshot.actorId,
                    type = EventType.MEMBER_IDENTITY_CHANGED,
                    revision = snapshot.occurredAt.toEpochMilli(),
                ).toEventId(),
            guildId = GuildId(snapshot.guildId),
            channelId = ChannelId(snapshot.channelId),
            occurredAt = snapshot.occurredAt,
            receivedAt = snapshot.receivedAt,
            sourceSequence = snapshot.sourceSequence,
            privacyClass = PrivacyClass.MEDIUM,
            actorId = AuthorId(snapshot.actorId),
            nickname = snapshot.nickname.toIdentityChange(),
            displayName = snapshot.displayName.toIdentityChange(),
        )
}

/** 이모지 스냅샷 → 도메인 [EmojiIdentity](unicode/custom 구분). */
internal fun EmojiSnapshot.toEmojiIdentity(): EmojiIdentity =
    when (this) {
        is EmojiSnapshot.Unicode -> EmojiIdentity.Unicode(codepoints)
        is EmojiSnapshot.Custom -> EmojiIdentity.Custom(customEmojiId, name)
    }

/**
 * 정체성 필드 스냅샷 → 도메인 [IdentityChange]? — "변경 없음"(Unchanged)은 null 로(이번 이벤트가 이 필드를 안
 * 바꿈), "변경됨"(Changed)은 old→new 를 보존한다. 이전 값을 모르면 old=null 로 명시(단일 null 뭉갬 아님).
 */
internal fun IdentityFieldSnapshot.toIdentityChange(): IdentityChange? =
    when (this) {
        is IdentityFieldSnapshot.Unchanged -> null
        is IdentityFieldSnapshot.Changed -> IdentityChange(old = old, new = new)
    }
