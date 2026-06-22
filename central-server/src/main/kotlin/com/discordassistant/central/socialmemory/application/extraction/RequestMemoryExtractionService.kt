package com.discordassistant.central.socialmemory.application.extraction

import org.slf4j.LoggerFactory

/**
 * finalized scene 에서 비동기 기억 추출을 **요청**하는 유스케이스(NEXA-P07-T014, application).
 *
 * **acceptance(T014) — 추출 실패가 Discord 응답 시간을 막지 않는다**: [request] 는 옵트인된 요청을 큐 포트에
 * 적재만 하고 즉시 반환한다(외부 GLM 호출 없음 — 그건 consolidation job 이 나중에 한다). 적재 자체가 실패해도
 * 예외를 삼키고 false 를 돌려준다 — 호출자(응답 경로)는 어떤 경우에도 추출 때문에 블록되거나 실패하지 않는다.
 * speech 경로와 분리돼 있어 추출이 응답 생성에 끼어들지 않는다.
 *
 * 순수 application: 큐 포트와 도메인 타입만 본다 — JPA/JDA·glm/Z.AI 타입 미참조. 아웃바운드 어댑터(큐 구현)가
 * 붙으면 Spring 빈으로 승격한다(현재는 포트만 정의, 단위 테스트는 fake 큐로 검증).
 */
class RequestMemoryExtractionService(
    private val queue: MemoryExtractionQueuePort,
) {
    private val log = LoggerFactory.getLogger(RequestMemoryExtractionService::class.java)

    /**
     * [request] 를 비동기 추출 큐에 적재한다. 동의가 없으면(옵트아웃) 적재하지 않고 false. 적재 성공이면 true.
     * 큐 적재 중 어떤 예외가 나도 삼키고 false — 응답 경로는 절대 블록·실패하지 않는다(fire-and-forget).
     */
    fun request(request: MemoryExtractionRequest): Boolean {
        if (!request.isExtractable) return false
        return try {
            queue.enqueue(request)
            true
        } catch (e: Exception) {
            // 추출 적재 실패는 응답을 막지 않는다 — 상세는 로그로만, 응답 경로는 정상 진행.
            log.warn("기억 추출 요청 적재 실패(무시): scene={} {}", request.sceneId, e.javaClass.simpleName)
            false
        }
    }
}
