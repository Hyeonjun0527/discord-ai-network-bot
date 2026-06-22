package com.discordassistant.central.participation.domain.model.config

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T002 participation feature flag 도메인 게이트 acceptance 단위 테스트.
 *
 * 핵심: **기본값 LEGACY/OFF 로 기존 동작 보존**(설정 없으면 NEXA 비활성), 채널 override·제외(kill switch) 우선순위.
 */
class NexaParticipationGateTest {
    @Test
    fun `acceptance — 설정이 없으면 OFF(LEGACY) — 기존 동작 보존`() {
        assertThat(NexaParticipationGate.resolve(channelId = 100L)).isEqualTo(ShadowMode.OFF)
        assertThat(NexaParticipationGate.isNexaActive(channelId = 100L)).isFalse()
        assertThat(ParticipationLane.DEFAULT).isEqualTo(ParticipationLane.LEGACY)
        assertThat(ParticipationLane.LEGACY.shadowMode).isEqualTo(ShadowMode.OFF)
    }

    @Test
    fun `lane 별 ShadowMode 매핑이 1대1 이다`() {
        assertThat(ParticipationLane.LEGACY.shadowMode).isEqualTo(ShadowMode.OFF)
        assertThat(ParticipationLane.SHADOW.shadowMode).isEqualTo(ShadowMode.SHADOW_PREDICT)
        assertThat(ParticipationLane.CANARY.shadowMode).isEqualTo(ShadowMode.CANARY)
        assertThat(ParticipationLane.LIVE.shadowMode).isEqualTo(ShadowMode.LIVE)
    }

    @Test
    fun `SHADOW 는 정책을 평가하되 실제 전송은 안 한다`() {
        assertThat(ParticipationLane.SHADOW.evaluatesPolicy).isTrue()
        assertThat(ParticipationLane.SHADOW.allowsRealSend).isFalse()
        assertThat(ParticipationLane.CANARY.allowsRealSend).isTrue()
        assertThat(ParticipationLane.LIVE.allowsRealSend).isTrue()
    }

    @Test
    fun `채널 override 가 길드 lane 을 이긴다`() {
        val mode =
            NexaParticipationGate.resolve(
                channelId = 100L,
                guildLane = ParticipationLane.LEGACY,
                channelOverride = ParticipationLane.SHADOW,
            )
        assertThat(mode).isEqualTo(ShadowMode.SHADOW_PREDICT)
    }

    @Test
    fun `제외 채널(kill switch)은 길드 LIVE 여도 항상 OFF`() {
        val mode =
            NexaParticipationGate.resolve(
                channelId = 100L,
                guildLane = ParticipationLane.LIVE,
                channelOverride = ParticipationLane.LIVE,
                excludedChannelIds = setOf(100L),
            )
        assertThat(mode).isEqualTo(ShadowMode.OFF)
        assertThat(
            NexaParticipationGate.isNexaActive(
                channelId = 100L,
                guildLane = ParticipationLane.LIVE,
                excludedChannelIds = setOf(100L),
            ),
        ).isFalse()
    }

    @Test
    fun `제외되지 않은 다른 채널은 길드 lane 을 따른다`() {
        val mode =
            NexaParticipationGate.resolve(
                channelId = 200L,
                guildLane = ParticipationLane.LIVE,
                excludedChannelIds = setOf(100L),
            )
        assertThat(mode).isEqualTo(ShadowMode.LIVE)
    }

    @Test
    fun `fromShadowMode 는 역매핑한다`() {
        assertThat(ParticipationLane.fromShadowMode(ShadowMode.OFF)).isEqualTo(ParticipationLane.LEGACY)
        // OFF/OBSERVE_ONLY 는 legacy lane 으로 정규화(매핑되는 lane 이 없으면 LEGACY 폴백).
        assertThat(ParticipationLane.fromShadowMode(ShadowMode.OBSERVE_ONLY)).isEqualTo(ParticipationLane.LEGACY)
        assertThat(ParticipationLane.fromShadowMode(ShadowMode.LIVE)).isEqualTo(ParticipationLane.LIVE)
    }
}
