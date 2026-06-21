package com.discordassistant.central.conversation.domain.service.topic

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import java.time.Duration
import java.time.Instant

/**
 * 주제 구간 baseline(NEXA-P05-T013, 순수 함수). **임베딩·GLM 호출 없이** reply graph 연속성, 시간 gap, keyword
 * 변화만으로 topic segment 후보를 만든다 — 같은 주제로 이어지는 burst 들을 한 [TopicSegment] 로 묶는다.
 *
 * 경계 신호(KISS baseline) — 직전 burst 와 다음 신호 중 하나라도 강하면 새 segment 를 연다:
 * 1. **시간 gap**: 직전 burst 와의 시작 간격이 [TopicSegmentConfig.maxGap] 를 초과하면 경계(대화 끊김).
 * 2. **reply 단절**: 다음 burst 가 직전 segment 의 어떤 burst 에도 reply 로 이어지지 않으면 경계 후보.
 * 3. **keyword 변화**: 직전 burst 와 keyword 자카드 유사도가 [TopicSegmentConfig.minKeywordOverlap] 미만이면 경계.
 *
 * keyword 는 **상류가 추출한 코드/토큰 집합** 으로 받는다 — 이 단계는 원문을 보지 않고(원문 비참조) 토큰 집합의
 * 겹침만 계산한다. 옵트아웃 사용자의 content-derived keyword 는 상류에서 빈 집합으로 전달돼 이 단계는 시간/reply
 * 신호만 쓴다(content feature 미사용).
 *
 * **acceptance(T013) — GLM 호출 없음**: 이 객체는 어떤 외부 호출도 하지 않는다(순수 함수, 표준 라이브러리만).
 *
 * 순수성: Spring/JPA/JDA 미참조. 상태 없음(모든 입력을 인자로 받는다).
 */
object TopicSegmentBaseline {
    /**
     * 시간순으로 정렬된 [inputs] 를 topic segment 후보로 분할한다. 첫 burst 는 항상 새 segment 를 연다.
     *
     * @param inputs 같은 위치의 burst topic 입력들(시간순 — 호출자가 정렬해 넘긴다, 미래 비참조).
     * @param config 경계 임계값.
     */
    fun segment(
        inputs: List<TopicBurstInput>,
        config: TopicSegmentConfig = TopicSegmentConfig.DEFAULT,
    ): List<TopicSegment> {
        if (inputs.isEmpty()) return emptyList()

        val segments = mutableListOf<MutableTopicSegment>()
        var current: MutableTopicSegment? = null

        for (input in inputs) {
            val prev = current
            val boundary = prev == null || isBoundary(prev, input, config)
            if (boundary) {
                current = MutableTopicSegment(ordinal = segments.size, first = input)
                segments.add(current)
            } else {
                prev!!.add(input)
            }
        }
        return segments.map { it.toSegment() }
    }

    /** 직전 segment([prev])와 다음 [next] 사이에 주제 경계가 있는지 — gap·reply 단절·keyword 변화 중 하나라도 강하면 true. */
    private fun isBoundary(
        prev: MutableTopicSegment,
        next: TopicBurstInput,
        config: TopicSegmentConfig,
    ): Boolean {
        // 1. 시간 gap — 직전 burst 마지막 시각과 다음 시작 간격.
        val gap = Duration.between(prev.lastAt, next.startedAt)
        if (gap > config.maxGap) return true

        // 2. reply 단절 — 다음 burst 가 직전 segment 의 burst 에 reply 로 이어지면 같은 주제(경계 아님).
        if (next.replyToBurst != null && next.replyToBurst in prev.burstIds) return false

        // 3. keyword 변화 — 자카드 유사도가 임계 미만이면 경계. 양쪽 중 하나라도 빈 키워드면 content 신호 없음으로
        //    간주해 keyword 로는 경계를 만들지 않는다(시간/reply 신호만으로 위에서 이미 판정됨).
        if (prev.keywords.isEmpty() || next.keywords.isEmpty()) return false
        return jaccard(prev.keywords, next.keywords) < config.minKeywordOverlap
    }

