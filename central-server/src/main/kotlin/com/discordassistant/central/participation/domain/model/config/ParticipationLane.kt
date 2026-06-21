package com.discordassistant.central.participation.domain.model.config

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode

/**
 * NEXA participation 운영 lane 선택(NEXA-P15-T002, 순수 도메인 enum).
 *
 * 길드/채널 관리자가 고르는 **사람이 읽는 4-way 선택**이다. 내부 정책 단계([ShadowMode])와 1:1 로 매핑하되,
 * "legacy"(=NEXA 끔, 기존 channelai 자동응답만)를 **명시 기본값**으로 둔다 — 이름이 OFF 가 아니라 LEGACY 라
 * 운영자에게 "기존 동작이 그대로 산다"는 의미를 드러낸다(회귀 0 의 의도 표현).
 *
 * **acceptance(T002) — 기본 migration 값이 legacy/OFF 로 기존 동작을 보존한다**: [DEFAULT] = [LEGACY],
 * [LEGACY.shadowMode] = [ShadowMode.OFF]. flag 가 설정되지 않은 모든 길드/채널은 LEGACY → NEXA 정책이 평가되지
 * 않고([ShadowMode.evaluatesPolicy] false) 전송도 없다([ShadowMode.allowsRealSend] false). 즉 기존
 * channelai 자동응답 경로만 동작한다.
 *
 * | lane | 의미 | [ShadowMode] |
 * | --- | --- | --- |
 * | [LEGACY] | NEXA 끔 — 기존 channelai 자동응답만(기본) | [ShadowMode.OFF] |
 * | [SHADOW] | NEXA 정책 예측·기록만, **전송 없음**(hard block) | [ShadowMode.SHADOW_PREDICT] |
 * | [CANARY] | 소수 채널 실제 발화(제한적) | [ShadowMode.CANARY] |
 * | [LIVE] | 전면 실제 발화 | [ShadowMode.LIVE] |
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 enum 만.
 */
enum class ParticipationLane(
    /** 이 lane 이 내부적으로 동작하는 정책 단계. */
    val shadowMode: ShadowMode,
) {
    /** NEXA 끔 — 기존 channelai 자동응답만(모든 길드/채널 기본). */
    LEGACY(ShadowMode.OFF),

    /** NEXA 정책 예측·기록만, 실제 Discord 전송은 구조적 차단. */
    SHADOW(ShadowMode.SHADOW_PREDICT),

    /** canary — 소수 채널 실제 발화(제한적 LIVE). */
    CANARY(ShadowMode.CANARY),

    /** live — 전면 실제 발화. */
    LIVE(ShadowMode.LIVE),
    ;

    /** 이 lane 에서 NEXA 가 실제 발화하는가([CANARY]/[LIVE]). */
    val allowsRealSend: Boolean
        get() = shadowMode.allowsRealSend

    /** 이 lane 에서 NEXA 정책을 평가하는가([SHADOW] 부터). LEGACY 는 평가하지 않는다(기존 경로만). */
    val evaluatesPolicy: Boolean
        get() = shadowMode.evaluatesPolicy

    companion object {
        /** acceptance(T002) — 기본 lane 은 LEGACY(=OFF). 명시 승인 전엔 기존 동작만 산다. */
        val DEFAULT: ParticipationLane = LEGACY

        /** 내부 [ShadowMode] → 운영 lane(상태 표시·전이용). */
        fun fromShadowMode(mode: ShadowMode): ParticipationLane = entries.firstOrNull { it.shadowMode == mode } ?: LEGACY
    }
}
