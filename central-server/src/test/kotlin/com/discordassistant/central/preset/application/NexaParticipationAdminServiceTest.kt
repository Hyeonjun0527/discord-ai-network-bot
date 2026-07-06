package com.discordassistant.central.preset.application

import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowApprovalAuthority
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P15-T013 participation 관리자 명령 통합 서비스 acceptance 단위 테스트.
 *
 * 핵심 acceptance: **권한 없으면 변경 거부(fail-closed), 변경 시 audit 가 남는다.**
 */
class NexaParticipationAdminServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)
    private val manage = ShadowApprovalAuthority(canManageShadow = true, canEnableRealSend = false)
    private val realSend = ShadowApprovalAuthority(canManageShadow = true, canEnableRealSend = true)

    @Test
    fun `acceptance — shadow 영역 전이는 운영 권한으로 가능하고 audit 가 남는다`() {
        val store = FakeModeStore()
        val service = NexaParticipationAdminService(store, FakeFlagPort(), clock)

        service.setGuildLane("g-1", ParticipationLane.SHADOW, manage, actorId = "op-1", reason = "예측 수집")

        assertThat(store.currentMode("g-1")).isEqualTo(ShadowMode.SHADOW_PREDICT)
        assertThat(store.audits).hasSize(1)
        assertThat(store.audits.first().to).isEqualTo(ShadowMode.SHADOW_PREDICT)
    }

    @Test
    fun `acceptance — 권한 없으면 lane 전이 거부(fail-closed)`() {
        val store = FakeModeStore()
        val service = NexaParticipationAdminService(store, FakeFlagPort(), clock)

        assertThatThrownBy {
            service.setGuildLane("g-1", ParticipationLane.SHADOW, ShadowApprovalAuthority.NONE, "op-1", "x")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.audits).isEmpty() // audit 도 남지 않는다
    }

    @Test
    fun `실제 전송 진입(LIVE)은 더 강한 권한을 요구한다`() {
        val store = FakeModeStore()
        val service = NexaParticipationAdminService(store, FakeFlagPort(), clock)

        // manage 권한만으로 LIVE 진입 시도 → 거부.
        assertThatThrownBy {
            service.setGuildLane("g-1", ParticipationLane.LIVE, manage, "op-1", "켜기")
        }.isInstanceOf(IllegalArgumentException::class.java)

        // realSend 권한이면 가능.
        service.setGuildLane("g-1", ParticipationLane.LIVE, realSend, "op-1", "켜기")
        assertThat(store.currentMode("g-1")).isEqualTo(ShadowMode.LIVE)
    }

    @Test
    fun `채널 제외(kill switch)는 운영 권한 필요`() {
        val flag = FakeFlagPort()
        val service = NexaParticipationAdminService(FakeModeStore(), flag, clock)

        assertThatThrownBy {
            service.setChannelExcluded("g-1", 100L, true, ShadowApprovalAuthority.NONE)
        }.isInstanceOf(IllegalArgumentException::class.java)

        service.setChannelExcluded("g-1", 100L, true, manage)
        assertThat(flag.excluded).containsEntry(100L, true)
    }

    @Test
    fun `채널 lane override LIVE 는 실제 전송 권한 필요`() {
        val service = NexaParticipationAdminService(FakeModeStore(), FakeFlagPort(), clock)
        assertThatThrownBy {
            service.setChannelLane("g-1", 100L, ParticipationLane.LIVE, manage)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private class FakeModeStore : ShadowModeStorePort {
        private val modes = mutableMapOf<String, ShadowMode>()
        val audits = mutableListOf<ShadowModeAudit>()

        override fun currentMode(guildPseudonym: String): ShadowMode = modes[guildPseudonym] ?: ShadowMode.OFF

        override fun applyTransition(audit: ShadowModeAudit) {
            modes[audit.guildPseudonym] = audit.to
            audits.add(audit)
        }

        override fun auditTrail(guildPseudonym: String): List<ShadowModeAudit> = audits.filter { it.guildPseudonym == guildPseudonym }

        override fun listModes(): List<ShadowModeState> = emptyList()
    }

    private class FakeFlagPort : NexaParticipationFlagPort {
        val override = mutableMapOf<Long, ParticipationLane?>()
        val excluded = mutableMapOf<Long, Boolean>()

        override fun channelOverride(
            guildPseudonym: String,
            channelId: Long,
        ): ParticipationLane? = override[channelId]

        override fun excludedChannelIds(guildPseudonym: String): Set<Long> = excluded.filterValues { it }.keys

        override fun clearGuild(guildPseudonym: String) {
            override.clear()
            excluded.clear()
        }

        override fun setChannelOverride(
            guildPseudonym: String,
            channelId: Long,
            lane: ParticipationLane?,
        ) {
            override[channelId] = lane
        }

        override fun setChannelExcluded(
            guildPseudonym: String,
            channelId: Long,
            excluded: Boolean,
        ) {
            this.excluded[channelId] = excluded
        }
    }
}
