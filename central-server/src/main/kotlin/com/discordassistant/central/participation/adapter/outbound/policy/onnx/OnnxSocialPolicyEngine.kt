package com.discordassistant.central.participation.adapter.outbound.policy.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.ParticipationPolicyPort
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.application.port.out.PolicyEngineCapabilities
import com.discordassistant.central.participation.application.port.out.SocialPolicyPort
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture

/**
 * ONNX Runtime JVM 정책 추론 어댑터(NEXA-P11-T018, adapter 레이어).
 *
 * participation 의 [SocialPolicyPort]/[ParticipationPolicyPort] 를 구현해, 학습·export 된 ONNX 정책 모델
 * (ml/social-policy P11-T017)을 central JVM 에서 추론한다. 이는 **participation 자기 소유 결정 엔진** 이지
 * routing 의 CloudLlm/GLM 텍스트 생성과 **무관** 하다(ADR 0006 경계: participation 이 "말할지", speech/GLM 이
 * "무엇을 말할지"). routing/GLM/Z.AI 타입을 일절 참조하지 않는다.
 *
 * **deliverable(T018)**: [encoder] 로 PolicyDecisionRequest feature 를 tensor 로 변환, onnxruntime 으로 5개 head
 * 를 추론, [OnnxPolicyResponseDecoder] 로 분포를 복원한다.
 *
 * **acceptance(T018) — 검증 실패 시 fallback**: 생성 시 [OnnxModelDescriptor.validate] 로 모델 파일·schema·
 * calibration 버전을 확인한다. 검증·세션 로드·추론 중 어떤 실패든 [fallback] 정책(보수적 baseline)으로 강등한다 —
 * 잘못된/변조된/버전 불일치 모델로는 절대 행동을 강제하지 않는다(fail-closed, shadow 안전).
 *
 * **shadow only(운영 배포 금지)**: 이 엔진은 shadow 비교용이다. 실제 행동 반영은 P09 ShadowMode·버전 협상이
 * 결정하며, 본 어댑터는 분포만 낸다. 운영 자동 승격은 T020 레지스트리가 막는다(미승인 artifact LIVE 불가).
 *
 * 동기 [decide] 가 추론하고, 비동기 [predict] 는 그 결과를 즉시 완료된 future 로 감싼다(로컬 CPU 추론이라 블록 없음).
 *
 * 순수성 경계: adapter 레이어 — onnxruntime·application 포트·도메인 분포만. Spring/JPA/JDA·routing/GLM 미참조.
 */
class OnnxSocialPolicyEngine private constructor(
    private val descriptor: OnnxModelDescriptor,
    private val session: OrtSession,
    private val environment: OrtEnvironment,
    private val outputNames: List<String>,
    private val fallback: ParticipationPolicyPort,
) : SocialPolicyPort,
    ParticipationPolicyPort,
    AutoCloseable {
    override fun capabilities(): PolicyEngineCapabilities =
        PolicyEngineCapabilities(
            supportedSchemaVersions = setOf(descriptor.featureSchemaVersion),
            supportedModelVersions = setOf(descriptor.modelVersion),
        )

    override fun decide(request: PolicyDecisionRequest): PolicyDecisionResponse =
        runCatching { infer(request) }.getOrElse { fallback.decide(request) }

    override fun predict(request: PolicyDecisionRequest): CompletableFuture<PolicyDecisionResponse> =
        CompletableFuture.completedFuture(decide(request))

    /** ONNX 추론 본체 — tensor 인코딩 → 세션 run → head 분포 복원. 실패는 호출자([decide])가 fallback 으로 잡는다. */
    private fun infer(request: PolicyDecisionRequest): PolicyDecisionResponse {
        val row = encoder.encode(request.features)
        val shape = longArrayOf(1, row.size.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(row), shape).use { input ->
            session.run(mapOf(INPUT_NAME to input)).use { results ->
                val heads = outputNames.associateWith { name -> firstRow(results.get(name).get().value) }
                return OnnxPolicyResponseDecoder.decode(
                    action = heads.getValue(HEAD_ACTION),
                    delay = heads.getValue(HEAD_DELAY),
                    burst = heads.getValue(HEAD_BURST),
                    act = heads.getValue(HEAD_ACT),
                    modelVersion = descriptor.modelVersion,
                )
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val INPUT_NAME = "features"
        private const val HEAD_ACTION = "action"
        private const val HEAD_DELAY = "delay"
        private const val HEAD_BURST = "burst"
        private const val HEAD_ACT = "act"

        private val encoder = OnnxPolicyTensorEncoder

        /**
         * 어댑터를 만든다. descriptor 를 검증하고 ONNX 세션을 연다. **검증/로드 실패면 null 을 돌려준다** —
         * 호출자(설정)가 ONNX 엔진 없이 fallback 만 등록하도록(잘못된 모델로 추론 시작 금지, acceptance T018).
         *
         * @param expectedCalibrationVersion 운영/레지스트리가 요구하는 calibration 버전(불일치면 검증 실패).
         */
        fun createOrNull(
            descriptor: OnnxModelDescriptor,
            fallback: ParticipationPolicyPort,
            expectedCalibrationVersion: String,
        ): OnnxSocialPolicyEngine? =
            runCatching {
                descriptor.validate(
                    expectedFeatureSchemaVersion = FeatureCatalog.VERSION,
                    expectedCalibrationVersion = expectedCalibrationVersion,
                )
                val env = OrtEnvironment.getEnvironment()
                val session = env.createSession(descriptor.modelPath.toString(), OrtSession.SessionOptions())
                OnnxSocialPolicyEngine(
                    descriptor = descriptor,
                    session = session,
                    environment = env,
                    outputNames = session.outputNames.toList(),
                    fallback = fallback,
                )
            }.getOrNull()

        /** onnxruntime 출력값(float[][])의 첫 행을 FloatArray 로. 배치 1 추론이라 행 1개. */
        private fun firstRow(value: Any?): FloatArray {
            val matrix =
                value as? Array<*> ?: error("ONNX 출력 형태가 예상과 다르다(float[][] 아님): $value")
            val first = matrix.firstOrNull() as? FloatArray ?: error("ONNX 출력 첫 행이 FloatArray 아님")
            return first
        }
    }
}
