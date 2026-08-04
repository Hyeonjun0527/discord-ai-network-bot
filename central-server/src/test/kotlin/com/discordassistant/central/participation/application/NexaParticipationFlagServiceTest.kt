package com.discordassistant.central.participation.application

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.participation.application.catchup.NiaCatchUpStateLifecycle
import com.discordassistant.central.participation.application.port.out.NexaParticipationConsentPort
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
        val service = service(FakeModeStore(ShadowMode.OFF), FakeFlagPort(), "OFF")
        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.OFF)
        assertThat(service.isNexaActive(guildId = 7L, channelId = 100L)).isFalse()
        assertThat(service.allowsRealSend(guildId = 7L, channelId = 100L)).isFalse()
    }

    @Test
    fun `길드 lane LIVE 이면 활성+전송 허용`() {
        val service = service(FakeModeStore(ShadowMode.LIVE), FakeFlagPort(), "OFF")
        assertThat(service.isNexaActive(guildId = 7L, channelId = 100L)).isTrue()
        assertThat(service.allowsRealSend(guildId = 7L, channelId = 100L)).isTrue()
    }

    @Test
    fun `길드 SHADOW 이면 활성이지만 전송은 차단`() {
        val service = service(FakeModeStore(ShadowMode.SHADOW_PREDICT), FakeFlagPort(), "OFF")
        assertThat(service.isNexaActive(guildId = 7L, channelId = 100L)).isTrue()
        assertThat(service.allowsRealSend(guildId = 7L, channelId = 100L)).isFalse()
    }

    @Test
    fun `채널 제외(kill switch)는 길드 LIVE 여도 OFF`() {
        val flagPort = FakeFlagPort(excluded = mapOf(100L to true))
        val service = service(FakeModeStore(ShadowMode.LIVE), flagPort, "OFF")
        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.OFF)
        // 제외 안 된 채널은 LIVE 유지.
        assertThat(service.effectiveMode(guildId = 7L, channelId = 200L)).isEqualTo(ShadowMode.LIVE)
    }

    @Test
    fun `채널 override 가 길드 lane 을 이긴다`() {
        val flagPort = FakeFlagPort(override = mapOf(100L to ParticipationLane.LIVE))
        val service = service(FakeModeStore(ShadowMode.OFF), flagPort, "OFF")
        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.LIVE)
    }

    @Test
    fun `글로벌 기본 lane SHADOW 면 행 없는 길드(OFF)도 SHADOW_PREDICT`() {
        // 길드 행 없음(currentMode=OFF) + 글로벌 기본 SHADOW → 평가·기록만, 전송은 차단.
        val service = service(FakeModeStore(ShadowMode.OFF), FakeFlagPort(), "SHADOW")
        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.SHADOW_PREDICT)
        assertThat(service.isNexaActive(guildId = 7L, channelId = 100L)).isTrue()
        assertThat(service.allowsRealSend(guildId = 7L, channelId = 100L)).isFalse()
    }

    @Test
    fun `글로벌 기본 SHADOW 여도 길드가 명시 LIVE 면 길드 설정 우선`() {
        // 길드 행이 OFF 아닌 명시값(LIVE)을 가지면 글로벌 기본보다 우선.
        val service = service(FakeModeStore(ShadowMode.LIVE), FakeFlagPort(), "SHADOW")
        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.LIVE)
    }

    @Test
    fun `우선순위 truth table — excluded 가 override 와 global LIVE 보다 항상 우선한다`() {
        val flagPort =
            FakeFlagPort(
                override =
                    mapOf(
                        100L to ParticipationLane.SHADOW,
                        200L to ParticipationLane.LIVE,
                        300L to ParticipationLane.CANARY,
                    ),
                excluded =
                    mapOf(
                        200L to true,
                        400L to true,
                    ),
            )
        val service = service(FakeModeStore(ShadowMode.OFF), flagPort, "LIVE")

        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.SHADOW_PREDICT)
        assertThat(service.effectiveMode(guildId = 7L, channelId = 200L)).isEqualTo(ShadowMode.OFF)
        assertThat(service.effectiveMode(guildId = 7L, channelId = 300L)).isEqualTo(ShadowMode.CANARY)
        assertThat(service.effectiveMode(guildId = 7L, channelId = 400L)).isEqualTo(ShadowMode.OFF)
        assertThat(service.effectiveMode(guildId = 7L, channelId = 500L)).isEqualTo(ShadowMode.LIVE)
    }

    @Test
    fun `채널 LIVE 활성화와 명시 비활성화는 글로벌 기본보다 우선한다`() {
        val flagPort = MutableFakeFlagPort()
        val service = service(FakeModeStore(ShadowMode.OFF), flagPort, "LIVE")

        service.disableChannel(guildId = 7L, channelId = 100L)

        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.OFF)

        service.enableChannelLive(guildId = 7L, channelId = 100L)

        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.LIVE)
    }

    @Test
    fun `채널 LIVE 활성화와 비활성화는 consent 범위를 갱신하고 stale CATCH_UP 상태를 비운다`() {
        val consent = RecordingConsentPort()
        val catchUpStates = RecordingCatchUpStateLifecycle()
        val service = service(FakeModeStore(ShadowMode.OFF), MutableFakeFlagPort(), "OFF", consent, catchUpStates)

        service.enableChannelLive(guildId = 7L, channelId = 100L, actorId = 42L)
        service.disableChannel(guildId = 7L, channelId = 100L)
        service.cleanupChannel(guildId = 7L, channelId = 100L)
        service.cleanupGuild(guildId = 7L)

        assertThat(consent.activations).containsExactly(Activation(7L, 100L, 42L))
        assertThat(consent.deactivations).containsExactly(7L to 100L)
        assertThat(consent.clearedChannels).containsExactly(7L to 100L)
        assertThat(consent.revokedGuilds).containsExactly(7L)
        assertThat(catchUpStates.clearedChannels).containsExactly(7L to 100L, 7L to 100L, 7L to 100L)
        assertThat(catchUpStates.clearedGuilds).containsExactly(7L)
    }

    @Test
    fun `채널 participation enable disable 은 actor source mode 를 같은 audit 형식으로 기록한다`() {
        val audit = AuditLog()
        val service =
            NexaParticipationFlagService(
                FakeModeStore(ShadowMode.OFF),
                MutableFakeFlagPort(),
                NexaParticipationConsentPort.Noop,
                "OFF",
                audit,
                NiaCatchUpStateLifecycle.Noop,
            )

        service.enableChannelLive(
            guildId = 7L,
            channelId = 100L,
            actorId = 42L,
            source = NexaParticipationFlagService.SOURCE_NIA_SETUP,
        )
        service.disableChannel(
            guildId = 7L,
            channelId = 100L,
            actorId = 43L,
            source = NexaParticipationFlagService.SOURCE_GUILD_ADMIN_TOGGLE,
        )

        assertThat(audit.all())
            .extracting("action")
            .containsExactly("nexa_participation_channel_enabled", "nexa_participation_channel_disabled")
        assertThat(audit.all()[0].actor).isEqualTo("admin:42")
        assertThat(audit.all()[0].target).isEqualTo("guild:7")
        assertThat(audit.all()[0].detail).isEqualTo("channel:100 source:nia_setup mode:LIVE")
        assertThat(audit.all()[1].actor).isEqualTo("admin:43")
        assertThat(audit.all()[1].target).isEqualTo("guild:7")
        assertThat(audit.all()[1].detail).isEqualTo("channel:100 source:guild_admin_toggle mode:OFF")
    }

    @Test
    fun `채널 삭제 cleanup 은 override 와 kill-switch 를 모두 제거한다`() {
        val flagPort = MutableFakeFlagPort()
        val service = service(FakeModeStore(ShadowMode.OFF), flagPort, "OFF")

        service.enableChannelLive(guildId = 7L, channelId = 100L)
        service.disableChannel(guildId = 7L, channelId = 200L)

        service.cleanupChannel(guildId = 7L, channelId = 100L)
        service.cleanupChannel(guildId = 7L, channelId = 200L)

        assertThat(flagPort.channelOverride("unused", 100L)).isNull()
        assertThat(flagPort.channelOverride("unused", 200L)).isNull()
        assertThat(flagPort.excludedChannelIds("unused")).doesNotContain(100L, 200L)
        assertThat(service.effectiveMode(guildId = 7L, channelId = 100L)).isEqualTo(ShadowMode.OFF)
        assertThat(service.effectiveMode(guildId = 7L, channelId = 200L)).isEqualTo(ShadowMode.OFF)
    }

    private fun service(
        modeStore: ShadowModeStorePort,
        flagPort: NexaParticipationFlagPort,
        globalDefaultLane: String,
        consentPort: NexaParticipationConsentPort = NexaParticipationConsentPort.Noop,
        catchUpStateLifecycle: NiaCatchUpStateLifecycle = NiaCatchUpStateLifecycle.Noop,
    ): NexaParticipationFlagService =
        NexaParticipationFlagService(modeStore, flagPort, consentPort, globalDefaultLane, catchUpStateLifecycle = catchUpStateLifecycle)

    private data class Activation(
        val guildId: Long,
        val channelId: Long,
        val actorId: Long?,
    )

    private class RecordingConsentPort : NexaParticipationConsentPort {
        val activations = mutableListOf<Activation>()
        val deactivations = mutableListOf<Pair<Long, Long>>()
        val clearedChannels = mutableListOf<Pair<Long, Long>>()
        val revokedGuilds = mutableListOf<Long>()

        override fun activateMemberChannel(
            guildId: Long,
            channelId: Long,
            actorId: Long?,
        ) {
            activations += Activation(guildId, channelId, actorId)
        }

        override fun deactivateMemberChannel(
            guildId: Long,
            channelId: Long,
        ) {
            deactivations += guildId to channelId
        }

        override fun clearChannel(
            guildId: Long,
            channelId: Long,
        ) {
            clearedChannels += guildId to channelId
        }

        override fun revokeGuild(guildId: Long) {
            revokedGuilds += guildId
        }
    }

    private class RecordingCatchUpStateLifecycle : NiaCatchUpStateLifecycle {
        val clearedChannels = mutableListOf<Pair<Long, Long>>()
        val clearedGuilds = mutableListOf<Long>()

        override fun clearChannel(
            guildId: Long,
            channelId: Long,
        ) {
            clearedChannels += guildId to channelId
        }

        override fun clearGuild(guildId: Long) {
            clearedGuilds += guildId
        }
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

        override fun clearGuild(guildPseudonym: String) = error("not used")

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

    private class MutableFakeFlagPort : NexaParticipationFlagPort {
        private val overrides = mutableMapOf<Long, ParticipationLane?>()
        private val excluded = mutableSetOf<Long>()

        override fun channelOverride(
            guildPseudonym: String,
            channelId: Long,
        ): ParticipationLane? = overrides[channelId]

        override fun excludedChannelIds(guildPseudonym: String): Set<Long> = excluded.toSet()

        override fun clearGuild(guildPseudonym: String) {
            overrides.clear()
            excluded.clear()
        }

        override fun setChannelOverride(
            guildPseudonym: String,
            channelId: Long,
            lane: ParticipationLane?,
        ) {
            overrides[channelId] = lane
        }

        override fun setChannelExcluded(
            guildPseudonym: String,
            channelId: Long,
            excluded: Boolean,
        ) {
            if (excluded) {
                this.excluded += channelId
            } else {
                this.excluded -= channelId
            }
        }

        override fun clearChannel(
            guildPseudonym: String,
            channelId: Long,
        ) {
            overrides.remove(channelId)
            excluded.remove(channelId)
        }
    }
}
