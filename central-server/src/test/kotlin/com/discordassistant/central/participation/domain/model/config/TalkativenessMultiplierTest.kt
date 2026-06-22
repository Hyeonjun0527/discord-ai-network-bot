package com.discordassistant.central.participation.domain.model.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P08-T017 talkativeness multiplier 계약 acceptance 단위 테스트. */
class TalkativenessMultiplierTest {
    @Test
    fun `T017 — 허용 범위는 0_0~2_0`() {
        assertThat(TalkativenessMultiplier(0.0).value).isEqualTo(0.0)
        assertThat(TalkativenessMultiplier(2.0).value).isEqualTo(2.0)
        assertThatThrownBy { TalkativenessMultiplier(-0.1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TalkativenessMultiplier(2.1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `T017 — 1_5 는 기본값 후보일 뿐 최종 기본은 인간 승인 대기`() {
        assertThat(TalkativenessMultiplier.DEFAULT_CANDIDATE).isEqualTo(1.5)
        // 잠정 기본은 "승인 대기" 진입점으로만 노출(무심코 영구 기본 굳히기 방지).
        assertThat(TalkativenessMultiplier.defaultPendingApproval().value).isEqualTo(1.5)
        // 코드의 보정-없음 중립값은 1.5 가 아니라 1.0 이다(1.5 자동 채택 아님).
        assertThat(TalkativenessMultiplier.NEUTRAL.value).isEqualTo(1.0)
    }

    @Test
    fun `T017 acceptance — 메시지 개수 곱이 아니라 speak logit 보정에만 쓰인다`() {
        val baseLogit = 0.0
        // multiplier 1.0 = 보정 없음.
        assertThat(TalkativenessMultiplier.NEUTRAL.applyToSpeakLogit(baseLogit)).isEqualTo(0.0)
        // >1 = 발화 쪽(양수 보정), <1 = 침묵 쪽(음수 보정).
        assertThat(TalkativenessMultiplier(2.0).applyToSpeakLogit(baseLogit)).isGreaterThan(0.0)
        assertThat(TalkativenessMultiplier(0.5).applyToSpeakLogit(baseLogit)).isLessThan(0.0)
        // 0 = 강한 침묵 편향(하한 클램프, -∞ 아님).
        assertThat(TalkativenessMultiplier(0.0).logitAdjustment())
            .isEqualTo(TalkativenessMultiplier.MIN_LOGIT_ADJUSTMENT)
    }
}
