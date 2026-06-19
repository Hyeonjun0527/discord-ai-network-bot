package com.discordassistant.central.global.error

/**
 * 도메인/비즈니스 예외의 공통 베이스(예외 원칙 6: 저수준 기술 예외를 상위 비즈니스 예외로 변환).
 *
 * 의도적으로 **순수 Kotlin**이다 — Spring/JPA/JDA 어디에도 의존하지 않으므로(HttpStatus 대신 Int)
 * 도메인·application·adapter 어느 레이어에서나 안전하게 던질 수 있고 ArchUnit 순수성 규칙을 깨지 않는다.
 * HTTP 경계 변환은 [com.discordassistant.central.global.adapter.inbound.web.GlobalExceptionHandler]가
 * 전담한다(예외 원칙 9: 공통 예외 처리는 전역에서).
 *
 * `cause`를 받아 원래 예외 정보를 보존한다(예외 원칙 7). 메시지는 원인을 알 수 있게 구체적으로 쓴다(예외 원칙 4).
 *
 * **계층형 컨텍스트**: [details]·[currentState]/[requiredState]·[failedCondition]·[blockedAction]·[actionGuide] 는
 * 전부 선택(기본 null)이다. 대부분의 예외는 `httpStatus`+`errorCode`+`message` 만으로 충분하고, 추가 필드는
 * **그 의미가 있는 예외에서만** 채운다(상태 전이 → [InvalidStateTransitionException], 선행 조건 → [PreconditionFailedException]).
 * 단순 NotFound/Forbidden 에 상태·조건 필드를 억지로 넣지 않는다(과설계 방지).
 */
abstract class DomainException(
    /** 이 예외에 대응하는 HTTP 상태코드(예: 400/404/409). 프레임워크 비의존 정수. */
    val httpStatus: Int,
    /** 기계가 분기할 수 있는 안정적 에러코드(예: `NOT_FOUND`). 사용자 메시지와 별개. */
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
    /** 입력값·제한값·실제값 등 추가 정보가 있을 때만. */
    val details: Map<String, Any?>? = null,
    /** 상태 전이 실패일 때만 — 현재 상태. */
    val currentState: String? = null,
    /** 상태 전이 실패일 때만 — 필요한 상태. */
    val requiredState: String? = null,
    /** "무슨 조건을 못 지켰는가" 가 핵심인 실패일 때만. */
    val failedCondition: String? = null,
    /** 조건 때문에 막힌 원래 행동이 의미 있을 때만. */
    val blockedAction: String? = null,
    /** 사용자가 할 수 있는 다음 행동이 명확할 때만. */
    val actionGuide: String? = null,
) : RuntimeException(message, cause)

/** 요청 대상 리소스를 찾을 수 없음 → 404. 메시지엔 무엇을(식별자 포함) 못 찾았는지 담는다. */
class NotFoundException(
    message: String,
    cause: Throwable? = null,
) : DomainException(httpStatus = 404, errorCode = "NOT_FOUND", message = message, cause = cause)

/** 클라이언트 입력이 잘못됨(검증 실패) → 400. `require()` 대용으로 도메인 의미를 담아 던진다. */
class InvalidRequestException(
    message: String,
    cause: Throwable? = null,
) : DomainException(httpStatus = 400, errorCode = "INVALID_REQUEST", message = message, cause = cause)

/** 현재 상태와 충돌(중복/이미 처리됨/버전 충돌 등) → 409. */
class ConflictException(
    message: String,
    cause: Throwable? = null,
) : DomainException(httpStatus = 409, errorCode = "CONFLICT", message = message, cause = cause)

/** 권한 없음(인증은 됐으나 해당 작업 불가) → 403. */
class ForbiddenException(
    message: String,
    cause: Throwable? = null,
) : DomainException(httpStatus = 403, errorCode = "FORBIDDEN", message = message, cause = cause)

/**
 * 상태 머신이 있는 도메인에서 **현재 상태에서는 할 수 없는 전이**를 시도했을 때 → 409.
 * (예: 라이선스 `EXPIRED` 에서 프리미엄 동작, 프로바이더 `offline` 을 pause, 이미 처리된 요청 재처리.)
 *
 * 이메일 형식 오류·없는 리소스·서버 내부 에러처럼 상태 전이가 아닌 실패에는 쓰지 않는다 —
 * 그런 경우엔 [InvalidRequestException]/[NotFoundException] 등을 쓴다.
 */
class InvalidStateTransitionException(
    message: String,
    currentState: String,
    requiredState: String,
    blockedAction: String? = null,
    actionGuide: String? = null,
    errorCode: String = "INVALID_STATE_TRANSITION",
    cause: Throwable? = null,
) : DomainException(
        httpStatus = 409,
        errorCode = errorCode,
        message = message,
        cause = cause,
        currentState = currentState,
        requiredState = requiredState,
        blockedAction = blockedAction,
        actionGuide = actionGuide,
    )

/**
 * **선행 조건 미충족** — "무슨 조건을 못 지켰는가" 가 핵심인 실패(입력값 검증·권한 조건·형식 불일치·
 * 인증 미완료 등) → 기본 422. [failedCondition] 으로 깨진 조건을 기계가 분기하게 하고, 필요하면
 * [details](제한값/실제값)·[blockedAction](막힌 행동)·[actionGuide](다음 행동)를 덧붙인다.
 *
 * 깨진 조건이 핵심이 아닌 단순 장애에는 쓰지 않는다(그땐 `code`+`message` 면 충분).
 */
class PreconditionFailedException(
    message: String,
    failedCondition: String,
    details: Map<String, Any?>? = null,
    blockedAction: String? = null,
    actionGuide: String? = null,
    httpStatus: Int = 422,
    errorCode: String = "PRECONDITION_FAILED",
    cause: Throwable? = null,
) : DomainException(
        httpStatus = httpStatus,
        errorCode = errorCode,
        message = message,
        cause = cause,
        details = details,
        failedCondition = failedCondition,
        blockedAction = blockedAction,
        actionGuide = actionGuide,
    )
