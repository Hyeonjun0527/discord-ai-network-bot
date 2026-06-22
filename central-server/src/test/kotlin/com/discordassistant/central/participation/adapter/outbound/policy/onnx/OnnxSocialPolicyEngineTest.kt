package com.discordassistant.central.participation.adapter.outbound.policy.onnx

import com.discordassistant.central.participation.adapter.outbound.policy.baseline.AlwaysSilentPolicy
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.PolicyConfigView
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.offset
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

/**
 * ONNX 정책 어댑터 통합 테스트(NEXA-P11-T018). 실제 fixture ONNX 모델로 end-to-end 추론(feature→tensor→head→
 * response 복원)과, 검증 실패 시 fallback 강등을 증명한다.
 */
class OnnxSocialPolicyEngineTest {
    private val onnxPath: Path = File("../contracts/policy/fixtures/parity/policy-v1-fixture.onnx").toPath()
    private val fixtureSha = OnnxModelDescriptor.sha256Hex(onnxPath)
    private val fallback = AlwaysSilentPolicy()

    private fun descriptor(
        sha: String = fixtureSha,
        schemaVersion: Int = FeatureCatalog.VERSION,
        calibration: String = "cal-1",
    ): OnnxModelDescriptor =
        OnnxModelDescriptor(
            modelId = "policy-v1-fixture",
            modelVersion = "policy-v1-fixture",
            modelPath = onnxPath,
            expectedSha256 = sha,
            featureSchemaVersion = schemaVersion,
            calibrationVersion = calibration,
        )

    private fun request(): PolicyDecisionRequest =
        PolicyDecisionRequest(
            sceneSnapshotRef = SceneSnapshotRef("g-1", "c-1", 1, 1),
            features =
                FeatureVectorView(
                    features =
                        mapOf(
                            FeatureCatalog.BURST_IS_QUESTION to FeatureValue.present(1.0),
                            FeatureCatalog.BURST_HAS_MENTION to FeatureValue.present(1.0),
                        ),
                    version = FeatureCatalog.VERSION,
                ),
            config = PolicyConfigView("auto", autoRespondEnabled = true, speechAllowed = true),
            modelVersion = "policy-v1-fixture",
            schemaVersion = FeatureCatalog.VERSION,
            seed = 7,
        )

    @Test
    fun `유효한 모델로 추론하면 계약을 통과하는 분포를 낸다`() {
        val engine = OnnxSocialPolicyEngine.createOrNull(descriptor(), fallback, "cal-1")
        assertThat(engine).isNotNull
        engine!!.use {
            val response = it.decide(request())
            // actionWeights 합=1(계약), modelVersion 반영, uncertainty [0,1].
            assertThat(response.actionWeights.values.sum()).isCloseTo(1.0, offset(1e-9))
            assertThat(response.modelVersion).isEqualTo("policy-v1-fixture")
            assertThat(response.uncertainty).isBetween(0.0, 1.0)
            assertThat(response.actionWeights.keys).contains(SocialActionKind.SPEAK)
        }
    }

    @Test
    fun `predict 는 같은 결과를 즉시 완료된 future 로 감싼다`() {
        val engine = OnnxSocialPolicyEngine.createOrNull(descriptor(), fallback, "cal-1")!!
        engine.use {
            val sync = it.decide(request())
            val async = it.predict(request()).get()
            assertThat(async.actionWeights).isEqualTo(sync.actionWeights)
        }
    }

    @Test
    fun `acceptance — hash 불일치면 엔진 생성이 실패한다(fallback 전제)`() {
        val engine =
            OnnxSocialPolicyEngine.createOrNull(
                descriptor(sha = "deadbeef".repeat(8)),
                fallback,
                "cal-1",
            )
        assertThat(engine).isNull()
    }

    @Test
    fun `acceptance — feature schema 버전 불일치면 엔진 생성이 실패한다`() {
        val engine =
            OnnxSocialPolicyEngine.createOrNull(
                descriptor(schemaVersion = FeatureCatalog.VERSION + 1),
                fallback,
                "cal-1",
            )
        assertThat(engine).isNull()
    }

    @Test
    fun `acceptance — calibration 버전 불일치면 엔진 생성이 실패한다`() {
        val engine = OnnxSocialPolicyEngine.createOrNull(descriptor(calibration = "cal-1"), fallback, "cal-2")
        assertThat(engine).isNull()
    }

    @Test
    fun `acceptance — 모델 파일 부재면 엔진 생성이 실패한다`() {
        val missing = descriptor().copy(modelPath = File("/nonexistent/model.onnx").toPath())
        assertThat(OnnxSocialPolicyEngine.createOrNull(missing, fallback, "cal-1")).isNull()
    }
}
