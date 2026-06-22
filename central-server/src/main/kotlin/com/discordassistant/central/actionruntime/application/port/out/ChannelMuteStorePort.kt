package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.domain.ChannelMuteLevel
import java.time.Instant

/**
 * 채널 mute 상태·audit 아웃바운드 포트(NEXA-P18-T014, application 레이어).
 *
 * 어느 채널이 현재 어느 [ChannelMuteLevel] 인지(활성 집합)를 영속화하고, mute/해제 audit 를 **append-only** 로
 * 남긴다. 구현(JPA)이 표(`nexa_channel_mute`)와 audit(`nexa_channel_mute_audit`)를 채운다.
 *
 * **즉시성(acceptance T014)**: [mute] 는 활성 집합에 즉시 반영돼야 하고, [activeMutes] 가 그 SSOT 다 — 발동 즉시
 * 다음 관찰/발화 경계가 차단을 본다.
 *
 * 순수성 경계: application 레이어 — 도메인 enum·표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface ChannelMuteStorePort {
    /** 현재 mute 상태인 채널 가명 → 수준 맵을 돌려준다(결정 코어가 차단 판정에 쓴다). [ChannelMuteLevel.NONE] 은 제외. */
    fun activeMutes(): Map<String, ChannelMuteLevel>

    /**
     * [channelPseudonym] 을 [level] 로 mute 한다(멱등 — 같은 수준 재설정은 유지, 수준 변경은 갱신). [actor]·[reason]·
     * [cancelledPending]·[at] 으로 audit 를 남긴다([cancelledPending] = 이 발동으로 취소된 pending 행동 수).
     */
    fun mute(
        channelPseudonym: String,
        level: ChannelMuteLevel,
        actor: String,
        reason: String,
        cancelledPending: Int,
        at: Instant,
    )

    /** [channelPseudonym] 의 mute 를 해제한다(멱등 — 이미 해제면 무시). [actor]·[at] 으로 해제 audit 를 남긴다. */
    fun unmute(
        channelPseudonym: String,
        actor: String,
        at: Instant,
    )

    /** [channelPseudonym] 의 mute audit 사건을 **시간순**으로 돌려준다(원문 없이 — 수준·actor·reason·시각). */
    fun auditFor(channelPseudonym: String): List<ChannelMuteAuditEvent>
}

/** 채널 mute audit 사건(append-only, 원문 비포함). */
data class ChannelMuteAuditEvent(
    val channelPseudonym: String,
    val action: ChannelMuteAction,
    /** 적용된 mute 수준(해제 사건이면 [ChannelMuteLevel.NONE]). */
    val level: ChannelMuteLevel,
    /** 발동/해제 주체(운영자 식별 코드 — 원문 user id 가 아니라 audit 식별자). */
    val actor: String,
    /** 발동 사유(저카디널리티 코드/짧은 설명 — 원문 대화 비포함). 해제 시 빈 문자열 허용. */
    val reason: String,
    /** mute 발동으로 취소된 pending 행동 수(해제 사건이면 0). */
    val cancelledPending: Int,
    val at: Instant,
)

/** 채널 mute audit 의 사건 종류. */
enum class ChannelMuteAction {
    MUTE,
    UNMUTE,
}
