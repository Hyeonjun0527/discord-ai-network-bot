package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.adapter.outbound.participation.ParticipationActionExecutionModeAdapter
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParticipationActionExecutionModeAdapterTest {
    @Test
    fun `채널 override 가 OFF 면 요청 모드 LIVE 보다 현재 OFF 가 우선한다`() {
        val flags = FakeFlagPort(overrides = mutableMapOf("g1" to mutableMapOf(100L to ParticipationLane.LEGACY)))
        val adapter =
            ParticipationActionExecutionModeAdapter(
                shadowModeStore = FakeModeStore(ShadowMode.LIVE),
                flagPort = flags,
                globalDefaultLaneName = "OFF",
            )

        val mode = adapter.currentMode(ActionTarget("g1", "100", "t1"), ShadowMode.LIVE)

        assertThat(mode).isEqualTo(ShadowMode.OFF)
    }

    @Test
    fun `숫자가 아닌 채널 target 은 현재 모드 조회 실패를 전송 허용으로 해석하지 않는다`() {
        val adapter =
            ParticipationActionExecutionModeAdapter(
                shadowModeStore = FakeModeStore(ShadowMode.LIVE),
                flagPort = FakeFlagPort(),
                globalDefaultLaneName = "LIVE",
            )

        val mode = adapter.currentMode(ActionTarget("g1", "not-a-channel-id", "t1"), ShadowMode.LIVE)

        assertThat(mode).isEqualTo(ShadowMode.OFF)
    }

    @Test
    fun `shadow origin은 채널이 LIVE로 승격돼도 전송 권한을 얻지 않는다`() {
        val adapter =
            ParticipationActionExecutionModeAdapter(
                shadowModeStore = FakeModeStore(ShadowMode.LIVE),
                flagPort = FakeFlagPort(),
                globalDefaultLaneName = "LIVE",
            )

        val mode = adapter.currentMode(ActionTarget("g1", "100", "t1"), ShadowMode.SHADOW_PREDICT)

        assertThat(mode).isEqualTo(ShadowMode.SHADOW_PREDICT)
    }

    @Test
    fun `CANARY origin과 LIVE current의 교집합은 CANARY를 유지한다`() {
        val adapter =
            ParticipationActionExecutionModeAdapter(
                shadowModeStore = FakeModeStore(ShadowMode.LIVE),
                flagPort = FakeFlagPort(),
                globalDefaultLaneName = "LIVE",
            )

        val mode = adapter.currentMode(ActionTarget("g1", "100", "t1"), ShadowMode.CANARY)

        assertThat(mode).isEqualTo(ShadowMode.CANARY)
    }

    private class FakeModeStore(
        private val mode: ShadowMode,
    ) : ShadowModeStorePort {
        override fun currentMode(guildPseudonym: String): ShadowMode = mode

        override fun applyTransition(audit: ShadowModeAudit) = Unit

        override fun auditTrail(guildPseudonym: String): List<ShadowModeAudit> = emptyList()

        override fun listModes(): List<ShadowModeState> = emptyList()
    }

    private class FakeFlagPort(
        private val overrides: MutableMap<String, MutableMap<Long, ParticipationLane>> = mutableMapOf(),
        private val excluded: MutableMap<String, MutableSet<Long>> = mutableMapOf(),
    ) : NexaParticipationFlagPort {
        override fun channelOverride(
            guildPseudonym: String,
            channelId: Long,
        ): ParticipationLane? = overrides[guildPseudonym]?.get(channelId)

        override fun excludedChannelIds(guildPseudonym: String): Set<Long> = excluded[guildPseudonym].orEmpty()

        override fun clearGuild(guildPseudonym: String) {
            overrides.remove(guildPseudonym)
            excluded.remove(guildPseudonym)
        }

        override fun setChannelOverride(
            guildPseudonym: String,
            channelId: Long,
            lane: ParticipationLane?,
        ) {
            if (lane == null) {
                overrides[guildPseudonym]?.remove(channelId)
            } else {
                overrides.getOrPut(guildPseudonym) { mutableMapOf() }[channelId] = lane
            }
        }

        override fun setChannelExcluded(
            guildPseudonym: String,
            channelId: Long,
            excluded: Boolean,
        ) {
            val channels = this.excluded.getOrPut(guildPseudonym) { mutableSetOf() }
            if (excluded) channels += channelId else channels -= channelId
        }
    }
}
