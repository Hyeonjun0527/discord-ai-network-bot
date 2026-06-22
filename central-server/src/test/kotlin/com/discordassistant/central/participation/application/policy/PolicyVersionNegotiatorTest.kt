package com.discordassistant.central.participation.application.policy

import com.discordassistant.central.participation.application.port.out.PolicyEngineCapabilities
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P08-T008 정책 계약 버전 협상의 acceptance 단위 테스트. */
class PolicyVersionNegotiatorTest {
    private val caps =
        PolicyEngineCapabilities(
            supportedSchemaVersions = setOf(1, 2),
            supportedModelVersions = setOf("rules-1", "onnx-3"),
        )

    @Test
    fun `호환되면 ACTIVE 다`() {
        val mode = PolicyVersionNegotiator.negotiate(caps, requestedSchemaVersion = 1, requestedModelVersion = "rules-1")
        assertThat(mode).isEqualTo(PolicyEngineMode.ACTIVE)
        assertThat(mode.appliesToBehavior).isTrue()
    }

    @Test
    fun `acceptance — model 비호환은 SHADOW_ONLY 로 전환된다`() {
        val mode = PolicyVersionNegotiator.negotiate(caps, requestedSchemaVersion = 2, requestedModelVersion = "future-9")
        assertThat(mode).isEqualTo(PolicyEngineMode.SHADOW_ONLY)
        assertThat(mode.appliesToBehavior).isFalse()
    }

    @Test
    fun `acceptance — schema 비호환은 FALLBACK 으로 전환된다`() {
        val mode = PolicyVersionNegotiator.negotiate(caps, requestedSchemaVersion = 99, requestedModelVersion = "rules-1")
        assertThat(mode).isEqualTo(PolicyEngineMode.FALLBACK)
        assertThat(mode.appliesToBehavior).isFalse()
    }

    @Test
    fun `model 무관(null) 은 schema 만 맞으면 ACTIVE 다`() {
        val mode = PolicyVersionNegotiator.negotiate(caps, requestedSchemaVersion = 1, requestedModelVersion = null)
        assertThat(mode).isEqualTo(PolicyEngineMode.ACTIVE)
    }

    @Test
    fun `능력 미상(빈 schema 집합) 은 schema 비호환으로 FALLBACK 이다`() {
        val empty = PolicyEngineCapabilities(emptySet(), emptySet())
        val mode = PolicyVersionNegotiator.negotiate(empty, requestedSchemaVersion = 1, requestedModelVersion = null)
        assertThat(mode).isEqualTo(PolicyEngineMode.FALLBACK)
    }
}
