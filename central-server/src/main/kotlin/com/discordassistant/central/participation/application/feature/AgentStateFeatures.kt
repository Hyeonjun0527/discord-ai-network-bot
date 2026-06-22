package com.discordassistant.central.participation.application.feature

import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue

/**
 * agent saturation feature builder(NEXA-P08-T015, application 레이어·순수 함수). NEXA **자신**의 채널 참여 상태
 * (P06 AgentParticipationState 투영)에서 정책 feature 를 만든다 — 최근 NEXA burst 수, burst 점유율(share),
 * 마지막 발화 경과(age), 미해결 pending action 수.
 *
 * **acceptance(T015) — 메시지 수와 burst 수를 혼동하지 않는다**:
 * 입력([AgentStateObservation])은 [nexaBurstCount]·[humanBurstCount] 처럼 **burst 단위** 카운트만 받는다(메시지/
 * 평균이 아니다 — 필드명과 KDoc 이 burst 단위임을 강제). share = nexaBurst / (nexaBurst + humanBurst) 로 burst
 * 점유율을 낸다(SpeakingSaturationCalculator 와 동일 단위). 메시지 개수 합을 곱하지 않는다.
 *
 * 마지막 발화가 없으면([lastSpokeAgeSeconds] = null) age feature 는 missing 으로 둔다(0=방금 과 구분).
 *
 * participation 은 socialmemory/conversation 도메인 타입을 직접 import 하지 않고 읽기 포트가 채운 입력만 본다.
 *
 * 순수성 경계: application 레이어 — 표준 타입·[FeatureId]/[FeatureValue]/카탈로그만. Spring/JPA/JDA 미참조.
 */
object AgentStateFeatures {
    fun build(observation: AgentStateObservation): Map<FeatureId, FeatureValue> {
        val total = observation.nexaBurstCount + observation.humanBurstCount
        // burst 점유율(메시지 수 아님): NEXA burst / 전체 burst. 전체 0 이면 점유 없음(0).
        val share = if (total == 0) 0.0 else observation.nexaBurstCount.toDouble() / total.toDouble()
        return linkedMapOf(
            FeatureCatalog.AGENT_RECENT_BURST_COUNT to FeatureValue.present(observation.nexaBurstCount.toDouble()),
            FeatureCatalog.AGENT_SHARE to FeatureValue.present(share),
            // 마지막 발화 시각이 없으면 age 는 missing(0=방금 과 구분 — 아직 발화한 적 없음).
            FeatureCatalog.AGENT_LAST_SPOKE_AGE_SECONDS to
                (observation.lastSpokeAgeSeconds?.let { FeatureValue.present(it) } ?: FeatureValue.MISSING),
            FeatureCatalog.AGENT_PENDING_ACTION_COUNT to FeatureValue.present(observation.pendingActionCount.toDouble()),
        )
    }
}

/**
 * NEXA 자기 참여 상태 관찰 입력 뷰(application 값 객체). 읽기 포트가 P06 AgentParticipationState 를 수치로 투영해
 * 넘긴다 — **burst 단위 카운트**(메시지 수 아님)·age·pending 수만(원문/식별자 비포함). [lastSpokeAgeSeconds] = null
 * 이면 아직 이 채널에서 발화한 적 없음(빌더가 age 를 missing 으로 처리).
 */
data class AgentStateObservation(
    /** 최근 창의 NEXA **burst** 수(메시지 수 아님 — acceptance: burst 단위). */
    val nexaBurstCount: Int,
    /** 최근 창의 사람(비봇·비옵트아웃) **burst** 수(share 분모). */
    val humanBurstCount: Int,
    /** NEXA 가 마지막으로 발화한 뒤 경과 초(비음수). null 이면 아직 발화 없음(age=missing). */
    val lastSpokeAgeSeconds: Double?,
    /** 미해결 pending action 수(영구 누적 아님 — 만료/해결로 줄어든다). */
    val pendingActionCount: Int,
) {
    init {
        require(nexaBurstCount >= 0) { "nexaBurstCount 는 음수일 수 없다: $nexaBurstCount" }
        require(humanBurstCount >= 0) { "humanBurstCount 는 음수일 수 없다: $humanBurstCount" }
        require(pendingActionCount >= 0) { "pendingActionCount 는 음수일 수 없다: $pendingActionCount" }
        lastSpokeAgeSeconds?.let { require(it >= 0.0) { "lastSpokeAgeSeconds 는 음수일 수 없다: $it" } }
    }
}
