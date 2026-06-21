package com.discordassistant.central.actionruntime.domain

import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P13-T002 — ActionStatus 상태 머신 합법 전이 그래프 단위 테스트.
 */
class ActionStatusTest {
    @Test
    fun `같은 상태로의 전이는 불가능하다`() {
        ActionStatus.entries.forEach { s ->
            assertThat(s.canTransitionTo(s)).isFalse()
        }
    }

    @Test
    fun `terminal 상태에서는 어떤 전이도 불가능하다`() {
        listOf(ActionStatus.COMPLETED, ActionStatus.CANCELLED, ActionStatus.FAILED).forEach { terminal ->
            ActionStatus.entries.forEach { target ->
                assertThat(terminal.canTransitionTo(target)).isFalse()
            }
        }
    }

    @Test
    fun `정상 흐름 전이가 합법이다`() {
        assertThat(ActionStatus.CONSIDERING.canTransitionTo(ActionStatus.SCHEDULED)).isTrue()
        assertThat(ActionStatus.SCHEDULED.canTransitionTo(ActionStatus.REEVALUATING)).isTrue()
        assertThat(ActionStatus.REEVALUATING.canTransitionTo(ActionStatus.TYPING)).isTrue()
        assertThat(ActionStatus.TYPING.canTransitionTo(ActionStatus.PARTIALLY_SENT)).isTrue()
        assertThat(ActionStatus.TYPING.canTransitionTo(ActionStatus.COMPLETED)).isTrue()
        assertThat(ActionStatus.PARTIALLY_SENT.canTransitionTo(ActionStatus.COMPLETED)).isTrue()
    }

    @Test
    fun `모든 비-terminal 상태는 취소로 갈 수 있다`() {
        ActionStatus.entries.filter { !it.isTerminal }.forEach { s ->
            assertThat(s.canTransitionTo(ActionStatus.CANCELLED)).isTrue()
        }
    }

    @Test
    fun `transient 재시도용 SCHEDULED 복귀가 REEVALUATING·TYPING 에서 합법이다`() {
        assertThat(ActionStatus.REEVALUATING.canTransitionTo(ActionStatus.SCHEDULED)).isTrue()
        assertThat(ActionStatus.TYPING.canTransitionTo(ActionStatus.SCHEDULED)).isTrue()
    }

    @Test
    fun `건너뛰는 전이는 불법이다`() {
        assertThat(ActionStatus.CONSIDERING.canTransitionTo(ActionStatus.TYPING)).isFalse()
        assertThat(ActionStatus.SCHEDULED.canTransitionTo(ActionStatus.COMPLETED)).isFalse()
        assertThat(ActionStatus.CONSIDERING.canTransitionTo(ActionStatus.REEVALUATING)).isFalse()
    }
}
