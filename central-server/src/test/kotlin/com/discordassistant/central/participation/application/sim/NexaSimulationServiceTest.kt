package com.discordassistant.central.participation.application.sim

import com.discordassistant.central.participation.domain.service.sim.SimScenarioException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 어드민 NEXA 시뮬레이션 서비스 단위 테스트 — 사전 정의 시나리오 실행·shadow(sends=0) 보장·알 수 없는 id 거부.
 */
class NexaSimulationServiceTest {
    private val service = NexaSimulationService()

    @Test
    fun `사전 정의 시나리오 목록을 제공한다`() {
        val list = service.listPredefined()
        assertThat(list).isNotEmpty
        assertThat(list.map { it.scenarioId }).contains("serious-direct-question", "silent-server", "mention-spam")
    }

    @Test
    fun `모든 사전 정의 시나리오는 실제 전송이 0이다(shadow only)`() {
        for (meta in service.listPredefined()) {
            val result = service.runPredefined(meta.scenarioId)
            assertThat(result.sends).describedAs("scenario ${meta.scenarioId} sends").isZero()
            assertThat(result.shadow).isTrue()
            assertThat(result.decisions).isNotEmpty
        }
    }

    @Test
    fun `직접 질문 시나리오는 정확히 한 번 SPEAK 한다`() {
        val result = service.runPredefined("serious-direct-question")
        assertThat(result.speakCount).isEqualTo(1)
    }

    @Test
    fun `알 수 없는 시나리오 id 는 거부된다`() {
        assertThatThrownBy { service.runPredefined("does-not-exist") }
            .isInstanceOf(SimScenarioException::class.java)
    }
}
