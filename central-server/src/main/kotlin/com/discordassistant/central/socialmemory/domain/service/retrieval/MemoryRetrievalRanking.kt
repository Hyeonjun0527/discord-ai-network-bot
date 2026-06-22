package com.discordassistant.central.socialmemory.domain.service.retrieval

import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import java.time.Duration
import java.time.Instant

/**
 * 기억 retrieval **랭킹** 규칙(NEXA-P07-T019, 순수 도메인 서비스). validity·confidence·recency·relation·topic
 * relevance 를 결합해 점수를 계산한다.
 *
 * **acceptance(T019) — expired/conflicted/deleted 기억은 점수와 무관하게 필터링된다**: [rank] 는 점수 계산 **전에**
 * [TemporalFact.isRetrievableAt] (status=ACTIVE 이고 valid 구간 안)로 거른다 — SUPERSEDED/CONFLICTED/INVALIDATED/
 * EXPIRED 나 valid 구간 밖 사실은 confidence·recency 가 아무리 높아도 결과에 들어오지 않는다. 가시성 스코프가
 * 안 맞아도 제외된다(T011).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다.
 */
object MemoryRetrievalRanking {
    /**
     * [facts] 를 [now] 기준 유효성·가시성으로 거른 뒤 점수 내림차순으로 돌려준다. [subjectFilter] 가 있으면 그
     * 주어만, [topicTokens] 가 있으면 predicate/object 토큰 겹침을 topic relevance 로 가중한다.
     */
    fun rank(
        facts: List<TemporalFact>,
        requesterScope: VisibilityScope,
        now: Instant,
        subjectFilter: String? = null,
        topicTokens: Set<String> = emptySet(),
    ): List<RankedMemory> =
        facts
            .asSequence()
            // 점수와 무관하게 먼저 필터(acceptance T019): 유효성·가시성·주어.
            .filter { it.isRetrievableAt(now) }
            .filter { it.visibility.isVisibleTo(requesterScope) }
            .filter { subjectFilter == null || it.subject == subjectFilter }
            .map { fact -> RankedMemory(fact, score(fact, now, topicTokens)) }
            .sortedWith(compareByDescending<RankedMemory> { it.score }.thenBy { it.fact.id })
            .toList()

    /**
     * **과거 회상(historical recall)** 랭킹(NEXA-P07-T021). 기본 [rank] 가 현재 retrieval(ACTIVE 만)인 것과 달리,
     * 이 함수는 [asOf] 시점에 **유효했던** 사실을 status 와 무관하게 surface 한다 — 그 시점에 닫힌(SUPERSEDED) 과거
     * 사실도 그때는 유효했으므로 회상에 쓸 수 있다(acceptance T021: 과거 회상 시 이전 닉네임 사용 가능). 단, 출처가
     * 무효화/만료된 기억(INVALIDATED/EXPIRED)은 과거라도 제외한다(삭제·TTL 은 회상에서도 살아나지 않는다).
     */
    fun recallAt(
        facts: List<TemporalFact>,
        requesterScope: VisibilityScope,
        asOf: Instant,
        subjectFilter: String? = null,
        topicTokens: Set<String> = emptySet(),
    ): List<RankedMemory> =
        facts
            .asSequence()
            .filter { it.status != MemoryStatus.INVALIDATED && it.status != MemoryStatus.EXPIRED }
            .filter { it.isValidAt(asOf) }
            .filter { it.visibility.isVisibleTo(requesterScope) }
            .filter { subjectFilter == null || it.subject == subjectFilter }
            .map { fact -> RankedMemory(fact, score(fact, asOf, topicTokens)) }
            .sortedWith(compareByDescending<RankedMemory> { it.score }.thenBy { it.fact.id })
            .toList()

    /**
     * 점수 = validity(현재 열린 구간 가중) · confidence · recency(validFrom 신선도) · topic relevance(토큰 겹침).
     * 각 항은 [0,1] 근방으로 정규화해 곱/가중합한다 — 어느 한 축도 단독으로 expired/conflicted 를 살리지 못한다
     * (그건 위 필터가 이미 제거).
     */
    fun score(
        fact: TemporalFact,
        now: Instant,
        topicTokens: Set<String> = emptySet(),
    ): Double {
        val validity = if (fact.isCurrent) 1.0 else 0.7 // 닫힌 과거 구간(과거 회상)도 점수는 가능하되 현재 사실 우대.
        val confidence = fact.confidence.value
        val recency = recencyScore(fact.validFrom, now)
        val relevance = topicRelevance(fact, topicTokens)
        // 가중합(합=1): confidence·recency 가 주, validity·relevance 가 보조. [0,1] 유지.
        return (
            (W_CONFIDENCE * confidence) +
                (W_RECENCY * recency) +
                (W_VALIDITY * validity) +
                (W_RELEVANCE * relevance)
        )
    }

    /** validFrom 이후 경과가 짧을수록 1 에 가깝게(반감기 [RECENCY_HALF_LIFE]). 미래 validFrom 은 1. */
    private fun recencyScore(
        validFrom: Instant,
        now: Instant,
    ): Double {
        val elapsed = Duration.between(validFrom, now)
        if (elapsed.isNegative || elapsed.isZero) return 1.0
        val ratio = elapsed.toMillis().toDouble() / RECENCY_HALF_LIFE.toMillis().toDouble()
        return Math.pow(0.5, ratio)
    }

    /** predicate/object 토큰과 [topicTokens] 겹침 비율([0,1]). 토큰 없으면 중립 0.5(주제 무관 쿼리). */
    private fun topicRelevance(
        fact: TemporalFact,
        topicTokens: Set<String>,
    ): Double {
        if (topicTokens.isEmpty()) return 0.5
        val factTokens = (tokenize(fact.predicate) + tokenize(fact.obj)).toSet()
        if (factTokens.isEmpty()) return 0.0
        val overlap = factTokens.count { it in topicTokens }
        return overlap.toDouble() / topicTokens.size.toDouble()
    }

    private fun tokenize(text: String): List<String> = text.lowercase().split(NON_WORD).filter { it.isNotBlank() }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val RECENCY_HALF_LIFE: Duration = Duration.ofDays(30)
    private const val W_CONFIDENCE = 0.4
    private const val W_RECENCY = 0.3
    private const val W_VALIDITY = 0.2
    private const val W_RELEVANCE = 0.1
}

/** 랭킹된 기억(NEXA-P07-T019). [score] 는 [0,1] 근방 결합 점수, [fact] 는 통과한 유효 사실. */
data class RankedMemory(
    val fact: TemporalFact,
    val score: Double,
)
