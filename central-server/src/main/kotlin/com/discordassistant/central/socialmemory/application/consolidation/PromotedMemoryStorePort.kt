package com.discordassistant.central.socialmemory.application.consolidation

import com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact

/**
 * 승격된 후보를 저장하고 충돌 판정 입력을 읽는 아웃바운드 포트(NEXA-P07-T016/T017, 헥사고날).
 *
 * **중복 생성 방지(acceptance T017)**: [storeIfAbsent] 는 후보의 결정론적 동일성 키(guild·subject·predicate·object)로
 * 이미 같은 현재 사실이 있으면 저장하지 않고 false 를 돌려준다 — job 재시작·중복 실행에도 같은 기억이 두 번 만들어지지
 * 않는다(멱등). 구현 어댑터(JPA)는 adapter.outbound.persistence 에 둔다.
 *
 * 순수 application: 도메인 타입만 본다 — JPA/JDA 타입 미참조.
 */
interface PromotedMemoryStorePort {
    /**
     * 같은 주장의 현재 ACTIVE 사실들을 읽어 충돌 판정(T016)에 쓴다. 없으면 빈 리스트. (guild·subject·predicate) 키.
     */
    fun findActiveFacts(candidate: MemoryCandidate): List<TemporalFact>

    /**
     * [candidate] 를 새 ACTIVE 사실로 저장하되, 같은 동일성 키의 현재 사실이 이미 있으면 저장하지 않고 false
     * (멱등 — 재시작/중복 실행 안전, acceptance T017). 새로 저장했으면 true.
     */
    fun storeIfAbsent(candidate: MemoryCandidate): Boolean
}
