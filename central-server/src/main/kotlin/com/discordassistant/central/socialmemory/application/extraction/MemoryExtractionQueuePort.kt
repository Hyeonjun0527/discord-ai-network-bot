package com.discordassistant.central.socialmemory.application.extraction

/**
 * 비동기 기억 추출 요청 **대기열** 아웃바운드 포트(NEXA-P07-T014, 헥사고날).
 *
 * speech 경로와 분리: 응답 생성 경로는 [enqueue] 로 요청을 큐에 넣기만 하고 즉시 반환한다 — 실제 추출(GLM 호출)은
 * consolidation job(T017)이 [drain] 으로 작은 batch 씩 꺼내 처리한다. 따라서 추출 지연·실패가 Discord 응답을
 * 막지 않는다(acceptance T014). 구현 어댑터(인메모리/DB)는 adapter.outbound 에 둔다.
 *
 * 순수 application: 도메인/표준 타입만 본다.
 */
interface MemoryExtractionQueuePort {
    /** 요청을 큐에 적재한다(즉시 반환, 비동기). 같은 sceneId 중복 적재는 구현이 멱등하게 흡수할 수 있다. */
    fun enqueue(request: MemoryExtractionRequest)

    /**
     * 처리 대기 중인 요청을 최대 [batchSize] 개 꺼낸다(consolidation job 입력). 꺼낸 요청은 처리 중으로 표시돼
     * 동시/중복 실행에서 재선점되지 않는다(lease, T017). batch 단위로 작게 처리해 부하를 제한한다.
     */
    fun drain(batchSize: Int): List<MemoryExtractionRequest>
}
