package com.discordassistant.central.conversation.domain.service.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import java.time.Duration

/**
 * 시간 인접성 edge 계산(NEXA-P05-T004, 순수 함수). reply/mention 같은 명시 신호가 없을 때, 발화 간 **시간
 * 간격·화자 교대·채널 tempo** 로 두 burst 사이 "약한 연결" 점수를 매긴다 — 동시 진행되는 여러 대화를 논리
 * 스레드로 가르는(T010) 보조 신호다.
 *
 * **acceptance(T004) — 미래 비참조·최대 창**:
 * - edge 는 **과거 burst → 더 늦은(또는 동시) burst** 방향으로만 본다([later] 가 [earlier] 보다 미래거나 동시).
 *   [later] 가 [earlier] 보다 과거면 입력 오류로 거부한다(미래 burst 를 참조해 점수를 만들지 않는다).
 * - 두 burst 시작 간격이 [TemporalAdjacencyConfig.maxWindow] 를 **초과** 하면 점수 0(= edge 없음).
 *   설정 창 밖으로는 어떤 약한 연결도 만들지 않는다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 [Duration] 만 쓰고 상태가 없다(모든 입력을 인자로 받는다).
 */
object TemporalAdjacency {
    /**
     * [earlier] burst 와 그 뒤(또는 동시)에 시작한 [later] burst 사이 약한 연결 점수 [0,1] 를 계산한다.
     * 0 이면 edge 없음(창 밖이거나 신호 부재). 높을수록 같은 논리 스레드일 가능성이 크다.
     */
    fun score(
        earlier: UtteranceBurst,
        later: UtteranceBurst,
        config: TemporalAdjacencyConfig,
    ): Double {
        val gap = Duration.between(earlier.startedAt, later.startedAt)
        require(!gap.isNegative) { "later 는 earlier 보다 과거일 수 없다(미래 burst 비참조)" }

        // 최대 창 밖이면 어떤 연결도 만들지 않는다(acceptance).
        if (gap > config.maxWindow) return 0.0

        // 시간 근접성: 창 안에서 가까울수록 1.0 에 가깝게 선형 감쇠.
        val proximity = 1.0 - gap.toMillis().toDouble() / config.maxWindow.toMillis().toDouble()

        // 화자 교대(다른 작성자 ↔ 대화)면 가중, 같은 작성자 연속이면 약화.
        val alternation = if (later.authorId != earlier.authorId) config.alternationBoost else config.sameAuthorWeight

        // 채널 tempo: 같은 위치(채널/스레드)면 같은 흐름일 가능성↑, 다른 위치면 0(연결 없음).
        if (later.location != earlier.location) return 0.0

        return (proximity * alternation).coerceIn(0.0, 1.0)
    }

    /**
     * [earlier] → [later] 인접성 edge 를 만든다(점수 > 0 일 때만). 점수가 0(창 밖·다른 위치·신호 없음)이면
     * null — edge 를 만들지 않는다(acceptance: 창 밖 edge 금지).
     */
    fun edgeOrNull(
        earlier: UtteranceBurst,
        later: UtteranceBurst,
        config: TemporalAdjacencyConfig,
    ): AdjacencyEdge? {
        val score = score(earlier, later, config)
        if (score <= 0.0) return null
        return AdjacencyEdge(from = earlier.burstId, to = later.burstId, score = score)
    }
}

/**
 * 시간 인접성 계산 설정(주입 — 순수 함수가 상태를 갖지 않도록 인자로 받는다).
 *
 * [maxWindow] 밖으로는 edge 를 만들지 않는다(hard ceiling). 가중치는 [0,1] 권장이나 점수는 최종 clamp 된다.
 */
data class TemporalAdjacencyConfig(
    /** 두 burst 시작 간격의 최대 창 — 이보다 멀면 edge 없음(점수 0). */
    val maxWindow: Duration,
    /** 화자 교대(다른 작성자)일 때의 가중. */
    val alternationBoost: Double = 1.0,
    /** 같은 작성자 연속일 때의 가중(보통 < alternationBoost). */
    val sameAuthorWeight: Double = 0.5,
) {
    init {
        require(!maxWindow.isNegative && !maxWindow.isZero) { "maxWindow 는 양수여야 한다" }
        require(alternationBoost in 0.0..1.0) { "alternationBoost 는 [0,1] 범위여야 한다" }
        require(sameAuthorWeight in 0.0..1.0) { "sameAuthorWeight 는 [0,1] 범위여야 한다" }
    }

    companion object {
        /** 기본 설정 — 5분 창, 화자 교대 1.0/동일 작성자 0.5. */
        val DEFAULT: TemporalAdjacencyConfig = TemporalAdjacencyConfig(maxWindow = Duration.ofMinutes(5))
    }
}

/** 시간 인접성 directed edge(순수 도메인). [from] burst → [to] burst 의 약한 연결 [score]. */
data class AdjacencyEdge(
    val from: BurstId,
    val to: BurstId,
    val score: Double,
)
