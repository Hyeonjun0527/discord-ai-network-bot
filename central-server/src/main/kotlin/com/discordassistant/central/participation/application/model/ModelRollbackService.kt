package com.discordassistant.central.participation.application.model

/**
 * 정책 모델 rollback 유스케이스(NEXA-P18-T015, application 레이어).
 *
 * LIVE 모델 포인터(active, previous)를 SSOT 에서 들고, active→previous **signed artifact 로 원자적 전환**한다
 * (deliverable T015). rollback 대상(previous)은 이미 [ShadowModelRegistry.selectForLiveVerified] 를 통과해 LIVE 였던
 * artifact 이므로 서명·hash 무결성이 보장된다(미서명/변조는 애초에 promote 되지 못한다). 전환은 [LiveModelPointer]
 * 인스턴스 통째 교체라 부분 상태가 관측되지 않는다(원자성).
 *
 * **acceptance(T015)**: [resolveInFlight] 가 in-flight decision 규칙을 강제한다 — 결정 시작 시 고정한 모델 버전이
 * 현재 active 와 같으면 유지(KEEP), rollback 으로 바뀌었으면 취소(CANCEL). 혼합 추론을 절대 허용하지 않는다.
 *
 * 순수성 경계: application — 결정 코어([ModelRollback])와 SSOT 접근 람다만. Spring/JPA/JDA 미참조(어댑터가 와이어).
 */
class ModelRollbackService(
    /** 현재 LIVE 포인터를 SSOT 에서 읽는다(없으면 null — 아직 LIVE 모델 미선택). */
    private val loadPointer: () -> LiveModelPointer?,
    /** 새 LIVE 포인터를 원자적으로 저장한다(active+previous 동시 — 부분 갱신 금지). */
    private val storePointer: (LiveModelPointer) -> Unit,
) {
    /**
     * 현재 active 를 previous 로 원자적 rollback 한다. previous 가 없으면 [ModelRollbackException]. 전환된 새 active
     * artifact 를 돌려준다(운영자 확인용).
     */
    fun rollBack(): LiveModelArtifact {
        val pointer = loadPointer() ?: throw ModelRollbackException("LIVE 모델 포인터가 없다 — rollback 대상 부재")
        val rolledBack = ModelRollback.rollBack(pointer)
        storePointer(rolledBack)
        return rolledBack.active
    }

    /**
     * 새 LIVE artifact 를 승격하고 현재 active 를 previous 로 보존한다(다음 rollback 대상). [next] 는 이미 무결성
     * 검증을 통과한 [LiveModelArtifact] 여야 한다(호출자가 [ShadowModelRegistry.selectForLiveVerified] 후 전달).
     */
    fun promote(next: LiveModelArtifact): LiveModelPointer {
        val updated = ModelRollback.promote(loadPointer(), next)
        storePointer(updated)
        return updated
    }

    /**
     * **acceptance(T015)** — in-flight decision 규칙. 결정 시작 시 고정한 [decisionModelVersion] 이 현재 active 와
     * 같으면 유지(KEEP), rollback 으로 바뀌었으면 취소(CANCEL). LIVE 포인터가 없으면(모델 미선택) 그 결정도 취소한다.
     */
    fun resolveInFlight(decisionModelVersion: String): InFlightResolution {
        val active = loadPointer()?.active ?: return InFlightResolution.CANCEL
        return ModelRollback.resolveInFlight(decisionModelVersion, active.modelVersion)
    }
}
