package com.discordassistant.central.participation.domain.model.shadow

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * ShadowMode 설정 모델·전이(NEXA-P09-T007) acceptance 단위 테스트.
 */
class ShadowModeTransitionTest {
    private val at = Instant.parse("2026-06-22T00:00:00Z")
    private val operator = ShadowApprovalAuthority(canManageShadow = true, canEnableRealSend = false)
    private val approver = ShadowApprovalAuthority(canManageShadow = true, canEnableRealSend = true)

    @Test
    fun `acceptance — 기본값은 OFF`() {
        assertThat(ShadowMode.DEFAULT).isEqualTo(ShadowMode.OFF)
    }

    @Test
    fun `OFF~SHADOW_PREDICT 는 실제 전송을 허용하지 않는다(미발화)`() {
        assertThat(ShadowMode.OFF.allowsRealSend).isFalse()
        assertThat(ShadowMode.OBSERVE_ONLY.allowsRealSend).isFalse()
        assertThat(ShadowMode.SHADOW_PREDICT.allowsRealSend).isFalse()
        assertThat(ShadowMode.CANARY.allowsRealSend).isTrue()
        assertThat(ShadowMode.LIVE.allowsRealSend).isTrue()
    }

    @Test
    fun `SHADOW_PREDICT 부터 정책을 평가한다`() {
        assertThat(ShadowMode.OFF.evaluatesPolicy).isFalse()
        assertThat(ShadowMode.OBSERVE_ONLY.evaluatesPolicy).isFalse()
        assertThat(ShadowMode.SHADOW_PREDICT.evaluatesPolicy).isTrue()
    }

    @Test
    fun `acceptance — 상태 전이는 승인 권한을 요구한다(권한 없으면 거부)`() {
        assertThatThrownBy {
            ShadowModeTransition.transition(
                from = ShadowMode.OFF,
                to = ShadowMode.OBSERVE_ONLY,
                authority = ShadowApprovalAuthority.NONE,
                guildPseudonym = "g-1",
                actorId = "op-1",
                reason = "관찰 시작",
                at = at,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `acceptance — 성공 전이는 audit 를 만든다`() {
        val audit =
            ShadowModeTransition.transition(
                from = ShadowMode.OFF,
                to = ShadowMode.SHADOW_PREDICT,
                authority = operator,
                guildPseudonym = "g-1",
                actorId = "op-1",
                reason = "shadow 예측 수집",
                at = at,
            )
        assertThat(audit.from).isEqualTo(ShadowMode.OFF)
        assertThat(audit.to).isEqualTo(ShadowMode.SHADOW_PREDICT)
        assertThat(audit.actorId).isEqualTo("op-1")
        assertThat(audit.enabledRealSend).isFalse()
    }

    @Test
    fun `실제 전송 활성화 상향은 더 강한 권한을 요구한다`() {
        // 일반 운영 권한으로 SHADOW_PREDICT→CANARY(실제 전송 켜기)는 거부.
        assertThatThrownBy {
            ShadowModeTransition.transition(
                from = ShadowMode.SHADOW_PREDICT,
                to = ShadowMode.CANARY,
                authority = operator,
                guildPseudonym = "g-1",
                actorId = "op-1",
                reason = "canary 시작",
                at = at,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        // 강한 승인 권한이면 허용 + enabledRealSend=true 기록.
        val audit =
            ShadowModeTransition.transition(
                from = ShadowMode.SHADOW_PREDICT,
                to = ShadowMode.CANARY,
                authority = approver,
                guildPseudonym = "g-1",
                actorId = "approver-1",
                reason = "canary 시작",
                at = at,
            )
        assertThat(audit.enabledRealSend).isTrue()
    }

    @Test
    fun `같은 단계로의 전이는 금지된다`() {
        assertThatThrownBy {
            ShadowModeTransition.transition(
                from = ShadowMode.OFF,
                to = ShadowMode.OFF,
                authority = operator,
                guildPseudonym = "g-1",
                actorId = "op-1",
                reason = "noop",
                at = at,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
