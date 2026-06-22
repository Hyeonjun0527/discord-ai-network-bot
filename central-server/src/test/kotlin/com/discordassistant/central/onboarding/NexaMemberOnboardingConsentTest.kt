package com.discordassistant.central.onboarding

import com.discordassistant.central.onboarding.domain.model.NexaMemberOnboardingConsent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T014 — onboarding NEXA 멤버 채널 동의의 목적별 독립성 acceptance.
 *
 * acceptance: **한 번의 포괄 동의로 모든 목적을 묶지 않는다.** 4개 축(관찰 범위/외부 GLM/shadow·live/학습)은
 * 각각 독립이며, 한 축을 켜도 다른 축이 자동으로 켜지지 않는다. 기본값은 모두 꺼짐(fail-closed).
 */
class NexaMemberOnboardingConsentTest {
    @Test
    fun `기본값은 모든 목적이 꺼져 있다(봇 추가·버튼 클릭만으로는 어떤 목적도 켜지지 않음)`() {
        val none = NexaMemberOnboardingConsent.NONE
        assertThat(none.observeScope).isFalse()
        assertThat(none.externalGlmAllowed).isFalse()
        assertThat(none.liveSendAllowed).isFalse()
        assertThat(none.learningOptIn).isFalse()
        assertThat(none.anyOptedIn).isFalse()
    }

    @Test
    fun `한 축을 켜도 다른 축은 자동으로 켜지지 않는다(포괄 동의 아님)`() {
        // 관찰만 동의 — GLM·실제 전송·학습은 여전히 꺼짐.
        val observeOnly = NexaMemberOnboardingConsent(observeScope = true)
        assertThat(observeOnly.observeScope).isTrue()
        assertThat(observeOnly.externalGlmAllowed).isFalse()
        assertThat(observeOnly.liveSendAllowed).isFalse()
        assertThat(observeOnly.learningOptIn).isFalse()

        // 학습만 별도로 동의 가능(관찰 없이도 — 학습 축은 다른 축과 완전 독립).
        val learningOnly = NexaMemberOnboardingConsent(learningOptIn = true)
        assertThat(learningOnly.learningOptIn).isTrue()
        assertThat(learningOnly.observeScope).isFalse()
    }

    @Test
    fun `각 목적을 따로 켤 수 있다`() {
        val all = NexaMemberOnboardingConsent(observeScope = true, externalGlmAllowed = true, liveSendAllowed = true, learningOptIn = true)
        assertThat(listOf(all.observeScope, all.externalGlmAllowed, all.liveSendAllowed, all.learningOptIn))
            .containsExactly(true, true, true, true)
    }

    @Test
    fun `안전 제약 — 관찰 없이 외부 GLM·실제 전송을 켤 수 없다(제약일 뿐 권한 부여 아님)`() {
        assertThatThrownBy { NexaMemberOnboardingConsent(observeScope = false, externalGlmAllowed = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NexaMemberOnboardingConsent(observeScope = false, liveSendAllowed = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
