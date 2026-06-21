package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.out.DiscordSendPort
import com.discordassistant.central.actionruntime.application.port.out.OutboundSendCommand
import com.discordassistant.central.actionruntime.domain.OutboundDecision
import com.discordassistant.central.actionruntime.domain.OutboundGuard
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode

/**
 * shadow outbound **hard block** 전송 경계(NEXA-P09-T008, actionruntime application).
 *
 * participation 결정이 actionruntime 으로 넘어와도, **현재 [ShadowMode] 가 실제 전송을 허용할 때만**
 * [DiscordSendPort] 를 호출한다. 차단 단계(OFF/OBSERVE_ONLY/SHADOW_PREDICT)에서는 **전송 port 를 절대 호출하지
 * 않는다**(구조적 차단 — "shadow = 미발화 관찰" 안전 핵심).
 *
 * **acceptance(T008) — mock 호출 0회**: SHADOW_PREDICT 결정이 [dispatch] 로 넘어가도 [send] 가 호출되지 않음을
 * 통합 테스트로 검증한다([com.discordassistant.central.actionruntime.application.ShadowOutboundDispatcherTest]).
 *
 * 차단은 [OutboundGuard](순수 도메인)가 판정한다 — 이 클래스는 그 결정에 따라 port 호출/skip 만 한다(분기 단순).
 * 차단된 전송은 조용히 drop 하지 않고 [OutboundDispatchResult.Blocked] 로 호출자에게 알린다(관찰·기록 가능).
 *
 * 순수성 경계: application 레이어 — 포트·도메인 타입만. Spring/JPA/JDA 미참조.
 */
class ShadowOutboundDispatcher(
    private val discordSend: DiscordSendPort,
) {
    /**
     * [mode] 에서 [command] 전송을 시도한다. 실제 전송이 허용되면 [DiscordSendPort.send] 를 호출하고
     * [OutboundDispatchResult.Sent], 차단되면 port 를 **호출하지 않고** [OutboundDispatchResult.Blocked] 를 돌려준다.
     */
    fun dispatch(
        mode: ShadowMode,
        command: OutboundSendCommand,
    ): OutboundDispatchResult =
        when (OutboundGuard.decide(mode)) {
            OutboundDecision.ALLOW -> {
                discordSend.send(command)
                OutboundDispatchResult.Sent
            }
            // hard block: 전송 port 를 호출하지 않는다(mock 호출 0회 — acceptance T008).
            OutboundDecision.BLOCK -> OutboundDispatchResult.Blocked(mode)
        }
}

/**
 * 전송 시도 결과(application 값 객체). 호출자가 실제 전송/차단을 구분해 기록·관찰할 수 있다.
 */
sealed interface OutboundDispatchResult {
    /** 실제 전송됨(CANARY/LIVE). */
    data object Sent : OutboundDispatchResult

    /** shadow 단계라 전송 차단됨(어떤 단계에서 막혔는지 보존). */
    data class Blocked(
        val mode: ShadowMode,
    ) : OutboundDispatchResult
}
