package com.discordassistant.central.participation.adapter.outbound.policy.baseline

/**
 * [BurstAwareHeuristicPolicy] 의 가중치 **versioned config**(NEXA-P09-T005).
 *
 * **acceptance(T005) — 가중치가 설정 파일에 숨지 않고 versioned config 로 저장된다**:
 * 가중치를 코드 곳곳의 매직넘버나 외부 properties 파일(추적 어려움)에 숨기지 않고, [version] 을 가진 **명시적
 * 값 객체**로 둔다 — 어떤 가중치 set 으로 나온 결정인지 [version] 으로 추적/재현하고, 변경 시 버전이 올라가
 * shadow 비교가 섞이지 않는다.
 *
 * 각 가중치는 해당 사회 신호가 "발화 쪽으로 미는" 정도다(클수록 SPEAK 경향). 합은 정규화되지 않아도 되며
 * (점수→로지스틱), [version] 이 다르면 다른 정책 식별자([BurstAwareHeuristicPolicy.modelVersion])로 본다.
 */
data class BurstAwareWeights(
    /** 가중치 set 버전(변경 시 +1 — shadow 비교 분리·재현 키). */
    val version: Int,
    /** 인간 burst 가 끝나 NEXA 가 끼어들 틈이 생긴 정도(틈↑ → 발화↑). */
    val burstEnded: Double,
    /** 다른 인간이 이미 응답한 정도(이미 응답↑ → 발화↓, 음수 가중). */
    val otherHumanResponded: Double,
    /** 채널 tempo(느릴수록 끼어들 여유↑ → 발화↑). */
    val channelTempo: Double,
    /** NEXA 가 직접 대상(멘션·focus)인 정도(직접 대상↑ → 발화↑). */
    val directlyAddressed: Double,
    /** bias(기본 침묵 성향 — 음수면 신호 없을 때 침묵). */
    val bias: Double,
) {
    init {
        require(version >= 1) { "version 은 1 이상이어야 한다: $version" }
    }

    companion object {
        /**
         * 기본 가중치 set v1. 직접 대상·burst 종료가 강하게 밀고, 다른 인간이 이미 응답했으면 끌어내리며,
         * bias 가 음수라 신호가 약하면 침묵으로 수렴한다(보수적 baseline).
         */
        val V1: BurstAwareWeights =
            BurstAwareWeights(
                version = 1,
                burstEnded = 1.2,
                otherHumanResponded = -1.5,
                channelTempo = 0.8,
                directlyAddressed = 2.5,
                bias = -1.0,
            )
    }
}
