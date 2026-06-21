package com.discordassistant.central.licensing

import com.discordassistant.central.licensing.domain.model.Entitlement
import com.discordassistant.central.licensing.domain.model.LicenseStatus
import com.discordassistant.central.licensing.domain.model.NexaFeatureEntitlement
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.preset.application.NexaLicenseLaneGate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T015 — licensing feature gate acceptance.
 *
 * acceptance: **라이선스 만료가 갑자기 legacy mention-always 동작으로 바뀌지 않는다** + safety/삭제/kill switch 는
 * 항상 제공된다. 실제 발화(CANARY/LIVE)는 유료/체험일 때만, 만료/정지면 SHADOW 로 다운그레이드(LEGACY 아님).
 */
class NexaLicenseGateTest {
    @Test
    fun `유료·체험은 실제 전송 권한이 있고 만료·정지는 없다`() {
        assertThat(NexaFeatureEntitlement.from(entitlement(LicenseStatus.TRIAL)).realSendAllowed).isTrue()
        assertThat(NexaFeatureEntitlement.from(entitlement(LicenseStatus.LICENSED)).realSendAllowed).isTrue()
        assertThat(NexaFeatureEntitlement.from(entitlement(LicenseStatus.EVENT_FREE)).realSendAllowed).isTrue()
        assertThat(NexaFeatureEntitlement.from(entitlement(LicenseStatus.EXPIRED)).realSendAllowed).isFalse()
        assertThat(NexaFeatureEntitlement.from(entitlement(LicenseStatus.REVOKED)).realSendAllowed).isFalse()
    }

    @Test
    fun `관찰·shadow 와 safety 제어는 라이선스 상태와 무관하게 항상 제공된다`() {
        for (status in LicenseStatus.entries) {
            val e = NexaFeatureEntitlement.from(entitlement(status))
            assertThat(e.observeAndShadowAllowed).`as`("$status observe/shadow").isTrue()
            assertThat(e.safetyControlsAlwaysAvailable).`as`("$status safety").isTrue()
        }
    }

    @Test
    fun `만료면 LIVE·CANARY 요청이 SHADOW 로 다운그레이드되고 LEGACY 로 바뀌지 않는다`() {
        val expired = NexaFeatureEntitlement.from(entitlement(LicenseStatus.EXPIRED))
        assertThat(NexaLicenseLaneGate.capLane(ParticipationLane.LIVE, expired)).isEqualTo(ParticipationLane.SHADOW)
        assertThat(NexaLicenseLaneGate.capLane(ParticipationLane.CANARY, expired)).isEqualTo(ParticipationLane.SHADOW)
        // 핵심: LEGACY(기존 mention-always)로 떨어지지 않는다.
        assertThat(NexaLicenseLaneGate.capLane(ParticipationLane.LIVE, expired)).isNotEqualTo(ParticipationLane.LEGACY)
    }

    @Test
    fun `안전 방향(LEGACY·SHADOW)은 라이선스와 무관하게 그대로 통과한다`() {
        val expired = NexaFeatureEntitlement.from(entitlement(LicenseStatus.EXPIRED))
        assertThat(NexaLicenseLaneGate.capLane(ParticipationLane.LEGACY, expired)).isEqualTo(ParticipationLane.LEGACY)
        assertThat(NexaLicenseLaneGate.capLane(ParticipationLane.SHADOW, expired)).isEqualTo(ParticipationLane.SHADOW)
    }

    @Test
    fun `유료면 LIVE 요청이 그대로 통과한다`() {
        val licensed = NexaFeatureEntitlement.from(entitlement(LicenseStatus.LICENSED))
        assertThat(NexaLicenseLaneGate.capLane(ParticipationLane.LIVE, licensed)).isEqualTo(ParticipationLane.LIVE)
    }

    private fun entitlement(status: LicenseStatus): Entitlement = Entitlement(status = status, trialEndsAt = null)
}
