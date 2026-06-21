package com.discordassistant.central.socialmemory.application.extraction

import com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate

/**
 * 구조화 기억 후보 **추출** 아웃바운드 포트(NEXA-P07-T015, 헥사고날).
 *
 * application 은 이 포트로만 추출을 요청한다 — 실제 GLM/Z.AI 호출은 어댑터(adapter.outbound.extraction)가
 * routing CloudLlm 포트를 경유해 수행하고, 이 레이어에는 glm/Z.AI 타입을 노출하지 않는다(speech 와 동일
 * anti-corruption, module-dag 금지 의존 #3, ADR 0006).
 *
 * 추출 결과는 낮은 confidence **후보**([MemoryCandidate]) 일 뿐 확정 사실이 아니다 — 검증·승격(T016)을 거쳐야
 * 저장된다. 추출 실패(호출/스키마 오류)는 빈 리스트로 흡수해 consolidation 이 다음 batch 로 진행하게 한다
 * (Discord 응답을 막지 않는다, acceptance T014).
 *
 * 순수 application: 도메인 후보 타입만 본다.
 */
fun interface MemoryCandidateExtractorPort {
    /**
     * [request] scene 에서 구조화 후보를 추출한다. 호출/파싱 실패·비활성이면 **빈 리스트**(예외를 위로 던지지
     * 않는다 — fire-and-forget). 결과 후보는 모두 GLM 약한 근거이므로 확정이 아니다(T010/T016).
     */
    fun extract(request: MemoryExtractionRequest): List<MemoryCandidate>
}
