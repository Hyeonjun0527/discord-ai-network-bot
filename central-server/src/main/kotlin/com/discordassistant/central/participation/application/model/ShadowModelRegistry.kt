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

    /** shadow 비교 대상 후보(SHADOW/APPROVED). 등록만 된(REGISTERED)·거부(REJECTED)는 제외. */
    fun shadowCandidates(): List<ShadowModelCandidate> =
        store.listAll().filter { it.status == ModelStatus.SHADOW || it.status == ModelStatus.APPROVED }

    /** 모든 후보 조회(상태 무관). */
    fun listAll(): List<ShadowModelCandidate> = store.listAll()

    private fun require(modelId: String): ShadowModelCandidate =
        store.find(modelId) ?: throw ShadowModelRegistryException("등록되지 않은 modelId: $modelId")
}
