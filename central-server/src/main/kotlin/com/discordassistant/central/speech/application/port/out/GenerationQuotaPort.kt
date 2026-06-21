package com.discordassistant.central.speech.application.port.out

/**
 * NEXA 발화 generation 의 quota 차감 아웃바운드 포트(NEXA-P15-T011, provider-neutral·좁은 포트).
 *
 * speech 가 quota 의 내부 모델(역할 한도·일일 카운트)을 직접 알지 않고도 **generation 1건당 reserve/settle** 만
 * 하도록 하는 anti-corruption 경계다. 구현은 quota 도메인(adapter)이 채운다.
 *
 * **acceptance(T011) — IGNORE/REACT·생성 전 취소는 quota 를 소비하지 않는다**: 이 포트는 **generation 직전에만**
 * 호출된다([com.discordassistant.central.speech.application.NexaGenerationQuotaConnector]). reserve 후
 * 성공/실패/취소에 따라 settle 한다 — 모델 호출이 일어나지 않은 경로(IGNORE/REACT/생성 전 취소)는 reserve 자체가
 * 일어나지 않아 차감이 0 이다(quota-boundary.md 불변식 1).
 *
 * 기본 [Noop] 은 아무것도 하지 않아 단위 테스트·연동 미구성 환경을 막지 않는다.
 */
interface GenerationQuotaPort {
    /**
     * [guildId]/[userId] 의 generation 1건을 **예약**한다(차감 후보). [correlationId] 로 중복 차감을 막는다(멱등 —
     * 같은 correlation 재예약은 두 번 차감하지 않는다). 예약 성공 여부를 돌려준다(한도 초과면 false → 호출자가 발화 보류).
     */
    fun reserve(
        guildId: Long,
        userId: Long,
        correlationId: String,
    ): Boolean

    /** 예약을 **확정 차감**한다(generation 성공 — 모델 비용 발생). */
    fun settleCharged(
        guildId: Long,
        userId: Long,
        correlationId: String,
    )

    /**
     * 예약을 **취소/환불**한다(generation 호출 실패·정책 외 사유로 응답 없음 — 부당 차감 방지, quota-boundary.md
     * 불변식 3). 정책 차단(한도)으로 애초에 reserve 가 false 였던 경우는 호출하지 않는다.
     */
    fun settleRefund(
        guildId: Long,
        userId: Long,
        correlationId: String,
    )

    /** no-op 기본 구현(연동 미구성·테스트). 항상 reserve 허용. */
    object Noop : GenerationQuotaPort {
        override fun reserve(
            guildId: Long,
            userId: Long,
            correlationId: String,
        ): Boolean = true

        override fun settleCharged(
            guildId: Long,
            userId: Long,
            correlationId: String,
        ) = Unit

        override fun settleRefund(
            guildId: Long,
            userId: Long,
            correlationId: String,
        ) = Unit
    }
}
