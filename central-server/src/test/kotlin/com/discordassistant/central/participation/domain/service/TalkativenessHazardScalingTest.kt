package com.discordassistant.central.participation.domain.service

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.config.TalkativenessMultiplier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** NEXA-P12-T009 talkativeness hazard scaling acceptance 단위 테스트 — 순서 보존·cap·대상 한정. */
class TalkativenessHazardScalingTest {
    @Test
    fun `T009 acceptance — 0_5 1_0 1_5 2_0 에서 hazard 순서가 보존된다`() {
        val base = 0.3
        val h05 = TalkativenessHazardScaling.scaleHazard(base, TalkativenessMultiplier(0.5))
        val h10 = TalkativenessHazardScaling.scaleHazard(base, TalkativenessMultiplier(1.0))
        val h15 = TalkativenessHazardScaling.scaleHazard(base, TalkativenessMultiplier(1.5))
        val h20 = TalkativenessHazardScaling.scaleHazard(base, TalkativenessMultiplier(2.0))

        // multiplier 가 클수록 hazard 가 (cap 전까지) 단조 증가.
        assertThat(h05).isLessThan(h10)
        assertThat(h10).isLessThan(h15)
        assertThat(h15).isLessThan(h20)
        // multiplier 1.0 = 보정 없음(원 hazard 유지).
        assertThat(h10).isCloseTo(base, within(1e-9))
    }

    @Test
    fun `T009 — hazard 는 HAZARD_CAP 으로 클램프돼 1 을 넘지 않는다`() {
        // 이미 높은 hazard 에 2.0 을 곱해도 cap 을 넘지 않는다(과도 끼어들기 방지).
        val high = 0.99
        val scaled = TalkativenessHazardScaling.scaleHazard(high, TalkativenessMultiplier(2.0))
        assertThat(scaled).isLessThanOrEqualTo(TalkativenessHazardScaling.HAZARD_CAP)
        assertThat(scaled).isLessThan(1.0)
    }

    @Test
    fun `T009 — multiplier 0 은 강한 침묵 편향(hazard 거의 0)이나 음수는 아니다`() {
        val scaled = TalkativenessHazardScaling.scaleHazard(0.5, TalkativenessMultiplier(0.0))
        assertThat(scaled).isGreaterThanOrEqualTo(0.0)
        assertThat(scaled).isLessThan(0.5)
    }

    @Test
    fun `T009 acceptance — SPEAK REACT hazard 에만 적용하고 다른 action 은 건드리지 않는다`() {
        val hazards =
            mapOf(
                SocialActionKind.SPEAK to 0.3,
                SocialActionKind.REACT to 0.3,
                SocialActionKind.IGNORE to 0.3,
                SocialActionKind.WAIT to 0.3,
            )
        val scaled = TalkativenessHazardScaling.scaleActionHazards(hazards, TalkativenessMultiplier(2.0))

        // SPEAK/REACT 는 증가, IGNORE/WAIT 는 불변.
        assertThat(scaled[SocialActionKind.SPEAK]!!).isGreaterThan(0.3)
        assertThat(scaled[SocialActionKind.REACT]!!).isGreaterThan(0.3)
        assertThat(scaled[SocialActionKind.IGNORE]!!).isEqualTo(0.3)
        assertThat(scaled[SocialActionKind.WAIT]!!).isEqualTo(0.3)
    }

    @Test
    fun `T009 — 확률 범위 밖 hazard 는 거부한다`() {
        assertThatThrownBy {
            TalkativenessHazardScaling.scaleHazard(1.5, TalkativenessMultiplier.NEUTRAL)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
