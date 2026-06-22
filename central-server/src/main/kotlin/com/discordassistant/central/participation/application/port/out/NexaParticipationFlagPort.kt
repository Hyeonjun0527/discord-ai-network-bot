package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.domain.model.config.ParticipationLane

/**
 * 길드/채널별 NEXA participation flag(lane override·제외 채널) 영속 포트(NEXA-P15-T002, application 레이어).
 *
 * 길드 단위 lane 은 기존 [ShadowModeStorePort] 가 소유한다(P09-T007, V60). 이 포트는 그 위에 **채널 단위
 * override 와 kill-switch(제외 채널)** 만 추가로 저장한다 — 회귀 0 을 위해 기존 `nexa_shadow_mode` 행은 건드리지
 * 않고 별도 테이블(V65 additive)에 채널 설정을 둔다.
 *
 * **acceptance(T002) — 기본 migration 값이 legacy/OFF**: 행이 없으면 [channelOverride] 는 null(길드 lane 상속),
 * [excludedChannelIds] 는 빈 집합이다. 즉 아무 설정도 안 한 길드/채널은 LEGACY → 기존 동작만 산다.
 *
 * 순수성: application 레이어 — 도메인 타입·표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface NexaParticipationFlagPort {
    /** [guildPseudonym]/[channelId] 의 채널 lane override(없으면 null — 길드 lane 상속). */
    fun channelOverride(
        guildPseudonym: String,
        channelId: Long,
    ): ParticipationLane?

    /** [guildPseudonym] 의 NEXA 제외 채널 id 집합(kill switch — 항상 OFF). 없으면 빈 집합. */
    fun excludedChannelIds(guildPseudonym: String): Set<Long>

    /** 채널 lane override 를 저장한다(null 이면 override 제거 → 길드 lane 상속). */
    fun setChannelOverride(
        guildPseudonym: String,
        channelId: Long,
        lane: ParticipationLane?,
    )

    /** 채널을 NEXA 제외 목록에 추가/제거(kill switch). */
    fun setChannelExcluded(
        guildPseudonym: String,
        channelId: Long,
        excluded: Boolean,
    )
}
