package com.discordassistant.central.socialmemory.domain.service.aging

import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.discordassistant.central.socialmemory.domain.service.retention.MemoryRetentionPolicy
import java.time.Duration
import java.time.Instant

/**
 * 기억 노화·압축 장기 실험 코어(NEXA-P19-T018, 순수 도메인 서비스·측정 로직).
 *
 * 90일치 합성 event volume 에서 기억의 **TTL 만료·consolidation(압축)·retrieval 품질**을 측정한다(deliverable
 * T018). 압축은 오래된 일화를 요약(merge)해 항목 수를 줄이지만 — **provenance(출처)·삭제 가능성·현재성(recency)이
 * 사라지지 않아야 한다**(acceptance T018). 이 코어는 그 불변식을 강제하면서 측정 지표를 낸다.
 *
 * **acceptance(T018) — 압축으로 provenance·삭제 가능성·현재성이 사라지지 않는다**:
 *  - [consolidate] 는 묶인 항목들의 [MemorySource.sourceEventIds] 를 **합집합으로 보존**한다(출처 손실 0) →
 *    삭제 전파([MemorySource.withoutEvents]) 가 여전히 동작한다(삭제 가능성 유지).
 *  - 압축 결과는 가장 최근 관찰 시각([latestObservedAt])을 유지한다(현재성 — 오래됨으로 오인 금지).
 *  - [AgingReport] 가 만료 수·압축 전후 항목 수·압축비·retrieval 품질(보존된 최근 항목 비율)을 보고한다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다(Clock 주입은 호출자 — now 를 인자로 받는다).
 */
object MemoryAgingSimulation {
    /**
     * [items] 중 TTL 이 지난 것을 만료로 분류한다(물리 삭제 아님 — 분류만). 만료 기준은 occurredAt + 유형 TTL
     * ([MemoryRetentionPolicy]) 이 [now] 이전인지다.
     */
    fun classifyExpired(
        items: List<AgingMemoryItem>,
        now: Instant,
    ): ExpiryPartition {
        val (expired, alive) =
            items.partition { item ->
                val expiry = item.kind.expiryFrom(item.occurredAt)
                !now.isBefore(expiry)
            }
        return ExpiryPartition(alive = alive, expired = expired)
    }

    /**
     * 같은 [AgingMemoryItem.consolidationKey] 의 항목들을 하나로 **압축(요약)** 한다 — 출처는 합집합으로 보존,
     * 최근 관찰 시각은 유지(현재성), confidence 는 최대값으로 승계. 단일 항목 그룹은 그대로 둔다(불필요 손실 방지).
     *
     * 출처가 사라지면 삭제 전파가 깨지므로 sourceEventIds 합집합 보존이 핵심이다(acceptance T018).
     */
    fun consolidate(items: List<AgingMemoryItem>): List<AgingMemoryItem> =
        items
            .groupBy { it.consolidationKey }
            .map { (key, group) ->
                if (group.size == 1) {
                    group.first()
                } else {
                    val mergedEventIds = group.flatMap { it.source.sourceEventIds }.toSet()
                    val anchor = group.maxBy { it.observedAt }
                    anchor.copy(
                        consolidationKey = key,
                        // 출처 합집합 보존(삭제 가능성 유지) — 가장 최근 항목의 source 를 합집합으로 확장.
                        source = anchor.source.copy(sourceEventIds = mergedEventIds),
                        // 현재성 보존: 그룹에서 가장 최근 관찰 시각.
                        observedAt = group.maxOf { it.observedAt },
                        // 압축으로 신뢰가 떨어지지 않게 최대 confidence 승계.
                        confidence = group.maxOf { it.confidence },
                        mergedCount = group.sumOf { it.mergedCount },
                    )
                }
            }

