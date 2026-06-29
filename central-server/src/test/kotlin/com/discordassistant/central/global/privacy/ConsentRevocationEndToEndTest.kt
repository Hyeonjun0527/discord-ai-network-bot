package com.discordassistant.central.global.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P17-T010: 동의 철회 즉시 차단 — 진행 중 ingestion·pending action·GLM 요청 직전 각 지점에서 철회를 주입해
 * 철회 시점 이후 신규 외부 전송/발화/학습 export 가 0 임을 증명한다.
 *
 * [ConsentGate] 가 모든 위험 지점의 단일 체크포인트다. 철회는 즉시 동기 효력을 갖는다(scheduler tick 비대기).
 */
class ConsentRevocationEndToEndTest {
    /** 한 가명 사용자의 NEXA 처리 파이프라인을 게이트로 둘러싼 합성 시뮬레이터. */
    private class Pipeline(
        private val gate: ConsentGate,
        private val subject: String,
    ) {
        var externalSends = 0
            private set
        var rawContextReads = 0
            private set
        var judgeCalls = 0
            private set
        var pendingActions = 0
            private set
        var speeches = 0
            private set
        var trainingExports = 0
            private set

        /** 한 메시지의 ingestion → raw read → judge → speech → GLM → pending action → export 흐름. */
        fun process(revokeBefore: ProcessingStage? = null) {
            runStage(ProcessingStage.INGESTION, revokeBefore) { /* 관찰만 */ }
            runStage(ProcessingStage.RAW_CONTEXT_READ, revokeBefore) { rawContextReads++ }
            runStage(ProcessingStage.JUDGE_CALL, revokeBefore) { judgeCalls++ }
            runStage(ProcessingStage.SPEECH_GENERATION, revokeBefore) { speeches++ }
            runStage(ProcessingStage.EXTERNAL_GLM_REQUEST, revokeBefore) { externalSends++ }
            runStage(ProcessingStage.PENDING_ACTION, revokeBefore) { pendingActions++ }
            runStage(ProcessingStage.TRAINING_EXPORT, revokeBefore) { trainingExports++ }
        }

        private fun runStage(
            stage: ProcessingStage,
            revokeBefore: ProcessingStage?,
            body: () -> Unit,
        ) {
            if (revokeBefore == stage) gate.revoke(subject) // 이 지점 직전에 철회를 주입.
            gate.checkAllowed(subject, stage) // 철회됐으면 여기서 ConsentRevokedException → 단계 미실행.
            body()
        }
    }

    @Test
    fun `revocation just before ingestion blocks everything downstream`() {
        val gate = ConsentGate().apply { grant("user_3") }
        val p = Pipeline(gate, "user_3")
        runCatching { p.process(revokeBefore = ProcessingStage.INGESTION) }
        assertThat(p.rawContextReads).isZero()
        assertThat(p.judgeCalls).isZero()
        assertThat(p.speeches).isZero()
        assertThat(p.pendingActions).isZero()
        assertThat(p.externalSends).isZero()
        assertThat(p.trainingExports).isZero()
    }

    @Test
    fun `revocation just before raw context read blocks judge speech pending and export`() {
        val gate = ConsentGate().apply { grant("user_3") }
        val p = Pipeline(gate, "user_3")
        runCatching { p.process(revokeBefore = ProcessingStage.RAW_CONTEXT_READ) }
        assertThat(p.rawContextReads).isZero()
        assertThat(p.judgeCalls).isZero()
        assertThat(p.speeches).isZero()
        assertThat(p.pendingActions).isZero()
        assertThat(p.externalSends).isZero()
        assertThat(p.trainingExports).isZero()
    }

    @Test
    fun `revocation just before judge call blocks judge speech pending and export`() {
        val gate = ConsentGate().apply { grant("user_3") }
        val p = Pipeline(gate, "user_3")
        runCatching { p.process(revokeBefore = ProcessingStage.JUDGE_CALL) }
        assertThat(p.rawContextReads).isEqualTo(1)
        assertThat(p.judgeCalls).isZero()
        assertThat(p.speeches).isZero()
        assertThat(p.pendingActions).isZero()
        assertThat(p.externalSends).isZero()
        assertThat(p.trainingExports).isZero()
    }

    @Test
    fun `revocation just before speech generation blocks send pending and export`() {
        val gate = ConsentGate().apply { grant("user_3") }
        val p = Pipeline(gate, "user_3")
        runCatching { p.process(revokeBefore = ProcessingStage.SPEECH_GENERATION) }
        assertThat(p.speeches).isZero()
        assertThat(p.externalSends).isZero()
        assertThat(p.pendingActions).isZero()
        assertThat(p.trainingExports).isZero()
    }

    @Test
    fun `revocation just before glm request blocks the external send`() {
        val gate = ConsentGate().apply { grant("user_3") }
        val p = Pipeline(gate, "user_3")
        runCatching { p.process(revokeBefore = ProcessingStage.EXTERNAL_GLM_REQUEST) }
        // 발화는 생성됐을 수 있으나 외부 전송·export 는 0(철회 이후 신규 외부 전송 0).
        assertThat(p.externalSends).isZero()
        assertThat(p.pendingActions).isZero()
        assertThat(p.trainingExports).isZero()
    }

    @Test
    fun `revocation just before pending action blocks scheduled execution and export`() {
        val gate = ConsentGate().apply { grant("user_3") }
        val p = Pipeline(gate, "user_3")
        runCatching { p.process(revokeBefore = ProcessingStage.PENDING_ACTION) }
        assertThat(p.rawContextReads).isEqualTo(1)
        assertThat(p.judgeCalls).isEqualTo(1)
        assertThat(p.speeches).isEqualTo(1)
        assertThat(p.externalSends).isEqualTo(1)
        assertThat(p.pendingActions).isZero()
        assertThat(p.trainingExports).isZero()
    }

    @Test
    fun `consented subject completes the full pipeline`() {
        val gate = ConsentGate().apply { grant("user_3") }
        val p = Pipeline(gate, "user_3")
        p.process(revokeBefore = null)
        assertThat(p.rawContextReads).isEqualTo(1)
        assertThat(p.judgeCalls).isEqualTo(1)
        assertThat(p.speeches).isEqualTo(1)
        assertThat(p.externalSends).isEqualTo(1)
        assertThat(p.pendingActions).isEqualTo(1)
        assertThat(p.trainingExports).isEqualTo(1)
    }

    @Test
    fun `default deny - never-consented subject is blocked at first stage`() {
        val gate = ConsentGate() // grant 없음.
        val p = Pipeline(gate, "user_3")
        runCatching { p.process() }
        assertThat(p.rawContextReads).isZero()
        assertThat(p.judgeCalls).isZero()
        assertThat(p.speeches).isZero()
        assertThat(p.externalSends).isZero()
        assertThat(p.pendingActions).isZero()
    }
}
