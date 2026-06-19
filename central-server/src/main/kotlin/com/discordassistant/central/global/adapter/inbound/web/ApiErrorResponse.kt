package com.discordassistant.central.global.adapter.inbound.web

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * REST 에러 응답의 단일 형태(계층형). 내부 구현(스택트레이스·클래스명·SQL)을 노출하지 않고
 * 사용자/클라이언트가 분기할 수 있는 정보만 담는다(예외 원칙 6: 내부 정보 누출 금지).
 *
 * **모든 에러가 모든 필드를 갖지 않는다.** [ApiError.code]·[ApiError.message] 만 기본이고,
 * 나머지는 그 에러에서 의미가 있을 때만 채운다(`null` 이면 [JsonInclude] 로 직렬화에서 빠진다).
 * 단순 장애(`INTERNAL`)에 `failedCondition`/`currentState` 를 억지로 넣지 않는다 — 과설계 방지.
 *
 * @property success  항상 false(에러 응답 마커). 성공/실패를 한 키로 분기하려는 클라이언트용.
 * @property status   HTTP 상태코드(전송 계층).
 * @property error    안정적 코드 + 메시지 + 선택적 컨텍스트.
 * @property requestId 상관관계 추적 id(`X-Request-Id`/MDC). 로그와 응답을 잇는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiErrorResponse(
    val error: ApiError,
    val status: Int,
    val requestId: String?,
    val success: Boolean = false,
)

/**
 * 에러 본문. `code`/`message` 는 항상, 나머지는 **해당 에러에 필요할 때만** 채운다.
 *
 * @property code            프론트가 분기하는 고정 식별자(`NOT_FOUND`/`INVALID_STATE_TRANSITION` 등). 로그·테스트 기준.
 * @property message         사용자 또는 클라이언트가 읽을 설명. 500 류는 내부 사유 대신 일반 안내만.
 * @property details         입력값·제한값·실제값 등 추가 정보가 있을 때만(예: `{field, allowedValues, actualValue}`).
 * @property currentState    상태 전이 실패일 때만. 현재 상태(예: `DRAFT`).
 * @property requiredState   상태 전이 실패일 때만. 필요한 상태(예: `READY_TO_SUBMIT`).
 * @property failedCondition "무슨 조건을 못 지켰는가" 가 핵심인 검증/권한/형식 실패일 때만.
 * @property blockedAction   조건 때문에 막힌 원래 행동이 의미 있을 때만(예: `ACCOUNT_SIGN_UP`).
 * @property actionGuide     사용자가 할 수 있는 다음 행동이 명확할 때만. "다시 시도" 남발 금지.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, Any?>? = null,
    val currentState: String? = null,
    val requiredState: String? = null,
    val failedCondition: String? = null,
    val blockedAction: String? = null,
    val actionGuide: String? = null,
)
