package com.discordassistant.central.conversation.domain.model.event

import java.time.Instant

/**
 * 멤버 표시 정체성 변경 정규화 이벤트(NEXA-P02-T021). 닉네임/표시명이 바뀌었다는 관찰 사실.
 *
 * 봉투([NormalizedDiscordEvent]) 공통 필드 + 정체성 고유 필드(대상 actor·닉네임/표시명 old→new)만 운반한다.
 * "시간 유효 기억 출처" 다 — 어느 시점에 어떤 이름이었는지 socialmemory 가 기억할 근거. privacyClass 는
 * MEDIUM(안정 식별자/표시 텍스트).
 *
 * 근거: conversation-context.md(정규화 관찰), test-fixtures/.../nickname-burst.v1.yaml(닉네임 변경 사례).
 *
 * **acceptance(T021)**: 닉네임 변경을 old→new 로 표현하고, 이벤트가 **순서가 뒤집혀 도착해도** [occurredAt]
 * 기준으로 현재값을 판정 가능하다([isMoreRecentThan]/[currentDisplayNameAt]). 수신 순서가 아니라 발생 시각이
 * 진실의 기준이다.
 */
data class MemberIdentityChanged(
    override val eventId: EventId,
    override val guildId: GuildId,
    override val channelId: ChannelId,
    override val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    override val privacyClass: PrivacyClass,
    /** 정체성이 바뀐 대상 actor. */
    val actorId: AuthorId,
    /** 길드 닉네임 변경(없거나 미변경이면 null). */
    val nickname: IdentityChange?,
    /** 표시명(global display name) 변경(없거나 미변경이면 null). */
    val displayName: IdentityChange?,
) : NormalizedDiscordEvent {
    /**
     * 같은 actor 의 [other] 변경보다 이 변경이 **더 최근**인가([occurredAt] 기준).
     *
     * 수신 순서(receivedAt/sourceSequence)가 아니라 발생 시각으로 판정한다 — 역순 도착에도 현재값을
     * 정확히 가린다.
     */
    fun isMoreRecentThan(other: MemberIdentityChanged): Boolean = actorId == other.actorId && occurredAt.isAfter(other.occurredAt)

    /**
     * 주어진 시각 [at] 시점의 닉네임으로 이 이벤트가 유효한지 — 즉 [occurredAt] 이 at 이하면 이 변경의
     * new 닉네임이 그 시점 현재값 후보다. 닉네임 변경이 없으면 null.
     */
    fun nicknameAsOf(at: Instant): String? = nickname?.takeIf { !occurredAt.isAfter(at) }?.new
}

/**
 * 표시 정체성 한 항목의 old→new 변경(시간 유효 기억의 단위).
 *
 * old 는 부재할 수 있다(이전 값을 모를 때 null). new 는 변경 후 값(설정 해제면 null).
 */
data class IdentityChange(
    /** 변경 전 값(모르면 null). */
    val old: String?,
    /** 변경 후 값(해제면 null). */
    val new: String?,
)
