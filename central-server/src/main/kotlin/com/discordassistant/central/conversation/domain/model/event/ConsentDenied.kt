package com.discordassistant.central.conversation.domain.model.event

import java.time.Instant

/**
 * 동의 거부 내부 정규화 이벤트(NEXA-P02-T022). Discord 외부 신호가 아니라 conversation 경계가 발행하는
 * **내부** 이벤트로, 동의 철회(옵트아웃)·길드 비활성화·채널 제외를 하류에 전달해 projection·예약된 행동을
 * 중단시킨다.
 *
 * 봉투([NormalizedDiscordEvent]) 공통 필드를 그대로 채운다(거부가 발생/관찰된 시각·범위). 거부 [scope] 와
 * [reason] 만 고유 필드다. privacyClass 는 LOW(결정/상태만, 원문 없음).
 *
 * 근거: ConsentDecision.kt(관찰·발화 2축·fail-closed), conversation-context.md(불변식 1·관찰 전 동의),
 * domain-events.md(원문 비전파).
 *
 * **acceptance(T022)**: 동의 철회는 일반 메시지 이벤트보다 **우선** 적용된다. 동시/근접 시각에 도착한 일반
 * 이벤트보다 거부가 먼저 효력을 갖도록 [precedenceKey] 가 같은 [occurredAt] 에서 거부를 앞세운다
 * ([takesPrecedenceOver]). 안전 기본값은 차단(fail-closed)이다.
 */
data class ConsentDenied(
    override val eventId: EventId,
    override val guildId: GuildId,
    override val channelId: ChannelId,
    override val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    override val privacyClass: PrivacyClass,
    /** 거부 범위 — 사용자 옵트아웃 / 길드 비활성화 / 채널 제외. */
    val scope: ConsentDenialScope,
    /** 거부 사유(설명·감사용 짧은 코드; 원문/PII 아님). */
    val reason: ConsentDenialReason,
) : NormalizedDiscordEvent {
    /**
     * 충돌 해소용 우선순위 키. 같은 [occurredAt] 에서 동의 거부를 일반 이벤트보다 앞세우기 위한 정렬 키다.
     * (작을수록 먼저 적용 — 거부는 0, 일반 관찰은 1 이상으로 두는 규칙의 거부 쪽 값.)
     */
    val precedenceKey: Int get() = 0

    /**
     * 이 거부가 같은 시각에 발생한 일반 이벤트 [other] 보다 먼저 적용되어야 하는가.
     *
     * 거부의 [occurredAt] 이 other 보다 늦지 않으면(이르거나 같으면) 거부를 우선한다 — 철회가 동시각
     * 관찰보다 우선이라 예약된 행동/projection 을 즉시 중단한다(fail-closed).
     */
    fun takesPrecedenceOver(other: NormalizedDiscordEvent): Boolean {
        if (other is ConsentDenied) return occurredAt.isBefore(other.occurredAt)
        return !occurredAt.isAfter(other.occurredAt)
    }
}

/** 동의 거부의 범위(어디까지 관찰/발화를 멈추는가). */
enum class ConsentDenialScope {
    /** 개인 사용자 옵트아웃 — 그 사용자에 대한 관찰/투영 중단(개인 거부 우선). */
    USER_OPT_OUT,

    /** 길드 전체 비활성화 — 그 길드의 모든 관찰 중단. */
    GUILD_DISABLED,

    /** 채널 제외 — 그 채널만 관찰 범위에서 제외. */
    CHANNEL_EXCLUDED,
}

/** 동의 거부 사유 코드(감사/설명용; 원문/PII 아님). */
enum class ConsentDenialReason {
    /** 사용자가 명시적으로 옵트아웃했다. */
    USER_REQUESTED,

    /** 길드 관리자가 기능을 비활성화했다. */
    GUILD_POLICY,

    /** 채널이 관찰 허용 목록에서 제외됐다. */
    CHANNEL_POLICY,
}
