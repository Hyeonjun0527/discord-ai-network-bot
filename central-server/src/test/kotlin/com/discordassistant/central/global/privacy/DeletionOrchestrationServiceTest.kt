package com.discordassistant.central.global.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P17-T009: 삭제 요청 orchestration — 부분 실패 상태·재시도·완료 증거. */
class DeletionOrchestrationServiceTest {
    private val request = DeletionRequest("req_1", "user_3", DeletionTrigger.USER_REQUEST)

    private class RecordingSteps(
        val failOn: DeletionStep? = null,
    ) : DeletionStepPort {
        val executed = mutableListOf<DeletionStep>()

        override fun execute(
            step: DeletionStep,
            request: DeletionRequest,
        ) {
            executed.add(step)
            if (step == failOn) throw IllegalStateException("boom at $step")
        }
    }

    @Test
    fun `all steps run in order and complete`() {
        val steps = RecordingSteps()
        val progress = DeletionOrchestrationService(steps).run(request)
        // event redaction → projection invalidation → memory deletion → dataset tombstone.
        assertThat(steps.executed).containsExactlyElementsOf(DeletionStep.ORDERED)
        assertThat(progress.complete).isTrue()
        assertThat(progress.failedSteps).isEmpty()
    }

    @Test
    fun `partial failure records failed step and stops`() {
        val steps = RecordingSteps(failOn = DeletionStep.MEMORY_DELETION)
        val progress = DeletionOrchestrationService(steps).run(request)
        assertThat(progress.complete).isFalse()
        assertThat(progress.failedSteps).containsExactly(DeletionStep.MEMORY_DELETION)
        // 실패 이후 단계(dataset tombstone)는 실행되지 않는다.
        assertThat(steps.executed).doesNotContain(DeletionStep.DATASET_TOMBSTONE)
        assertThat(progress.statusOf(DeletionStep.MEMORY_DELETION)).isEqualTo(DeletionStepStatus.FAILED)
        assertThat(progress.attemptsOf(DeletionStep.MEMORY_DELETION)).isEqualTo(1)
    }

    @Test
    fun `retry resumes only remaining steps and completes`() {
        // 1차: memory 단계 실패.
        val failing = RecordingSteps(failOn = DeletionStep.MEMORY_DELETION)
        val first = DeletionOrchestrationService(failing).run(request)
        assertThat(first.complete).isFalse()

        // 2차(재시도): 이제 성공하는 포트로 같은 progress 를 이어받아 재실행.
        val healthy = RecordingSteps()
        val second = DeletionOrchestrationService(healthy).run(request, previousProgress = first)
        // 이미 DONE 인 event redaction·projection 은 재실행하지 않는다(멱등).
        assertThat(healthy.executed)
            .containsExactly(DeletionStep.MEMORY_DELETION, DeletionStep.DATASET_TOMBSTONE)
        assertThat(second.complete).isTrue()
        // 재시도 횟수가 누적된다(증거).
        assertThat(second.attemptsOf(DeletionStep.MEMORY_DELETION)).isEqualTo(2)
    }
}
