package com.discordassistant.central.global.privacy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P17-T008: 사용자 데이터 export — 다른 사용자 content·내부 비밀 비포함. */
class UserDataExportServiceTest {
    private fun source(
        events: List<ExportedSourceEvent> = emptyList(),
        memories: List<ExportedMemory> = emptyList(),
        relations: List<ExportedRelationState> = emptyList(),
        eligibility: ExportedTrainingEligibility? = null,
    ) = object : UserDataSourcePort {
        override fun sourceEvents(subjectPseudonym: String) = events

        override fun memories(subjectPseudonym: String) = memories

        override fun relationStates(subjectPseudonym: String) = relations

        override fun trainingEligibility(subjectPseudonym: String) = eligibility
    }

    @Test
    fun `exports source events memory relation and eligibility for subject`() {
        val service =
            UserDataExportService(
                source(
                    events = listOf(ExportedSourceEvent("user_3", "evt_1", "message", 100)),
                    memories = listOf(ExportedMemory("user_3", "mem_1", "라면 좋아함", "stated")),
                    relations = listOf(ExportedRelationState("user_3", "user_9", "friendly")),
                    eligibility = ExportedTrainingEligibility("user_3", true, "opted_in"),
                ),
            )
        val out = service.export("user_3")
        assertThat(out.sourceEvents).hasSize(1)
        assertThat(out.memories.single().claim).isEqualTo("라면 좋아함")
        assertThat(out.relationStates.single().counterpartPseudonym).isEqualTo("user_9")
        assertThat(out.trainingEligibility?.eligible).isTrue()
    }

    @Test
    fun `cross-subject content is rejected fail-closed`() {
        // 포트가 다른 사용자(user_99) 항목을 잘못 반환하면 export 가 거부된다.
        val service =
            UserDataExportService(
                source(memories = listOf(ExportedMemory("user_99", "mem_x", "남의 비밀", "stated"))),
            )
        assertThatThrownBy { service.export("user_3") }
            .isInstanceOf(CrossSubjectLeakException::class.java)
    }

    @Test
    fun `blank subject is rejected`() {
        assertThatThrownBy { UserDataExportService(source()).export(" ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
