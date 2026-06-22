package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.out.DiscordSendPort
import com.discordassistant.central.actionruntime.application.port.out.OutboundSendCommand
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * shadow outbound hard block(NEXA-P09-T008) acceptance 통합 테스트.
 *
 * **acceptance(T008) — mock 호출 0회**: SHADOW_PREDICT(및 OFF/OBSERVE_ONLY) 결정이 actionruntime 전송 경계로
 * 넘어가도 Discord executor([DiscordSendPort])가 **한 번도** 호출되지 않음을 검증한다. 손으로 만든 호출 카운트
 * fake 로 검증한다(외부 mock 프레임워크 불필요 — 검증이 명시적).
 */
class ShadowOutboundDispatcherTest {
    private val command = OutboundSendCommand(correlationId = "corr-1", channelId = "chan-1", speechPlanRef = "plan-1")

    /** 호출 카운트 fake — send 가 몇 번 불렸는지 정확히 센다(mock 호출 0회 검증). */
    private class CountingDiscordSend : DiscordSendPort {
        var calls = 0
            private set

        override fun send(command: OutboundSendCommand) {
            calls++
        }
    }

    @Test
    fun `acceptance — SHADOW_PREDICT 는 Discord executor 를 0회 호출한다`() {
        val send = CountingDiscordSend()
        val dispatcher = ShadowOutboundDispatcher(send)
        val result = dispatcher.dispatch(ShadowMode.SHADOW_PREDICT, command)
        assertThat(send.calls).isZero() // 미발화 — 절대 전송 안 함
        assertThat(result).isInstanceOf(OutboundDispatchResult.Blocked::class.java)
        assertThat((result as OutboundDispatchResult.Blocked).mode).isEqualTo(ShadowMode.SHADOW_PREDICT)
    }

    @Test
    fun `OFF·OBSERVE_ONLY 도 전송을 0회 호출한다`() {
        val send = CountingDiscordSend()
        val dispatcher = ShadowOutboundDispatcher(send)
        dispatcher.dispatch(ShadowMode.OFF, command)
        dispatcher.dispatch(ShadowMode.OBSERVE_ONLY, command)
        assertThat(send.calls).isZero()
    }

    @Test
    fun `CANARY·LIVE 만 실제 전송을 호출한다`() {
        val send = CountingDiscordSend()
        val dispatcher = ShadowOutboundDispatcher(send)
        assertThat(dispatcher.dispatch(ShadowMode.CANARY, command)).isEqualTo(OutboundDispatchResult.Sent)
        assertThat(dispatcher.dispatch(ShadowMode.LIVE, command)).isEqualTo(OutboundDispatchResult.Sent)
        assertThat(send.calls).isEqualTo(2)
    }
}
