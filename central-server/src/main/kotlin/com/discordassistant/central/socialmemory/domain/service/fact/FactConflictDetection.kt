package com.discordassistant.central.socialmemory.domain.service.fact

import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact

/**
 * 동일 주장(subject+predicate)의 **상충 object** 를 감지해 모순 fact 를 [MemoryStatus.CONFLICTED] 로 두는 규칙
 * (NEXA-P07-T009, 순수 도메인 서비스).
 *
 * **acceptance(T009) — 근거가 부족한 경우 임의로 한쪽을 현재 사실로 승격하지 않는다**: confidence 차이가 [DECISIVE_MARGIN]
 * 이상으로 한쪽이 명확히 우세할 때만 그쪽을 ACTIVE 로 두고 나머지를 SUPERSEDED 로 닫는다. 차이가 작으면(근거 부족)
 * **양쪽 모두 CONFLICTED** 로 두어 한쪽을 임의 승격하지 않는다 — 사람이 정리하거나 더 강한 관찰이 올 때까지 보류한다.
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
object FactConflictDetection {
    /** 한쪽을 현재 사실로 승격하려면 필요한 confidence 우세 폭. 이 미만이면 근거 부족으로 보고 양쪽 보류. */
    const val DECISIVE_MARGIN = 0.2

    /**
     * 같은 주장의 후보 fact 들([candidates]) 중 상충하는 object 가 있는지 판정한다. 후보는 같은 subject/predicate/guild
     * 여야 한다(아니면 [IllegalArgumentException]). object 가 1종이면 모순 아님 — 입력 그대로 돌려준다.
     *
     * 2종 이상이면: 가장 신뢰 높은 후보가 차순위보다 [DECISIVE_MARGIN] 이상 우세하면 그 후보만 ACTIVE,
     * 나머지는 SUPERSEDED. 그렇지 않으면(근거 부족) **모든 후보를 CONFLICTED** 로 둔다(임의 승격 금지).
     */
    fun resolve(candidates: List<TemporalFact>): ConflictResolution {
        require(candidates.isNotEmpty()) { "candidates 는 비어 있을 수 없다" }
        val head = candidates.first()
        require(candidates.all { it.sameClaimAs(head) }) { "후보는 같은 주장(subject/predicate/guild)이어야 한다" }

        val distinctObjects = candidates.map { it.obj }.toSet()
        if (distinctObjects.size <= 1) {
            // 모순 아님 — 같은 object 의 재진술. 상태 변경 없이 그대로.
            return ConflictResolution(facts = candidates, conflicted = false)
        }

        val sorted = candidates.sortedByDescending { it.confidence.value }
        val top = sorted[0]
        val runnerUp = sorted[1]
        val decisive = (top.confidence.value - runnerUp.confidence.value) >= DECISIVE_MARGIN

        return if (decisive) {
            // 한쪽이 명확히 우세 — 그쪽만 현재 사실, 나머지는 닫음(물리 삭제 아님).
            val resolved =
                candidates.map {
                    if (it.id == top.id) it.copy(status = MemoryStatus.ACTIVE) else it.copy(status = MemoryStatus.SUPERSEDED)
                }
            ConflictResolution(facts = resolved, conflicted = false)
        } else {
            // 근거 부족 — 임의 승격 금지, 모든 후보를 CONFLICTED 로 보류(acceptance T009).
            val held = candidates.map { it.copy(status = MemoryStatus.CONFLICTED) }
            ConflictResolution(facts = held, conflicted = true)
        }
    }
}

/**
 * 모순 판정 결과(NEXA-P07-T009). [conflicted] 가 true 면 근거 부족으로 모든 [facts] 가 CONFLICTED(보류) 다.
 * false 면 모순이 없거나 명확한 우세로 한쪽이 ACTIVE 로 정리됐다.
 */
data class ConflictResolution(
    val facts: List<TemporalFact>,
    val conflicted: Boolean,
)
