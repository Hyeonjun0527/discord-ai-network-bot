package com.discordassistant.central.participation.application.model

/**
 * 정책 모델 rollback 의 **순수 결정 코어**(NEXA-P18-T015, application 레이어·순수 함수).
 *
 * LIVE 로 선택된 모델 포인터는 (active, previous) 쌍이다. rollback 은 **active→previous signed artifact 로의 원자적
 * 전환**이다(deliverable T015) — 둘을 동시에 갱신하므로 중간 상태가 관측되지 않는다.
 *
 * **acceptance(T015) — in-flight decision 은 사용 model version 을 유지하거나 취소하는 규칙이 있다**:
 * [resolveInFlight] 가 그 규칙이다. 결정이 시작될 때 고정(pin)한 [decisionModelVersion] 이 현재 active 와 같으면
 * 그대로 진행(KEEP), 다르면(그 사이 rollback/swap 발생) 그 결정은 **취소**(CANCEL)한다 — 절대 결정 중간에 모델을
 * 바꿔 끼우지 않는다(혼합 추론 금지). rollback 자체는 새 결정에만 적용된다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 타입만. 활성 포인터는 호출자(서비스)가 SSOT 에서 로드해 넘긴다.
 */
object ModelRollback {
    /**
     * 현재 [pointer] 에서 active→previous 로의 원자적 rollback 을 계산한다. previous 가 없으면(되돌릴 대상 부재)
     * [ModelRollbackException]. 결과는 새 [LiveModelPointer] — active 가 이전 previous 가 되고, 새 previous 는
     * **없음**(연쇄 rollback 으로 끝없이 과거로 가지 않도록 한 단계만 — 더 과거로 가려면 명시 재선택).
     */
    fun rollBack(pointer: LiveModelPointer): LiveModelPointer {
        val target =
            pointer.previous
                ?: throw ModelRollbackException(
                    "rollback 대상(previous)이 없다: active=${pointer.active.modelVersion} — 되돌릴 이전 artifact 부재",
                )
        // 원자적: active 를 previous 로 바꾸고, 이전 active 는 버린다(한 단계 rollback). 새 previous 는 없음.
        return LiveModelPointer(active = target, previous = null)
    }

    /**
     * 새 LIVE 선택(승격) — [next] 를 active 로 올리고 현재 active 를 previous 로 보존한다(다음 rollback 대상). 같은
     * artifact 재선택은 no-op 의미로 그대로 둔다(previous 오염 방지).
     */
    fun promote(
        pointer: LiveModelPointer?,
        next: LiveModelArtifact,
    ): LiveModelPointer {
        if (pointer == null) return LiveModelPointer(active = next, previous = null)
        if (pointer.active == next) return pointer
        return LiveModelPointer(active = next, previous = pointer.active)
    }

    /**
     * **acceptance(T015)** — in-flight decision 규칙. 결정 시작 시 고정한 [decisionModelVersion] 과 현재 active
     * 버전을 비교한다:
     *  - 같으면 [InFlightResolution.KEEP] — 그 결정은 처음 본 모델 버전을 그대로 유지해 끝낸다.
     *  - 다르면 [InFlightResolution.CANCEL] — 결정 도중 rollback/swap 이 일어났으므로 혼합 추론을 막기 위해 취소한다.
     */
    fun resolveInFlight(
        decisionModelVersion: String,
        currentActiveVersion: String,
    ): InFlightResolution = if (decisionModelVersion == currentActiveVersion) InFlightResolution.KEEP else InFlightResolution.CANCEL
}

/**
 * LIVE 모델 포인터(application 값 객체·불변). active = 현재 발화/예측에 쓰는 모델, previous = 한 단계 rollback 대상.
 * rollback 은 이 쌍을 통째로 새 인스턴스로 교체한다(원자성 — 부분 갱신 없음).
 */
data class LiveModelPointer(
    val active: LiveModelArtifact,
    val previous: LiveModelArtifact?,
)

/**
 * LIVE 로 선택된 signed artifact 의 정체성(application 값 객체·불변). [ShadowModelRegistry.selectForLiveVerified]
 * 를 통과한 (modelId, modelVersion, artifactSha256) 만 담는다 — 미서명/변조 artifact 는 여기 도달하지 못한다.
 */
data class LiveModelArtifact(
    val modelId: String,
    val modelVersion: String,
    val artifactSha256: String,
) {
    init {
        require(modelId.isNotBlank()) { "modelId 는 비어 있을 수 없다" }
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
        require(artifactSha256.isNotBlank()) { "artifactSha256 은 비어 있을 수 없다" }
    }
}

/** in-flight decision 처리 규칙(acceptance T015) — 시작 시 본 모델 버전 유지(KEEP) 또는 취소(CANCEL). */
enum class InFlightResolution {
    /** 결정 시작 시 고정한 모델 버전을 그대로 유지해 끝낸다(active 가 그대로). */
    KEEP,

    /** 결정 도중 active 가 바뀌었다(rollback/swap) — 혼합 추론 방지로 그 결정을 취소한다. */
    CANCEL,
}

/** 정책 모델 rollback 불변식 위반(NEXA-P18-T015). 되돌릴 previous 부재 등에서 던진다(fail-closed). */
class ModelRollbackException(
    message: String,
) : RuntimeException(message)
