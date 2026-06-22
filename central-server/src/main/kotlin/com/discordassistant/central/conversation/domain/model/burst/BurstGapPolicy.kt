package com.discordassistant.central.conversation.domain.model.burst

import java.time.Duration

/**
 * 채널별 버스트 gap 설정(NEXA-P04-T005, 순수 도메인 value object). 서버 문화 학습(P04 후반) 전에 segmenter 가
 * 쓰는 최소/최대/기본 gap 을 담는다. 같은 작성자의 두 조각 간격이 이 gap 이하면 같은 버스트, 초과하면 경계다.
 *
 * 순수성: Spring/JPA/JDA 타입을 참조하지 않는다 — 표준 [Duration] 만 쓴다.
 *
 * **acceptance(T005) — 안전한 기본값**: 설정 부재 시 [DEFAULT] 가 적용된다(config 포트가 null 이면 호출자가 이 기본을
 * 쓴다). [maxGap] 은 hard ceiling 으로, typing 연장(T010)이나 동적 feature(T006)가 늘려도 이 값을 못 넘는다 —
 * typing 이벤트 유실 시에도 무한 연기되지 않게 하는 안전장치다. [minGap] 은 floor(너무 짧게 깨지 않도록).
 */
data class BurstGapPolicy(
    /** 같은 작성자 조각을 같은 버스트로 묶는 기본 허용 간격(baseline segmenter 기준, T004). */
    val defaultGap: Duration,
    /** 동적 조정이 줄일 수 있는 하한(이보다 짧게는 안 깬다). */
    val minGap: Duration,
    /** 동적 조정·typing 연장이 늘려도 못 넘는 상한(hard deadline 안전장치, T010). */
    val maxGap: Duration,
) {
    init {
        require(!minGap.isNegative && !minGap.isZero) { "minGap 은 양수여야 한다" }
        require(defaultGap >= minGap) { "defaultGap 은 minGap 이상이어야 한다" }
        require(maxGap >= defaultGap) { "maxGap 은 defaultGap 이상이어야 한다" }
    }

    /** [candidate] gap 을 [minGap, maxGap] 범위로 자른다(동적 조정·연장 결과를 안전 범위로 강제). */
    fun clamp(candidate: Duration): Duration =
        when {
            candidate < minGap -> minGap
            candidate > maxGap -> maxGap
            else -> candidate
        }

    companion object {
        /**
         * 설정 부재 시 적용되는 안전 기본값. 짧은 연속 채팅(수 초 간격)을 묶되, 수십 초 이상 침묵은 경계로 본다.
         * 한국어 짧은 조각 채팅 특성(닉네임-버스트 fixture: 1초 간격 연속)을 고려한 보수적 baseline.
         */
        val DEFAULT: BurstGapPolicy =
            BurstGapPolicy(
                defaultGap = Duration.ofSeconds(7),
                minGap = Duration.ofSeconds(2),
                maxGap = Duration.ofSeconds(30),
            )
    }
}
