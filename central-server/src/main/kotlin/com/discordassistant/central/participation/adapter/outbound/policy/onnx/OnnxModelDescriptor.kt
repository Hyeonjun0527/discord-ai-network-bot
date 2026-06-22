package com.discordassistant.central.participation.adapter.outbound.policy.onnx

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * ONNX 정책 모델 descriptor(NEXA-P11-T018, adapter 레이어). central 이 추론에 쓸 ONNX artifact 의 **신원과 호환
 * 계약** 을 담는다 — 모델 파일 경로, 기대 sha256 hash, feature schema 버전, calibration 버전, modelVersion.
 *
 * **acceptance(T018) — 모델 파일·schema·calibration version 검증 실패 시 fallback 한다**:
 * [validate] 가 (1) 파일 존재·읽기 가능, (2) sha256 hash 일치, (3) feature schema 버전 일치, (4) calibration
 * 버전 일치를 모두 확인한다. 하나라도 어긋나면 [OnnxModelValidationException] 으로 실패해 엔진이 fallback 한다 —
 * 잘못된/변조된/버전 불일치 모델로는 **절대 추론하지 않는다**(fail-closed, shadow 안전).
 *
 * 순수성 경계: adapter 레이어 — 표준 java.nio·security 만. Spring/JPA/JDA·routing/GLM 미참조.
 */
data class OnnxModelDescriptor(
    /** 모델 식별자(레지스트리·로그 추적용). */
    val modelId: String,
    /** 추론에 보고할 모델 버전(PolicyDecisionResponse.modelVersion). */
    val modelVersion: String,
    /** ONNX 모델 파일 경로. */
    val modelPath: Path,
    /** 기대 sha256 hex(무결성 봉인 — 변조/스큐 감지). */
    val expectedSha256: String,
    /** 이 모델이 기대하는 feature schema 버전(FeatureCatalog.VERSION 과 일치해야 한다). */
    val featureSchemaVersion: Int,
    /** 이 모델의 calibration 버전(요청·레지스트리와 일치해야 한다). */
    val calibrationVersion: String,
) {
    init {
        require(modelId.isNotBlank()) { "modelId 는 비어 있을 수 없다" }
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
        require(expectedSha256.isNotBlank()) { "expectedSha256 은 비어 있을 수 없다" }
        require(featureSchemaVersion >= 1) { "featureSchemaVersion 은 1 이상이어야 한다: $featureSchemaVersion" }
        require(calibrationVersion.isNotBlank()) { "calibrationVersion 은 비어 있을 수 없다" }
    }

    /**
     * 모델 파일·schema·calibration 버전을 검증한다(acceptance T018). 실패는 [OnnxModelValidationException].
     *
     * @param expectedFeatureSchemaVersion 런타임 feature 카탈로그 버전(코드 SSOT). 모델과 다르면 거부.
     * @param expectedCalibrationVersion 요청/레지스트리가 요구하는 calibration 버전. 다르면 거부.
     */
    fun validate(
        expectedFeatureSchemaVersion: Int,
        expectedCalibrationVersion: String,
    ) {
        if (!Files.isReadable(modelPath)) {
            throw OnnxModelValidationException("ONNX 모델 파일을 읽을 수 없다: $modelPath")
        }
        val actual = sha256Hex(modelPath)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw OnnxModelValidationException(
                "ONNX 모델 hash 불일치(변조/스큐): 기대 $expectedSha256, 실제 $actual",
            )
        }
        if (featureSchemaVersion != expectedFeatureSchemaVersion) {
            throw OnnxModelValidationException(
                "feature schema 버전 불일치: 모델 $featureSchemaVersion != 런타임 $expectedFeatureSchemaVersion",
            )
        }
        if (calibrationVersion != expectedCalibrationVersion) {
            throw OnnxModelValidationException(
                "calibration 버전 불일치: 모델 $calibrationVersion != 기대 $expectedCalibrationVersion",
            )
        }
    }

    companion object {
        /** 파일의 sha256 hex(소문자). */
        fun sha256Hex(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(Files.readAllBytes(path))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * ONNX 모델 검증 실패(NEXA-P11-T018). 파일 부재·hash 불일치·schema/calibration 버전 불일치 시 던진다 —
 * 엔진이 이를 잡아 fallback 정책으로 강등한다(잘못된 모델로 추론 금지, fail-closed).
 */
class OnnxModelValidationException(
    message: String,
) : RuntimeException(message)
