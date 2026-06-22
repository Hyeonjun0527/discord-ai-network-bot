package com.discordassistant.central.global.privacy

/**
 * 삭제 요청 orchestration(NEXA-P17-T009, security·삭제 전파).
 * SSOT: [deletion-propagation.md](../../../../../../../../specs/product-v2/nexa/deletion-propagation.md).
 *
 * event redaction → projection invalidation → memory deletion → dataset tombstone 를 **하나의 추적 가능한
 * workflow** 로 연결한다. 각 단계의 성공/실패를 기록하고, 부분 실패 시 실패 단계만 **재시도**할 수 있게 상태를
 * 남긴다(acceptance T009 — 부분 실패 상태와 재시도·완료 증거).
 *
 * 순수 application: 단계 포트만 호출. Spring/JPA/JDA 미참조. 멱등 — 이미 DONE 인 단계는 다시 실행하지 않는다.
 */
class DeletionOrchestrationService(
    private val steps: DeletionStepPort,
) {
    /**
     * [request] 의 모든 단계를 순서대로 실행한다. 이미 [previousProgress] 에서 DONE 인 단계는 건너뛴다(재시도 시
     * 멱등). 한 단계가 실패해도 다음 단계를 시도하지 않고(순서 의존), 그 단계까지의 진행 상태를 돌려준다.
     * 모든 단계 DONE 이면 [DeletionProgress.complete] 가 true.
     */
    fun run(
        request: DeletionRequest,
        previousProgress: DeletionProgress = DeletionProgress.start(request.requestId),
    ): DeletionProgress {
        var progress = previousProgress
        for (step in DeletionStep.ORDERED) {
            if (progress.statusOf(step) == DeletionStepStatus.DONE) continue
            progress =
                try {
                    steps.execute(step, request)
                    progress.mark(step, DeletionStepStatus.DONE, attempt = progress.attemptsOf(step) + 1)
                } catch (e: Exception) {
                    // 실패 단계를 기록하고 중단 — 이후 단계는 PENDING 으로 남아 재시도 대상이 된다.
                    return progress.mark(
                        step,
                        DeletionStepStatus.FAILED,
                        attempt = progress.attemptsOf(step) + 1,
                        error = e.message ?: e::class.simpleName ?: "error",
                    )
                }
        }
        return progress
    }
}

/** 삭제 요청(가명 대상·트리거). 원문 비포함. */
data class DeletionRequest(
    val requestId: String,
    val subjectPseudonym: String,
    val trigger: DeletionTrigger,
) {
    init {
        require(requestId.isNotBlank()) { "requestId 는 비어 있을 수 없다" }
        require(subjectPseudonym.isNotBlank()) { "subjectPseudonym 은 비어 있을 수 없다" }
    }
}

/** 삭제 트리거 종류(deletion-propagation.md). */
enum class DeletionTrigger {
    MESSAGE_DELETE,
    USER_REQUEST,
    GUILD_LEAVE,
    CONSENT_REVOKE,
}

/** 삭제 전파 단계(순서 고정 — 원본→파생). */
enum class DeletionStep {
    EVENT_REDACTION,
    PROJECTION_INVALIDATION,
    MEMORY_DELETION,
    DATASET_TOMBSTONE,

    ;

    companion object {
        /** 실행 순서(원본 event → projection → memory → dataset). */
        val ORDERED: List<DeletionStep> = listOf(EVENT_REDACTION, PROJECTION_INVALIDATION, MEMORY_DELETION, DATASET_TOMBSTONE)
    }
}

/** 단계 상태. */
enum class DeletionStepStatus { PENDING, DONE, FAILED }

/** 한 단계의 진행 레코드(상태·시도 횟수·마지막 오류). */
data class DeletionStepProgress(
    val status: DeletionStepStatus,
    val attempts: Int,
    val lastError: String? = null,
)

/**
 * 삭제 workflow 진행 상태(불변·추적 가능). 단계별 상태/시도/오류를 담아 부분 실패와 재시도·완료 증거를 표현한다.
 */
data class DeletionProgress(
    val requestId: String,
    val steps: Map<DeletionStep, DeletionStepProgress>,
) {
    /** 모든 단계가 DONE 이면 완료. */
    val complete: Boolean
        get() = DeletionStep.ORDERED.all { statusOf(it) == DeletionStepStatus.DONE }

    /** 실패한 단계(재시도 대상) — 없으면 빈 목록. */
    val failedSteps: List<DeletionStep>
        get() = DeletionStep.ORDERED.filter { statusOf(it) == DeletionStepStatus.FAILED }

    fun statusOf(step: DeletionStep): DeletionStepStatus = steps[step]?.status ?: DeletionStepStatus.PENDING

    fun attemptsOf(step: DeletionStep): Int = steps[step]?.attempts ?: 0

    /** 한 단계 상태를 갱신한 새 진행 상태를 만든다(불변). */
    fun mark(
        step: DeletionStep,
        status: DeletionStepStatus,
        attempt: Int,
        error: String? = null,
    ): DeletionProgress = copy(steps = steps + (step to DeletionStepProgress(status = status, attempts = attempt, lastError = error)))

    companion object {
        /** 모든 단계 PENDING 인 시작 상태. */
        fun start(requestId: String): DeletionProgress =
            DeletionProgress(
                requestId = requestId,
                steps = DeletionStep.ORDERED.associateWith { DeletionStepProgress(DeletionStepStatus.PENDING, 0) },
            )
    }
}

/** 삭제 단계 실행 아웃바운드 포트. 어댑터가 실제 redaction/invalidation/deletion/tombstone 을 수행한다. */
interface DeletionStepPort {
    /** [step] 을 [request] 대상에 실행한다. 실패 시 예외를 던지면 orchestration 이 FAILED 로 기록한다. */
    fun execute(
        step: DeletionStep,
        request: DeletionRequest,
    )
}
