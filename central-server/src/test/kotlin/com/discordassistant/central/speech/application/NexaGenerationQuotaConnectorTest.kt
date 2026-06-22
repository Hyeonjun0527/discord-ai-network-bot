package com.discordassistant.central.speech.application

import com.discordassistant.central.speech.application.generation.CandidateGenerationService
import com.discordassistant.central.speech.application.generation.ReasoningModeSelector
import com.discordassistant.central.speech.application.generation.SpeechGenerationGate
import com.discordassistant.central.speech.application.generation.SpeechTrigger
import com.discordassistant.central.speech.application.port.out.GenerationQuotaPort
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import com.discordassistant.central.speech.generation.SpeechGenerationFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T011 generation-only quota 차감 연결자 acceptance 단위 테스트.
 *
 * 핵심 acceptance: **IGNORE/REACT·생성 전 취소는 quota 를 소비하지 않는다**(reserve 0회). SPEAK 성공만 차감.
 */
class NexaGenerationQuotaConnectorTest {
    private val packet = SpeechGenerationFixtures.packet()

    @Test
    fun `acceptance — IGNORE 는 reserve 도 차감도 0`() {
        val quota = CountingQuota()
        val result = connector(EmptyPort(), quota).run(SpeechTrigger.IGNORE)
        assertThat(quota.reserves).isZero()
        assertThat(quota.charges).isZero()
        assertThat(result.reserved).isFalse()
        assertThat(result.quotaCharged).isFalse()
    }

    @Test
    fun `acceptance — REACT·WAIT 도 reserve 0`() {
        for (trigger in listOf(SpeechTrigger.REACT, SpeechTrigger.WAIT, SpeechTrigger.OTHER)) {
            val quota = CountingQuota()
            connector(EmptyPort(), quota).run(trigger)
            assertThat(quota.reserves).withFailMessage("%s must not reserve", trigger).isZero()
        }
    }

    @Test
    fun `acceptance — stale SPEAK(생성 전 취소)는 reserve 0`() {
        val quota = CountingQuota()
        connector(NonEmptyPort(), quota).run(SpeechTrigger.SPEAK, stale = true)
        assertThat(quota.reserves).isZero()
    }

    @Test
    fun `SPEAK 성공이면 reserve 1·확정 차감 1`() {
        val quota = CountingQuota()
        val result = connector(NonEmptyPort(), quota).run(SpeechTrigger.SPEAK)
        assertThat(quota.reserves).isEqualTo(1)
        assertThat(quota.charges).isEqualTo(1)
        assertThat(quota.refunds).isZero()
        assertThat(result.quotaCharged).isTrue()
    }

    @Test
    fun `SPEAK 이지만 생성 무응답이면 reserve 후 환불(부당 차감 방지)`() {
        val quota = CountingQuota()
        val result = connector(EmptyPort(), quota).run(SpeechTrigger.SPEAK)
        assertThat(quota.reserves).isEqualTo(1)
        assertThat(quota.charges).isZero()
        assertThat(quota.refunds).isEqualTo(1)
        assertThat(result.quotaCharged).isFalse()
    }

    @Test
    fun `한도 초과(reserve 실패)면 generation 호출도 차감도 없다`() {
        val quota = CountingQuota(allowReserve = false)
        val port = NonEmptyPort()
        val result = connector(port, quota).run(SpeechTrigger.SPEAK)
        assertThat(port.calls).isZero() // 발화 보류 — 모델 비용 없음
        assertThat(quota.charges).isZero()
        assertThat(result.gate.invokedGeneration).isFalse()
    }

    private fun connector(
        port: SpeechGenerationPort,
        quota: GenerationQuotaPort,
    ): Harness {
        val service =
            CandidateGenerationService(
                generationPort = port,
                socialActCompiler = SocialActPromptCompiler(),
                burstCompiler = BurstPromptCompiler(),
                reasoningModeSelector = ReasoningModeSelector(),
            )
        return Harness(NexaGenerationQuotaConnector(SpeechGenerationGate(service), quota), packet)
    }

    private inner class Harness(
        val connector: NexaGenerationQuotaConnector,
        val packet: com.discordassistant.central.speech.domain.model.SpeechScenePacket,
    ) {
        fun run(
            trigger: SpeechTrigger,
            stale: Boolean = false,
        ): QuotaGateResult =
            connector.generateWithQuota(
                trigger = trigger,
                guildId = 7L,
                userId = 42L,
                correlationId = "corr-1",
                packet = packet,
                stale = stale,
            )
    }

    private class CountingQuota(
        private val allowReserve: Boolean = true,
    ) : GenerationQuotaPort {
        var reserves = 0
        var charges = 0
        var refunds = 0

        override fun reserve(
            guildId: Long,
            userId: Long,
            correlationId: String,
        ): Boolean {
            reserves++
            return allowReserve
        }

        override fun settleCharged(
            guildId: Long,
            userId: Long,
            correlationId: String,
        ) {
            charges++
        }

        override fun settleRefund(
            guildId: Long,
            userId: Long,
            correlationId: String,
        ) {
            refunds++
        }
    }

    private class EmptyPort : SpeechGenerationPort {
        override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult = SpeechGenerationResult.EMPTY
    }

    private class NonEmptyPort : SpeechGenerationPort {
        var calls = 0

        override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult {
            calls++
            return SpeechGenerationResult(listOf(SpeechCandidate("c1", listOf("안녕!"))))
        }
    }
}
