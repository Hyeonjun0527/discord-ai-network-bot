package com.discordassistant.central.actionruntime.domain

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode

/**
 * shadow outbound hard block 의 **순수 결정 코어**(NEXA-P09-T008, 순수 도메인 서비스).
 *
 * participation 이 낸 결정이 actionruntime 으로 넘어와도, **현재 [ShadowMode] 가 실제 전송을 허용하지 않으면
 * Discord 전송을 구조적으로 차단**한다. "shadow = 미발화 관찰" 안전 핵심: SHADOW_PREDICT 에서는 Discord
 * executor 가 **절대** 호출되지 않는다.
 *
 * **acceptance(T008) — mock 호출 0회**: [ShadowOutboundDispatcher] 가 이 가드의 [decide] 결과로만 전송 port 를
 * 호출하므로, SHADOW_PREDICT/OBSERVE_ONLY/OFF 에서는 전송 port 가 한 번도 호출되지 않는다(통합 테스트로 검증).
 *
 * 순수성: Spring/JPA/JDA 미참조. participation 도메인 enum 만 참조(같은 NEXA 도메인 모델).
 */
object OutboundGuard {
    /**
     * [mode] 에서 실제 Discord 전송이 허용되는지 판정한다. [ShadowMode.allowsRealSend] 가 SSOT —
     * OFF/OBSERVE_ONLY/SHADOW_PREDICT 는 BLOCK, CANARY/LIVE 는 ALLOW.
     */
    fun decide(mode: ShadowMode): OutboundDecision = if (mode.allowsRealSend) OutboundDecision.ALLOW else OutboundDecision.BLOCK
}

/**
 * outbound 전송 허용 판정(순수 도메인 enum). [BLOCK] 이면 전송 경계가 Discord executor 를 호출하지 않는다.
 */
enum class OutboundDecision {
    /** 실제 전송 허용(CANARY/LIVE). */
    ALLOW,

    /** 실제 전송 차단(OFF/OBSERVE_ONLY/SHADOW_PREDICT — shadow 미발화). */
    BLOCK,
    ;

    val isBlocked: Boolean
        get() = this == BLOCK
}
