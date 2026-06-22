package com.discordassistant.central.participation.application.model

import com.discordassistant.central.participation.application.port.out.ShadowModelRegistryPort
import java.time.Clock
import java.time.Instant

/**
 * shadow 모델 후보 레지스트리 서비스(NEXA-P11-T020, application 레이어). 학습 모델 후보의 등록·버전 관리·상태 전이·
 * LIVE 선택을 한곳에서 **불변식과 함께** 다룬다.
 *
 * **deliverable(T020)**: [register] 가 모델 ID·hash·feature schema·calibration·status 를 등록한다(초기 status =
 * [ModelStatus.REGISTERED]). 같은 modelId 재등록은 거부(불변 봉인).
 *
 * **acceptance(T020) — 승인되지 않은 artifact 는 LIVE 상태로 선택할 수 없다**:
 * - [transition] 은 [ModelStatus.canTransitionTo] 단계 규칙만 허용한다 — REGISTERED 에서 곧장 APPROVED 로 가지
 *   못하고 반드시 SHADOW 를 경유한다(자동 LIVE 승격 차단).
 * - [selectForLive] 는 [ShadowModelCandidate.isSelectableForLive](= APPROVED)만 돌려준다. 미승인 후보를 LIVE 로
 *   고르려 하면 [ShadowModelRegistryException].
 *
 * 즉 shadow 모드에서만 후보가 등록·비교되고, LIVE 자격은 명시 human-gate 승인(APPROVED) 후에만 생긴다(안전).
 *
 * 순수성 경계: application 레이어 — 포트·application 값 객체·표준 Clock 만. Spring/JPA/JDA 미참조.
 */
