package com.discordassistant.central.participation.application

import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T002 flag 해석 application 서비스 acceptance 단위 테스트(fake 포트).
 *
 * 핵심 acceptance: **flag 미설정 = OFF(legacy) → isNexaActive=false** (기존 channelai 자동응답만 동작, 회귀 0).
 */
class NexaParticipationFlagServiceTest {
    @Test
    fun `acceptance — 아무 설정도 없으면 OFF 이고 NEXA 비활성(legacy)`() {
        val service = NexaParticipationFlagService(FakeModeStore(ShadowMode.OFF), FakeFlagPort())
        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.OFF)
        assertThat(service.isNexaActive(guildId = 7L, channelId = 100L)).isFalse()
        assertThat(service.allowsRealSend(guildId = 7L, channelId = 100L)).isFalse()
    }

    @Test
    fun `길드 lane LIVE 이면 활성+전송 허용`() {
        val service = NexaParticipationFlagService(FakeModeStore(ShadowMode.LIVE), FakeFlagPort())
        assertThat(service.isNexaActive(guildId = 7L, channelId = 100L)).isTrue()
        assertThat(service.allowsRealSend(guildId = 7L, channelId = 100L)).isTrue()
    }

    @Test
    fun `길드 SHADOW 이면 활성이지만 전송은 차단`() {
        val service = NexaParticipationFlagService(FakeModeStore(ShadowMode.SHADOW_PREDICT), FakeFlagPort())
        assertThat(service.isNexaActive(guildId = 7L, channelId = 100L)).isTrue()
        assertThat(service.allowsRealSend(guildId = 7L, channelId = 100L)).isFalse()
    }

    @Test
    fun `채널 제외(kill switch)는 길드 LIVE 여도 OFF`() {
        val flagPort = FakeFlagPort(excluded = mapOf(100L to true))
        val service = NexaParticipationFlagService(FakeModeStore(ShadowMode.LIVE), flagPort)
        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.OFF)
        // 제외 안 된 채널은 LIVE 유지.
        assertThat(service.effectiveMode(guildId = 7L, channelId = 200L)).isEqualTo(ShadowMode.LIVE)
    }

    @Test
    fun `채널 override 가 길드 lane 을 이긴다`() {
        val flagPort = FakeFlagPort(override = mapOf(100L to ParticipationLane.LIVE))
        val service = NexaParticipationFlagService(FakeModeStore(ShadowMode.OFF), flagPort)
        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.LIVE)
    }

    private class FakeModeStore(
        private val mode: ShadowMode,
    ) : ShadowModeStorePort {
        override fun currentMode(guildPseudonym: String): ShadowMode = mode

        override fun applyTransition(audit: ShadowModeAudit) = error("not used")

        override fun auditTrail(guildPseudonym: String): List<ShadowModeAudit> = emptyList()

        override fun listModes(): List<ShadowModeState> = emptyList()
    }

    private class FakeFlagPort(
        private val override: Map<Long, ParticipationLane> = emptyMap(),
        private val excluded: Map<Long, Boolean> = emptyMap(),
    ) : NexaParticipationFlagPort {
        override fun channelOverride(
            guildPseudonym: String,
            channelId: Long,
        ): ParticipationLane? = override[channelId]

        override fun excludedChannelIds(guildPseudonym: String): Set<Long> = excluded.filterValues { it }.keys

        override fun setChannelOverride(
            guildPseudonym: String,
            channelId: Long,
            lane: ParticipationLane?,
        ) = error("not used")

        override fun setChannelExcluded(
            guildPseudonym: String,
            channelId: Long,
            excluded: Boolean,
        ) = error("not used")
    }
}