    /** 두 토큰 집합의 자카드 유사도(교집합/합집합). 합집합이 비면 0(겹침 신호 없음). */
    private fun jaccard(
        a: Set<String>,
        b: Set<String>,
    ): Double {
        val union = a.size + b.size - a.count { it in b }
        if (union == 0) return 0.0
        val intersection = a.count { it in b }
        return intersection.toDouble() / union.toDouble()
    }
}

/** 가변 누적용 내부 헬퍼(segment 빌드 중 상태). 외부로 노출되지 않는다(불변 [TopicSegment] 로 변환 후 반환). */
private class MutableTopicSegment(
    val ordinal: Int,
    first: TopicBurstInput,
) {
    private val members = mutableListOf(first)
    val burstIds: MutableSet<BurstId> = mutableSetOf(first.burstId)
    var keywords: Set<String> = first.keywords
    var lastAt: Instant = first.lastFragmentAt

    fun add(input: TopicBurstInput) {
        members.add(input)
        burstIds.add(input.burstId)
        keywords = keywords + input.keywords
        lastAt = input.lastFragmentAt
    }

    fun toSegment(): TopicSegment =
        TopicSegment(
            ordinal = ordinal,
            burstIds = members.map { it.burstId },
            startedAt = members.first().startedAt,
            endedAt = lastAt,
        )
}

/**
 * 주제 분할의 입력 단위(순수 도메인 값 객체). 원문이 아니라 burst 식별자·시각·reply 연결·**추출된 keyword 토큰**만
 * 담는다 — 이 단계는 원문을 보지 않는다(content feature 는 상류가 토큰 집합으로 추려 넣는다, 옵트아웃이면 빈 집합).
 */
data class TopicBurstInput(
    val burstId: BurstId,
    val startedAt: Instant,
    val lastFragmentAt: Instant,
    /** 이 burst 가 reply 로 가리킨 대상 burst(없으면 null). reply 연속성 판정용. */
    val replyToBurst: BurstId? = null,
    /** 상류가 추출한 keyword 토큰 집합(원문 비포함). 옵트아웃 사용자면 빈 집합(content 신호 미사용). */
    val keywords: Set<String> = emptySet(),
)

/**
 * 주제 구간 후보(NEXA-P05-T013, 순수 도메인 값 객체·불변). 같은 주제로 이어진 burst 묶음 — 원문 미포함, 식별자·시각만.
 */
data class TopicSegment(
    /** 위치 내 segment 순번(0-based, 결정론적). */
    val ordinal: Int,
    /** 이 segment 에 속한 burst 식별자(시간순). 항상 1개 이상. */
    val burstIds: List<BurstId>,
    val startedAt: Instant,
    val endedAt: Instant,
) {
    init {
        require(burstIds.isNotEmpty()) { "topic segment 는 최소 1개 burst 를 가진다(빈 segment 금지)" }
        require(ordinal >= 0) { "ordinal 은 음수일 수 없다" }
    }
}

/**
 * 주제 분할 baseline 설정(주입 — 순수 함수가 상태를 갖지 않도록 인자로 받는다).
 *
 * 모든 임계는 임베딩·GLM 없이 평가된다(acceptance T013).
 */
data class TopicSegmentConfig(
    /** 직전 burst 마지막 시각과 다음 시작 간격이 이를 초과하면 주제 경계(대화 끊김). */
    val maxGap: Duration,
    /** keyword 자카드 유사도가 이 미만이면 주제 변화 경계로 본다([0,1]). */
    val minKeywordOverlap: Double = 0.2,
) {
    init {
        require(!maxGap.isNegative && !maxGap.isZero) { "maxGap 은 양수여야 한다" }
        require(minKeywordOverlap in 0.0..1.0) { "minKeywordOverlap 은 [0,1] 범위여야 한다" }
    }

    companion object {
        /** 기본 — 10분 gap, keyword 겹침 0.2 미만이면 주제 변화. */
        val DEFAULT: TopicSegmentConfig = TopicSegmentConfig(maxGap = Duration.ofMinutes(10))
    }
}
