package com.discordassistant.central.domain

import com.discordassistant.central.conversation.domain.model.ConsentDecision
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * conversation 동의 결정 도메인 타입([ConsentDecision]) 단위 테스트.
 *
 * 2축(관찰/발화) 불변식과 사전정의 상수의 의미를 검증한다(consent-model / user-opt-out / channel-scope).
 */
class ConsentDecisionDomainTest {
    @Test
    fun `DENIED 는 관찰도 발화도 금지한다(fail-closed 기본값)`() {
        assertAll(
            { assertFalse(ConsentDecision.DENIED.observationAllowed) },
            { assertFalse(ConsentDecision.DENIED.speechAllowed) },
        )
    }

    @Test
    fun `OBSERVE_ONLY 는 관찰만 허용하고 발화는 금지한다`() {
        assertAll(
            { assertTrue(ConsentDecision.OBSERVE_ONLY.observationAllowed) },
            { assertFalse(ConsentDecision.OBSERVE_ONLY.speechAllowed) },
        )
    }

    @Test
    fun `OBSERVE_AND_SPEAK 는 관찰과 발화를 모두 허용한다`() {
        assertAll(
            { assertTrue(ConsentDecision.OBSERVE_AND_SPEAK.observationAllowed) },
            { assertTrue(ConsentDecision.OBSERVE_AND_SPEAK.speechAllowed) },
        )
    }

    @Test
    fun `관찰 없이 발화 허용은 불변식 위반으로 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConsentDecision(observationAllowed = false, speechAllowed = true)
        }
    }
}
