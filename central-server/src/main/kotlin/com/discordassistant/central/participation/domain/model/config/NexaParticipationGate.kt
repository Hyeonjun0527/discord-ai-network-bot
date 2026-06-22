package com.discordassistant.central.participation.domain.model.config

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode

/**
 * 길드/채널별 NEXA participation 게이트 해석(NEXA-P15-T002, 순수 도메인 서비스·불변 입력).
 *
 * 길드 lane + (선택) 채널 override + 제외 채널 집합을 받아, 한 (guild, channel)에 대한 **유효 [ShadowMode]** 를
 * 결정한다. 이 값을 P15 파이프라인(T004~T006)이 보고 "NEXA 를 돌릴지/전송할지"를 정한다.
 *
 * **acceptance(T002) — 기본값이 legacy/OFF 로 기존 동작 보존**: 아무 설정도 없으면 [resolve] 는
 * [ShadowMode.OFF] 를 돌려준다(LEGACY). 즉 flag 미설정 길드/채널은 NEXA 가 비활성이고 기존 channelai 자동응답만
 * 동작한다. 회귀 0 의 도메인 보증.
 *
 * **해석 규칙(우선순위)**:
 *  1. 채널이 제외 목록([excludedChannelIds])에 있으면 항상 [ShadowMode.OFF](kill switch — T013).
 *  2. 채널 override 가 있으면 그 lane 의 [ShadowMode].
 *  3. 없으면 길드 lane 의 [ShadowMode].
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 타입·표준 컬렉션만.
 */
object NexaParticipationGate {
    /**
     * ([guildLane], [channelOverride], [excludedChannelIds])로 [channelId] 의 유효 [ShadowMode] 를 해석한다.
     * 어떤 입력도 없으면 [ShadowMode.OFF](LEGACY) — fail-safe 기본.
     */
    fun resolve(
        channelId: Long,
        guildLane: ParticipationLane = ParticipationLane.DEFAULT,
        channelOverride: ParticipationLane? = null,
        excludedChannelIds: Set<Long> = emptySet(),
    ): ShadowMode {
        if (channelId in excludedChannelIds) return ShadowMode.OFF // kill switch 우선
        return (channelOverride ?: guildLane).shadowMode
    }

    /**
     * 이 (guild, channel)에서 NEXA participation 이 **활성**인가(정책 평가 단계 이상). false 면 기존 channelai
     * 자동응답 경로만 동작한다(LEGACY). P15 파이프라인 진입 가드용.
     */
    fun isNexaActive(
        channelId: Long,
        guildLane: ParticipationLane = ParticipationLane.DEFAULT,
        channelOverride: ParticipationLane? = null,
        excludedChannelIds: Set<Long> = emptySet(),
    ): Boolean = resolve(channelId, guildLane, channelOverride, excludedChannelIds).evaluatesPolicy
}
