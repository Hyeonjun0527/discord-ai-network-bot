package com.discordassistant.central.participation.adapter.outbound.policy.onnx

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ONNX tensor 인코더 단위 테스트(NEXA-P11-T018). feature→tensor 차원·순서·missing 보존을 검증한다(ML design
 * matrix `features ++ missing_mask` 와 동일 형태).
 */
class OnnxPolicyTensorEncoderTest {
    private val dim = FeatureCatalog.all.size

    @Test
    fun `입력 차원은 feature 수의 2배(값 + missing mask)다`() {
        assertThat(OnnxPolicyTensorEncoder.inputDim).isEqualTo(dim * 2)
    }

    @Test
    fun `관측 feature 는 값 채널에, missing 채널은 0 이다`() {
        val features =
            FeatureVectorView(
                mapOf(FeatureCatalog.BURST_IS_QUESTION to FeatureValue.present(1.0)),
                FeatureCatalog.VERSION,
            )
        val row = OnnxPolicyTensorEncoder.encode(features)
        val idx = FeatureCatalog.all.keys.indexOf(FeatureCatalog.BURST_IS_QUESTION)
        assertThat(row[idx]).isEqualTo(1.0f)
        assertThat(row[dim + idx]).isEqualTo(0.0f) // 관측 → missing 0.
    }

    @Test
    fun `부재 feature 는 값 0 + missing 1 로 보존된다(0 과 모름 구분)`() {
        val row = OnnxPolicyTensorEncoder.encode(FeatureVectorView.empty(FeatureCatalog.VERSION))
        // 모든 feature 부재 → 값 채널 전부 0, missing 채널 전부 1.
        for (i in 0 until dim) {
            assertThat(row[i]).isEqualTo(0.0f)
            assertThat(row[dim + i]).isEqualTo(1.0f)
        }
    }

    @Test
    fun `명시 missing feature 는 missing 채널 1`() {
        val features =
            FeatureVectorView(
                mapOf(FeatureCatalog.REL_FAMILIARITY to FeatureValue.MISSING),
                FeatureCatalog.VERSION,
            )
        val row = OnnxPolicyTensorEncoder.encode(features)
        val idx = FeatureCatalog.all.keys.indexOf(FeatureCatalog.REL_FAMILIARITY)
        assertThat(row[idx]).isEqualTo(0.0f)
        assertThat(row[dim + idx]).isEqualTo(1.0f)
    }
}
