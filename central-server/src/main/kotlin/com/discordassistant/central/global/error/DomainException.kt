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
 */
abstract class DomainException(
    /** 이 예외에 대응하는 HTTP 상태코드(예: 400/404/409). 프레임워크 비의존 정수. */
    val httpStatus: Int,
    /** 기계가 분기할 수 있는 안정적 에러코드(예: `NOT_FOUND`). 사용자 메시지와 별개. */
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
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
