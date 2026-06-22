package com.discordassistant.central.speech.safety

import com.discordassistant.central.speech.application.safety.HighRiskClassifier
import com.discordassistant.central.speech.application.safety.HighRiskFallbackBoundary
import com.discordassistant.central.speech.application.safety.HighRiskResponse
import com.discordassistant.central.speech.application.safety.RiskLevel
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P17-T016: 고위험 도움 요청 fallback 경계 — 분류 실패 시 과도한 확신·조롱이 나오지 않는다. */
class HighRiskFallbackBoundaryTest {
    private val boundary = HighRiskFallbackBoundary()

    @Test
    fun `low-risk small talk takes normal path`() {
        val directive = boundary.evaluate(packet("오늘 날씨 좋네 점심 뭐 먹지"))
        assertThat(directive.level).isEqualTo(RiskLevel.LOW)
        assertThat(directive.isNormal).isTrue()
        assertThat(directive.suppressConfidence).isFalse()
    }

    @Test
    fun `self-harm context forbids banter and prefers safe resource`() {
        val directive = boundary.evaluate(packet("요즘 너무 힘들고 죽고 싶어"))
        assertThat(directive.level).isEqualTo(RiskLevel.HIGH)
        assertThat(directive.forbiddenActs).contains(SpeechSocialAct.TEASE)
        assertThat(directive.suppressConfidence).isTrue()
        assertThat(directive.response).isEqualTo(HighRiskResponse.SAFE_RESOURCE)
    }

    @Test
    fun `legal and medical context is high risk`() {
        assertThat(boundary.evaluate(packet("이거 고소 당하면 어떡해")).level).isEqualTo(RiskLevel.HIGH)
        assertThat(boundary.evaluate(packet("약 복용량 두 배로 먹어도 돼?")).level).isEqualTo(RiskLevel.HIGH)
    }

    @Test
    fun `empty scene is uncertain not low — fail-safe`() {
        // 평가할 본문이 없으면 LOW 로 떨어뜨리지 않고 보수적으로 처리.
        val directive = boundary.evaluate(packetEmpty())
        assertThat(directive.level).isEqualTo(RiskLevel.UNCERTAIN)
        assertThat(directive.forbiddenActs).contains(SpeechSocialAct.TEASE)
        assertThat(directive.suppressConfidence).isTrue()
        assertThat(directive.response).isEqualTo(HighRiskResponse.SAFE_RESOURCE)
    }

    @Test
    fun `classifier failure is treated as uncertain not low`() {
        // 분류기가 예외를 던지면(분류 실패) UNCERTAIN 으로 안전 하강 — 조롱·확신 차단.
        val throwingBoundary =
            HighRiskFallbackBoundary(
                classifier = HighRiskClassifier { error("classifier blew up") },
            )
        val directive = throwingBoundary.evaluate(packet("아무 말"))
        assertThat(directive.level).isEqualTo(RiskLevel.UNCERTAIN)
        assertThat(directive.suppressConfidence).isTrue()
        assertThat(directive.forbiddenActs).contains(SpeechSocialAct.TEASE)
    }

    private fun packet(text: String): SpeechScenePacket =
        SpeechScenePacket.of(
            focusThreadKey = "thread_1",
            target = SpeechTarget.NONE,
            recentTurns = listOf(ConversationTurn("user_1", text)),
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            burstShape = SpeechBurstShape(1, 280, false),
            identity = IdentityKernelSection.of("니아", "당신은 「니아」 예요."),
        )

    private fun packetEmpty(): SpeechScenePacket =
        SpeechScenePacket.of(
            focusThreadKey = "thread_1",
            target = SpeechTarget.NONE,
            recentTurns = emptyList(),
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            burstShape = SpeechBurstShape(1, 280, false),
            identity = IdentityKernelSection.of("니아", "당신은 「니아」 예요."),
        )
}
