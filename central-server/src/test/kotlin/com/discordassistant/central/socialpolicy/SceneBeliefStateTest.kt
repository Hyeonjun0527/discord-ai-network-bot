package com.discordassistant.central.socialpolicy

import com.discordassistant.central.socialpolicy.domain.model.CommonGroundBelief
import com.discordassistant.central.socialpolicy.domain.model.IntentHypothesisBelief
import com.discordassistant.central.socialpolicy.domain.model.SceneBeliefDelta
import com.discordassistant.central.socialpolicy.domain.model.SceneBeliefState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class SceneBeliefStateTest {
    private val now = Instant.parse("2026-07-17T00:00:00Z")

    @Test
    fun `같은 참여자의 경쟁 가설 확률은 합이 1을 넘으면 정규화된다`() {
        val state = SceneBeliefState.initial("g", "c", "focus", now)
        val updated =
            state.apply(
                SceneBeliefDelta(
                    intentHypotheses =
                        listOf(
                            IntentHypothesisBelief("user_a", "actual_question", 0.8, setOf("m1")),
                            IntentHypothesisBelief("user_a", "teasing_test", 0.7, setOf("m1")),
                        ),
                ),
            )

        assertThat(updated.intentHypotheses.sumOf { it.probability })
            .isCloseTo(
                1.0,
                org.assertj.core.data.Offset
                    .offset(0.000001),
            )
    }

    @Test
    fun `근거 없는 공통 기반은 저장할 수 없다`() {
        assertThatThrownBy { CommonGroundBelief("already_explained", 0.8, emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