class ShadowModelRegistry(
    private val store: ShadowModelRegistryPort,
    private val clock: Clock = Clock.systemUTC(),
    private val integrityVerifier: ArtifactIntegrityVerifier = ArtifactIntegrityVerifier(),
) {
    /**
     * 새 모델 후보를 [ModelStatus.REGISTERED] 로 등록한다. 같은 modelId 가 이미 있으면 거부(불변 — 재등록 금지).
     */
    fun register(
        modelId: String,
        artifactSha256: String,
        modelVersion: String,
        featureSchemaVersion: Int,
        calibrationVersion: String,
    ): ShadowModelCandidate {
        if (store.find(modelId) != null) {
            throw ShadowModelRegistryException("이미 등록된 modelId: $modelId(재등록 금지)")
        }
        val candidate =
            ShadowModelCandidate(
                modelId = modelId,
                artifactSha256 = artifactSha256,
                modelVersion = modelVersion,
                featureSchemaVersion = featureSchemaVersion,
                calibrationVersion = calibrationVersion,
                status = ModelStatus.REGISTERED,
                registeredAt = Instant.now(clock),
            )
        store.save(candidate)
        return candidate
    }

    /**
     * 후보 상태를 [to] 로 전이한다(단계 규칙 강제). 불법 전이(예: REGISTERED→APPROVED 직승격)는
     * [ShadowModelRegistryException].
     */
    fun transition(
        modelId: String,
        to: ModelStatus,
    ): ShadowModelCandidate {
        val current = require(modelId)
        if (!current.status.canTransitionTo(to)) {
            throw ShadowModelRegistryException(
                "허용되지 않는 상태 전이: ${current.status} → $to(modelId=$modelId). 자동 LIVE 승격 금지.",
            )
        }
        val updated = current.copy(status = to)
        store.save(updated)
        return updated
    }

    /**
     * LIVE 로 선택한다 — **[ModelStatus.APPROVED] 후보만** 허용(acceptance T020). 미승인/거부/등록/shadow 후보를
     * LIVE 로 고르면 [ShadowModelRegistryException].
     *
     * **주의**: 이 메서드는 *상태*(APPROVED)만 확인한다. 실제 LIVE 승격 경로는 변조 artifact 를 거르기 위해
     * 반드시 [selectForLiveVerified] 로 서명·hash 무결성까지 검증해야 한다(NEXA-P17-T020 acceptance — 변조
     * artifact 는 ACTIVE 가 되지 못한다). 상태만 확인하는 이 경로는 무결성 검증을 위한 전제(후보 조회)로만 쓴다.
     */
    fun selectForLive(modelId: String): ShadowModelCandidate {
        val candidate = require(modelId)
        if (!candidate.isSelectableForLive) {
            throw ShadowModelRegistryException(
                "승인되지 않은 artifact 를 LIVE 로 선택할 수 없다: modelId=$modelId, status=${candidate.status}",
            )
        }
        return candidate
    }

    /**
     * LIVE 승격 경로(NEXA-P17-T020 enforcement). 상태(APPROVED) 확인에 더해 [ArtifactIntegrityVerifier] 로
     * **서명·hash 무결성까지 검증**한 뒤에만 후보를 돌려준다. 다음 중 하나라도 어긋나면 거부한다:
     *  - 후보가 APPROVED 가 아님([selectForLive] 위임) → 미승인 LIVE 금지.
     *  - manifest 의 modelVersion 이 등록된 후보의 modelVersion 과 다름 → 다른 artifact 를 끼워넣는 swap.
     *  - manifest 의 `model` 컴포넌트 sha256 이 등록된 [ShadowModelCandidate.artifactSha256] 와 다름 → artifact 변조.
     *  - [ArtifactIntegrityVerifier.verify] 가 서명 불일치·hash 불일치·구성 누락을 발견 → 변조/미서명.
     *
     * 즉 APPROVED 라벨만으로는 LIVE 가 될 수 없고, 변조·미서명 artifact 는 [ArtifactIntegrityException] 으로 거부된다.
     *
     * @param signed ml 측이 봉인한 서명 manifest.
     * @param actualDigests 검증 시점에 다시 계산한 컴포넌트별 현재 sha256(name → sha256 hex).
     * @param signingKey 대칭 서명키(env 로만 주입 — 레지스트리는 키를 보관하지 않는다).
     */
    fun selectForLiveVerified(
        modelId: String,
        signed: SignedArtifactManifest,
        actualDigests: Map<String, String>,
        signingKey: ByteArray,
    ): ShadowModelCandidate {
        val candidate = selectForLive(modelId)
        // 등록된 후보의 정체성(modelVersion·artifact hash)이 서명된 manifest 와 일치하는지 먼저 못박는다 —
        // 서명이 유효해도 "다른 승인 후보의 서명을 미승인 artifact 에 붙이는" swap 을 차단한다.
        if (signed.manifest.modelVersion != candidate.modelVersion) {
            throw ArtifactIntegrityException(
                "manifest modelVersion 불일치: registry=${candidate.modelVersion} signed=${signed.manifest.modelVersion}" +
                    " — ACTIVE 자격 없음",
            )
        }
        val signedModelDigest =
            signed.manifest.components
                .firstOrNull { it.name == MODEL_COMPONENT_NAME }
                ?.sha256
        if (signedModelDigest != candidate.artifactSha256) {
            throw ArtifactIntegrityException(
                "등록된 artifactSha256 과 manifest model digest 불일치(변조/swap) — ACTIVE 자격 없음",
            )
        }
        // 서명·각 컴포넌트 hash 무결성(변조/미서명/누락 차단). 어긋나면 ArtifactIntegrityException.
        integrityVerifier.verify(signed = signed, actualDigests = actualDigests, signingKey = signingKey)
        return candidate
    }

    /** shadow 비교 대상 후보(SHADOW/APPROVED). 등록만 된(REGISTERED)·거부(REJECTED)는 제외. */
    fun shadowCandidates(): List<ShadowModelCandidate> =
        store.listAll().filter { it.status == ModelStatus.SHADOW || it.status == ModelStatus.APPROVED }

    /** 모든 후보 조회(상태 무관). */
    fun listAll(): List<ShadowModelCandidate> = store.listAll()

    private fun require(modelId: String): ShadowModelCandidate =
        store.find(modelId) ?: throw ShadowModelRegistryException("등록되지 않은 modelId: $modelId")

    companion object {
        /** manifest 에서 ONNX 모델 본체 컴포넌트의 논리 이름(ml signing.py `sign_artifact` 의 "model" 키와 일치). */
        const val MODEL_COMPONENT_NAME: String = "model"
    }
}
