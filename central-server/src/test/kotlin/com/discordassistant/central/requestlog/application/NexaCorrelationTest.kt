package com.discordassistant.central.requestlog.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T010 requestlog NEXA 상관 메타데이터 acceptance 단위 테스트.
 *
 * 핵심 acceptance: decision→GLM request→Discord message 를 잇는 식별자(원문 없음)와 NEXA 전용 purpose 구분.
 */
class NexaCorrelationTest {
    @Test
    fun `correlationId·decisionId·actionId·modelVersion 으로 감사 체인을 만든다`() {
        val correlation =
            NexaCorrelation(
                correlationId = "corr-1",
                decisionId = "dec-1",
                actionId = "act-1",
                modelVersion = "glm-5.1",
            )
        assertThat(correlation.correlationId).isEqualTo("corr-1")
        assertThat(correlation.decisionId).isEqualTo("dec-1")
        assertThat(correlation.actionId).isEqualTo("act-1")
        assertThat(correlation.modelVersion).isEqualTo("glm-5.1")
    }

    @Test
    fun `미전송(shadow·생성만)이면 actionId 가 null 일 수 있다`() {
        val correlation = NexaCorrelation("corr-1", "dec-1", actionId = null, modelVersion = "glm-5.1")
        assertThat(correlation.actionId).isNull()
    }

    @Test
    fun `NEXA 발화 purpose 라벨로 일반 요청과 구분한다`() {
        assertThat(NexaCorrelation.PURPOSE).isEqualTo("NEXA_SPEECH")
    }

    @Test
    fun `필수 식별자가 비면 거부한다(원문 없는 식별자 무결성)`() {
        assertThatThrownBy { NexaCorrelation("", "dec-1", null, "glm-5.1") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NexaCorrelation("c", "", null, "glm-5.1") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NexaCorrelation("c", "d", null, "") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Noop recorder 는 아무 것도 하지 않는다(미연동 안전)`() {
        NexaCorrelationRecorderPort.Noop.record(NexaCorrelation("c", "d", null, "m")) // 예외 없이 통과
    }
}