    /**
     * 90일 노화·압축 한 사이클을 시뮬레이션해 [AgingReport] 를 낸다: 만료 분류 → 생존분 압축 → 지표.
     *
     * retrieval 품질은 "압축 후에도 최근(window 내) 항목이 조회 가능하게 남았는가" 로 본다 — 현재성 보존 증거.
     */
    fun simulate(
        items: List<AgingMemoryItem>,
        now: Instant,
        recencyWindow: Duration = Duration.ofDays(7),
    ): AgingReport {
        val partition = classifyExpired(items, now)
        val consolidated = consolidate(partition.alive)
        val recencyThreshold = now.minus(recencyWindow)
        // 현재성은 "최근 관찰을 가진 consolidation 그룹(주제)" 단위로 본다 — 압축은 같은 그룹의 중복 항목을 줄이되
        // 그 그룹의 최근 관찰은 남긴다. 항목 수가 아니라 **최근 주제가 여전히 조회 가능한가**가 현재성 보존이다.
        val recentBefore =
            partition.alive
                .filter { it.observedAt.isAfter(recencyThreshold) }
                .map { it.consolidationKey }
                .toSet()
                .size
        val recentAfter = consolidated.count { it.observedAt.isAfter(recencyThreshold) }
        // 압축이 출처를 보존했는지(삭제 가능성): 압축 전 생존분의 모든 출처 ID 가 압축 후에도 남아 있어야 한다.
        val sourceBefore = partition.alive.flatMap { it.source.sourceEventIds }.toSet()
        val sourceAfter = consolidated.flatMap { it.source.sourceEventIds }.toSet()
        return AgingReport(
            inputCount = items.size,
            expiredCount = partition.expired.size,
            aliveBeforeConsolidation = partition.alive.size,
            aliveAfterConsolidation = consolidated.size,
            preservedSourceEventIds = sourceAfter.size,
            lostSourceEventIds = (sourceBefore - sourceAfter).size,
            recentRetrievableBefore = recentBefore,
            recentRetrievableAfter = recentAfter,
        )
    }
}

/**
 * 노화 실험용 기억 항목(순수 값 객체·불변). 운영 데이터 아님 — 합성 fixture 에서 파생. 원문 미포함(출처 ID 만).
 */
data class AgingMemoryItem(
    val id: String,
    val kind: MemoryRetentionPolicy,
    /** 사건 발생 시각(TTL 기준). */
    val occurredAt: Instant,
    /** 마지막 관찰/언급 시각(현재성·retrieval 기준). */
    val observedAt: Instant,
    /** 압축 그룹 키(같은 주제·관계의 일화를 묶는다). */
    val consolidationKey: String,
    /** 출처(provenance) — 압축 시 합집합 보존. */
    val source: MemorySource,
    /** [0,1] confidence. */
    val confidence: Double,
    /** 이 항목이 몇 개의 원본 일화를 합친 것인지(압축 추적). */
    val mergedCount: Int = 1,
) {
    init {
        require(id.isNotBlank()) { "id 는 비어 있을 수 없다" }
        require(confidence in 0.0..1.0) { "confidence 는 [0,1] 범위여야 한다: $confidence" }
        require(mergedCount >= 1) { "mergedCount 는 1 이상이어야 한다: $mergedCount" }
    }

    /** 이 항목의 가장 최근 관찰 시각(압축 anchor 선택용). */
    val latestObservedAt: Instant
        get() = observedAt
}

/** TTL 만료 분류 결과(물리 삭제 아님 — 분류만). */
data class ExpiryPartition(
    val alive: List<AgingMemoryItem>,
    val expired: List<AgingMemoryItem>,
)

/**
 * 노화·압축 사이클 측정 리포트(집계 — 원문 미포함). 압축비·출처 보존·현재성 보존을 수치로 본다.
 */
data class AgingReport(
    val inputCount: Int,
    val expiredCount: Int,
    val aliveBeforeConsolidation: Int,
    val aliveAfterConsolidation: Int,
    /** 압축 후 보존된 서로 다른 출처 이벤트 ID 수. */
    val preservedSourceEventIds: Int,
    /** 압축으로 사라진 출처 이벤트 ID 수 — **0 이어야 한다**(acceptance T018, 삭제 가능성 유지). */
    val lostSourceEventIds: Int,
    /** 압축 전 recency window 내 최근 관찰을 가진 서로 다른 주제(consolidation key) 수. */
    val recentRetrievableBefore: Int,
    /** 압축 후 recency window 내 조회 가능한 최근 주제 수 — 현재성 보존(before 와 같아야 함). */
    val recentRetrievableAfter: Int,
) {
    /** 압축비 = 압축 후 / 압축 전 항목 수([0,1], 작을수록 많이 압축). 입력 0 이면 1.0. */
    val compressionRatio: Double
        get() = if (aliveBeforeConsolidation == 0) 1.0 else aliveAfterConsolidation.toDouble() / aliveBeforeConsolidation

    /** 출처가 하나도 사라지지 않았는가(삭제 가능성 유지). */
    val provenancePreserved: Boolean
        get() = lostSourceEventIds == 0

    /** 압축 후에도 최근 항목이 동일하게 조회 가능한가(현재성 보존). */
    val recencyPreserved: Boolean
        get() = recentRetrievableAfter == recentRetrievableBefore
}
