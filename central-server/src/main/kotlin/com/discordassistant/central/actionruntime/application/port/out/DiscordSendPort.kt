package com.discordassistant.central.actionruntime.application.port.out

/**
 * Discord 실제 전송 아웃바운드 포트(NEXA-P09-T008, actionruntime application 레이어). 실제 발화/리액션을 Discord 로
 * 보내는 executor 추상이다 — 실제 구현(JDA 어댑터)은 LIVE/CANARY 에서만 와이어된다.
 *
 * **shadow 안전 핵심**: 이 포트는 [com.discordassistant.central.actionruntime.application.ShadowOutboundDispatcher]
 * 가 [com.discordassistant.central.participation.domain.model.shadow.ShadowMode] 가 실제 전송을 허용할 때만
 * 호출한다(T008 hard block). shadow 단계에서는 이 포트가 **한 번도** 호출되지 않는다.
 *
 * 순수성 경계: application 레이어 — 값 객체만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface DiscordSendPort {
    /** [command] 를 실제 Discord 로 전송한다. shadow 단계에서는 dispatcher 가 이 메서드를 호출하지 않는다. */
    fun send(command: OutboundSendCommand)
}

/**
 * 실제 전송 명령(application 값 객체·불변). 어느 채널에 무엇을 보낼지의 식별 참조만 담는다(원문 본문은 speech
 * 가 채운 발화 계획 참조로 운반 — 이 계약은 전송 라우팅 메타만).
 */
data class OutboundSendCommand(
    /** 전송 상관 식별자(결정 로그와 연결, 원문 비포함). */
    val correlationId: String,
    /** 대상 채널 식별자(원시 String). */
    val channelId: String,
    /** 전송할 발화 계획 참조(speech 가 만든 실제 문구의 식별 참조 — 이 계약은 본문을 담지 않는다). */
    val speechPlanRef: String,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId 는 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(speechPlanRef.isNotBlank()) { "speechPlanRef 는 비어 있을 수 없다" }
    }
}
