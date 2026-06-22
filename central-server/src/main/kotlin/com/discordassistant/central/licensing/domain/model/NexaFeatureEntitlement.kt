package com.discordassistant.central.licensing.domain.model

/**
 * NEXA 사회적 참여 기능에 대한 라이선스 게이트 판정(NEXA-P15-T015, 순수 도메인·불변).
 *
 * NEXA 의 **실제 발화(CANARY/LIVE)** 는 유료/체험 기능이다(메모리 billing_subscription_plan — 트라이얼웨어).
 * 그러나 라이선스 만료가 **안전·삭제·kill switch 를 끄거나 legacy mention-always 로 되돌리면 안 된다**.
 * 이 게이트는 [Entitlement] 와 운영자가 원하는 "실제 전송 활성화" 의도를 받아, **무엇이 허용되는지**만 판정한다
 * (lane enum 비참조 — 호출자가 lane 으로 번역). licensing 도메인이 participation 에 의존하지 않도록 booleans 로 표현.
 *
 * **acceptance(T015) — 라이선스 만료가 갑자기 legacy mention-always 동작으로 바뀌지 않는다**:
 *  - 유료 접근이 없으면(EXPIRED/REVOKED) [realSendAllowed] 만 false 가 된다(실제 발화 차단) — 관찰/shadow 예측은
 *    그대로 허용([observeAndShadowAllowed] = true). 즉 NEXA 가 *꺼지지* 않고 shadow(미발화)로 **다운그레이드**된다.
 *    legacy(기존 channelai mention-always 자동응답)로 *전환*하지 않는다 — lane 다운그레이드는 운영자 명시 행위다.
 *  - [safetyControlsAlwaysAvailable] 는 라이선스 상태와 무관하게 **항상 true** — kill switch·삭제·동의 철회·lane
 *    하향은 만료/정지 상태에서도 막히지 않는다(safety/삭제/kill switch 는 항상 제공).
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 타입만(licensing.domain 규칙).
 */
data class NexaFeatureEntitlement(
    /** 실제 Discord 전송(CANARY/LIVE 발화)을 새로 켤 수 있는가 — 유료/체험일 때만 true. */
    val realSendAllowed: Boolean,
) {
    /** 관찰·shadow 예측(전송 0)은 라이선스와 무관하게 허용된다 — 만료는 NEXA 를 끄지 않고 shadow 로만 묶는다. */
    val observeAndShadowAllowed: Boolean
        get() = true

    /** safety/삭제/kill switch/동의 철회/lane 하향은 라이선스 상태와 무관하게 항상 제공된다(T015 불변). */
    val safetyControlsAlwaysAvailable: Boolean
        get() = true

    companion object {
        /**
         * [entitlement] 로부터 NEXA 기능 게이트를 판정한다. 유료 접근([Entitlement.hasPaidAccess])이면 실제 전송
         * 활성화 가능, 아니면 실제 전송만 차단된다(관찰/shadow·안전 제어는 유지).
         */
        fun from(entitlement: Entitlement): NexaFeatureEntitlement = NexaFeatureEntitlement(realSendAllowed = entitlement.hasPaidAccess())
    }
}
