package com.discordassistant.central.global.adapter.inbound.web

/**
 * REST 에러 응답의 단일 형태. 내부 구현(스택트레이스·클래스명·SQL)을 노출하지 않고
 * 사용자/클라이언트가 분기할 수 있는 최소 정보만 담는다(예외 원칙 6: 내부 정보 누출 금지).
 *
 * @property status   HTTP 상태코드
 * @property error    안정적 에러코드(`NOT_FOUND`/`INVALID_REQUEST`/`CONFLICT`/`INTERNAL` 등) — 기계 분기용
 * @property message  사람이 읽을 사유(예외 원칙 4). 500 류는 내부 사유 대신 일반 안내만 담는다.
 * @property requestId 상관관계 추적 id(`X-Request-Id`/MDC). 로그와 응답을 잇는다.
 */
data class ApiErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val requestId: String?,
)
