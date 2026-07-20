package com.discordassistant.central.participation.application.debug

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaInputTraceStoreTest {
    @Test
    fun `judge and speech inputs join under one trace and are removed after execution is recorded`() {
        val store = NiaInputTraceStore()
        val rag =
            ParticipationTraceConversation(
                id = "41",
                title = "피곤한 장면",
                score = 0.92,
                scoringMethod = "EMBEDDING",
                expectedAction = "SPEAK",
                messages = listOf(ParticipationTraceMessage("a", "피곤하다")),
                expectedReplies = listOf("푹 자"),
            )

        store.recordJudge(
            traceId = "trace-1",
            judgePrompt = "judge exact input",
            globalFewShotSetId = 3,
            globalFewShotVersion = 7,
            globalFewShotExampleCount = 12,
            ragQuery = "a: 피곤하다",
            ragMatches = listOf(rag),
        )
        store.record("trace-1", "speech system exact input", "speech user exact input")

        val snapshot = store.take("trace-1")

        assertThat(snapshot?.judgePrompt).isEqualTo("judge exact input")
        assertThat(snapshot?.speechSystemPrompt).isEqualTo("speech system exact input")
        assertThat(snapshot?.speechUserPrompt).isEqualTo("speech user exact input")
        assertThat(snapshot?.globalFewShotSetId).isEqualTo(3)
        assertThat(snapshot?.globalFewShotVersion).isEqualTo(7)
        assertThat(snapshot?.globalFewShotExampleCount).isEqualTo(12)
        assertThat(snapshot?.ragMatches).containsExactly(rag)
        assertThat(store.take("trace-1")).isNull()
    }
}
