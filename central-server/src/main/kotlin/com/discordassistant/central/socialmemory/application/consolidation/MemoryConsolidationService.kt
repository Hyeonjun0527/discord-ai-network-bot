package com.discordassistant.central.socialmemory.application.consolidation

import com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate
import com.discordassistant.central.socialmemory.domain.service.consolidation.CandidatePromotionRule
import com.discordassistant.central.socialmemory.domain.service.consolidation.PromotionOutcome
import com.discordassistant.central.socialmemory.domain.service.consolidation.PromotionReason

/**
 * 기억 후보 **검증·승격** 유스케이스(NEXA-P07-T016, application). 후보를 [CandidatePromotionRule] 로 판정하고,
 * STORE 판정만 [PromotedMemoryStorePort] 로 멱등 저장한다.
 *
 * **acceptance(T016) — 모든 승격 결과에 reason code 가 남는다**: [consolidate] 는 후보별 [CandidateConsolidationResult]
 * 를 돌려주며 항상 reason code 를 포함한다(STORE/HOLD/DISCARD 모두). 저장은 멱등(중복 생성 없음, T017 연계) —
 * 이미 같은 사실이 있으면 reason 은 그대로 두고 stored=false 로 표시한다.
 *
 * 순수 application: 도메인 규칙·아웃바운드 포트만 본다 — JPA/JDA·glm/Z.AI 타입 미참조. store 아웃바운드 어댑터가
 * 붙으면 Spring 빈으로 승격한다(현재는 포트만 정의, 단위 테스트는 fake store 로 검증).
 */
class MemoryConsolidationService(
    private val store: PromotedMemoryStorePort,
) {
    /** 후보 목록을 검증·승격한다. 각 결과에 reason code 포함. STORE 만 멱등 저장 시도(중복이면 stored=false). */
    fun consolidate(candidates: List<MemoryCandidate>): List<CandidateConsolidationResult> =
        candidates.map { candidate ->
            val existing = store.findActiveFacts(candidate)
            val decision = CandidatePromotionRule.decide(candidate, existing)
            val stored =
                if (decision.outcome == PromotionOutcome.STORE) {
                    store.storeIfAbsent(candidate)
                } else {
                    false
                }
            CandidateConsolidationResult(
                outcome = decision.outcome,
                reason = decision.reason,
                stored = stored,
            )
        }
}

/**
 * 한 후보의 consolidation 결과(NEXA-P07-T016). 항상 [reason] 코드 포함(acceptance). [stored] 는 실제로 새 사실이
 * 저장됐는지(멱등이라 STORE 라도 이미 있으면 false).
 */
data class CandidateConsolidationResult(
    val outcome: PromotionOutcome,
    val reason: PromotionReason,
    val stored: Boolean,
)
