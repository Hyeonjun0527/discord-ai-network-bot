package com.discordassistant.central.participation.domain.model.shadow

/**
 * NEXA participation rollout 단계(NEXA-P09-T007, 순수 도메인 enum·버전 관리).
 *
 * 길드별로 NEXA 참여 정책이 **얼마나 실제로 작동하는지**를 단계로 표현한다. 핵심 안전 불변식:
 * **[OFF]~[SHADOW_PREDICT] 는 절대 Discord 로 실제 전송하지 않는다**(미발화 관찰). 실제 발화는 [CANARY] 부터
 * (제한적), [LIVE] 에서(전면) 일어난다.
 *
 * **acceptance(T007) — 기본값은 OFF**: 어떤 길드도 명시 승인 없이는 참여 정책이 돌지 않는다([DEFAULT]=OFF).
 *
 * | 단계 | 정책 평가 | 예측 기록 | **실제 Discord 전송** |
 * | --- | --- | --- | --- |
 * | [OFF] | 안 함 | 안 함 | **절대 안 함** |
 * | [OBSERVE_ONLY] | 안 함(장면만 관찰) | 안 함 | **절대 안 함** |
 * | [SHADOW_PREDICT] | 함(baseline 들 예측) | 함 | **절대 안 함**(hard block — T008) |
 * | [CANARY] | 함 | 함 | 제한적(소수 길드/채널) |
 * | [LIVE] | 함 | 함 | 함 |
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 enum 만.
 */
enum class ShadowMode {
    /** 꺼짐 — 정책 평가도 전송도 없음. 모든 길드의 기본값. */
    OFF,

    /** 관찰만 — 장면/사회 상태는 쌓되 정책 평가는 안 한다. 데이터 적격성 확보 단계. 전송 없음. */
    OBSERVE_ONLY,

    /** shadow 예측 — baseline 정책들이 예측을 내고 기록한다. **실제 전송은 구조적으로 차단**(T008). */
    SHADOW_PREDICT,

    /** canary — 소수 길드/채널에서만 실제 발화(제한적 LIVE). */
    CANARY,

    /** live — 전면 실제 발화. */
    LIVE,
    ;

    /**
     * 이 단계에서 실제 Discord 전송이 **허용되는가**. [OFF]/[OBSERVE_ONLY]/[SHADOW_PREDICT] 는 false —
     * actionruntime 전송 경계(T008)가 이 값으로 hard block 한다(shadow 면 SendToDiscord 미호출).
     */
    val allowsRealSend: Boolean
        get() = this == CANARY || this == LIVE

    /**
     * Two rollout constraints can only reduce authority. This intentionally does not use enum ordinal: OFF,
     * OBSERVE_ONLY, and SHADOW_PREDICT all deny real sends but preserve their distinct operational meaning.
     */
    fun restrictiveIntersection(other: ShadowMode): ShadowMode =
        when {
            this == OFF || other == OFF -> OFF
            this == OBSERVE_ONLY || other == OBSERVE_ONLY -> OBSERVE_ONLY
            this == SHADOW_PREDICT || other == SHADOW_PREDICT -> SHADOW_PREDICT
            this == CANARY || other == CANARY -> CANARY
            else -> LIVE
        }

    /**
     * 이 단계에서 정책 평가/예측을 **수행하는가**. [SHADOW_PREDICT] 부터 true — [OFF]/[OBSERVE_ONLY] 는 평가 안 함.
     */
    val evaluatesPolicy: Boolean
        get() = this == SHADOW_PREDICT || this == CANARY || this == LIVE

    companion object {
        /** acceptance(T007) — 기본값은 OFF. 명시 승인 전에는 어떤 정책도 돌지 않는다. */
        val DEFAULT: ShadowMode = OFF
    }
}
