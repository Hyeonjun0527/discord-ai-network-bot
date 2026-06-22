package com.discordassistant.central.participation.adapter.outbound.policy.onnx

import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.DelayBucket
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.offset
import org.junit.jupiter.api.Test

/**
 * ONNX head → response 디코더 단위 테스트(NEXA-P11-T018). head 인덱스→도메인 enum 매핑·재정규화·uncertainty·
 * none 대상 복원을 검증한다(ML category 순서와 일치).
 */
class OnnxPolicyResponseDecoderTest {
    @Test
    fun `head 인덱스가 ML category 순서대로 도메인 enum 에 매핑된다`() {
        // action: [ignore, wait, react, speak, cancel] — speak 인덱스 3 이 최대.
        val response =
            OnnxPolicyResponseDecoder.decode(
                action = floatArrayOf(0.1f, 0.1f, 0.1f, 0.6f, 0.1f),
                delay = floatArrayOf(0.6f, 0.1f, 0.1f, 0.1f, 0.1f),
                burst = floatArrayOf(0.1f, 0.8f, 0.1f),
                act = floatArrayOf(0.1f, 0.1f, 0.6f, 0.1f, 0.05f, 0.05f),
                modelVersion = "m-1",
            )
        assertThat(response.mostLikelyAction).isEqualTo(SocialActionKind.SPEAK)
        assertThat(response.delayDistribution.mostLikelyBucket).isEqualTo(DelayBucket.IMMEDIATE)
        // act: [acknowledge, agree, ask, tease, self_disclose, unknown] — ask 인덱스 2 가 최대.
        assertThat(response.socialActWeights.maxByOrNull { it.value }!!.key).isEqualTo(SocialAct.ASK)
        assertThat(response.modelVersion).isEqualTo("m-1")
    }

    @Test
    fun `actionWeights 합이 1 로 재정규화된다(부동소수 잔차 흡수)`() {
        val response =
            OnnxPolicyResponseDecoder.decode(
                action = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.21f), // 합 1.01.
                delay = floatArrayOf(1f, 0f, 0f, 0f, 0f),
                burst = floatArrayOf(0f, 1f, 0f),
                act = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f),
                modelVersion = "m-1",
            )
        assertThat(response.actionWeights.values.sum()).isCloseTo(1.0, offset(1e-9))
    }

    @Test
    fun `target 은 장면 ID 부재로 none 대상으로 복원된다`() {
        val response =
            OnnxPolicyResponseDecoder.decode(
                action = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f),
                delay = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f),
                burst = floatArrayOf(0.34f, 0.33f, 0.33f),
                act = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.1f, 0.1f),
                modelVersion = "m-1",
            )
        assertThat(response.targetDistribution.noneProbability).isEqualTo(1.0)
        assertThat(response.targetDistribution.candidates).isEmpty()
    }

    @Test
    fun `균등 분포의 uncertainty 가 1 에 가깝고 확정 분포는 0 에 가깝다`() {
        val uniform =
            OnnxPolicyResponseDecoder.decode(
                action = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f),
                delay = floatArrayOf(1f, 0f, 0f, 0f, 0f),
                burst = floatArrayOf(0f, 1f, 0f),
                act = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f),
                modelVersion = "m",
            )
        val certain =
            OnnxPolicyResponseDecoder.decode(
                action = floatArrayOf(0f, 0f, 0f, 1f, 0f),
                delay = floatArrayOf(1f, 0f, 0f, 0f, 0f),
                burst = floatArrayOf(0f, 1f, 0f),
                act = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f),
                modelVersion = "m",
            )
        assertThat(uniform.uncertainty).isCloseTo(1.0, offset(1e-6))
        assertThat(certain.uncertainty).isCloseTo(0.0, offset(1e-6))
    }

    @Test
    fun `burst none 확률이 reaction-only 로, single multi 가 조각 수 분포로 복원된다`() {
        val response =
            OnnxPolicyResponseDecoder.decode(
                action = floatArrayOf(0f, 0f, 0f, 1f, 0f),
                delay = floatArrayOf(1f, 0f, 0f, 0f, 0f),
                burst = floatArrayOf(0.3f, 0.5f, 0.2f), // none 0.3, single 0.5, multi 0.2.
                act = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f),
                modelVersion = "m",
            )
        assertThat(response.burstProfile.reactionOnlyProbability).isCloseTo(0.3, offset(1e-6))
        // single/multi 재정규화: 0.5/0.7, 0.2/0.7.
        assertThat(response.burstProfile.fragmentCountWeights[1]!!).isCloseTo(0.5 / 0.7, offset(1e-6))
        assertThat(response.burstProfile.fragmentCountWeights[2]!!).isCloseTo(0.2 / 0.7, offset(1e-6))
    }
}
