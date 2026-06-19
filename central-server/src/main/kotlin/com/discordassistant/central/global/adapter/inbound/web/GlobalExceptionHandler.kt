package com.discordassistant.central.global.adapter.inbound.web

import com.discordassistant.central.global.error.DomainException
import com.discordassistant.central.global.observability.BugsinkScope
import com.discordassistant.central.global.security.RequestIdFilter
import io.sentry.Sentry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * REST 경계의 공통 예외 처리(예외 원칙 9: 공통 예외 처리는 전역에서). 컨트롤러마다 try-catch 를
 * 도배하지 않고, 도메인/검증 예외를 한 곳에서 구조화된 HTTP 응답으로 변환한다.
 *
 * 의도적으로 **우리 예외(DomainException·require() 의 IllegalArgumentException)만** 처리한다.
 * 만능 `catch(Exception)` 을 두지 않는 이유(예외 원칙 1·2: 처리 못 할 예외는 잡지 말 것):
 * - Spring 프레임워크 예외(정적 리소스 404 `NoResourceFoundException`, 405 method-not-allowed,
 *   Security 의 인증/인가 예외 등)는 프레임워크가 이미 올바른 상태코드로 처리한다. 여기서 가로채면
 *   오히려 500 으로 잘못 바꿔 회귀를 만든다.
 * - 진짜 예상 못 한 예외는 Spring 기본 처리(500 + 서버 로그)로 흘려보내 표면화한다(숨기지 않음).
 *
 * 더 세밀한 도메인 의미는 호출부에서 [DomainException] 하위타입(NotFound/InvalidRequest/Conflict 등)을
 * 던지면 자동으로 알맞은 상태코드가 된다 — 새 예외 타입을 추가해도 이 클래스는 수정할 필요가 없다(OCP).
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /** 도메인 예외 → 예외가 지정한 상태코드. 4xx 는 클라이언트 잘못이므로 WARN, 5xx 는 서버 결함이므로 ERROR. */
    @ExceptionHandler(DomainException::class)
    fun handleDomain(
        ex: DomainException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val requestId = MDC.get(RequestIdFilter.MDC_KEY)
        if (ex.httpStatus >= 500) {
            log.error("도메인 예외(5xx) code={} requestId={}: {}", ex.errorCode, requestId, ex.message, ex)
            captureServerException(ex, request, ex.httpStatus, requestId)
        } else {
            // 4xx 라도 원인(cause)이 있으면 디버깅을 위해 남긴다 — 숨기지 않는다(예외 원칙 3).
            log.warn("도메인 예외({}) code={} requestId={}: {}", ex.httpStatus, ex.errorCode, requestId, ex.message, ex)
        }
        return ResponseEntity.status(ex.httpStatus).body(
            ApiErrorResponse(
                error =
                    ApiError(
                        code = ex.errorCode,
                        message = ex.message ?: ex.errorCode,
                        // 선택 컨텍스트는 예외가 채운 것만 흘려보낸다 — null 은 NON_NULL 로 직렬화에서 빠진다.
                        details = ex.details,
                        currentState = ex.currentState,
                        requiredState = ex.requiredState,
                        failedCondition = ex.failedCondition,
                        blockedAction = ex.blockedAction,
                        actionGuide = ex.actionGuide,
                    ),
                status = ex.httpStatus,
                requestId = requestId,
            ),
        )
    }

    private fun captureServerException(
        ex: Throwable,
        request: HttpServletRequest,
        status: Int,
        requestId: String?,
    ) {
        Sentry.captureException(ex) { scope ->
            BugsinkScope.tagApiError(scope, request.requestURI, request.method, status, requestId)
        }
    }

    /**
     * `require(...)`/명시적 `IllegalArgumentException` = 잘못된 입력 → 400(예외 원칙 10: 잘못된 값은 빨리 터뜨림).
     * Kotlin 의 관용적 입력 검증 실패를 생짜 500 대신 구조화된 400 으로 돌려준다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiErrorResponse> {
        val requestId = MDC.get(RequestIdFilter.MDC_KEY)
        log.warn("잘못된 요청(400) requestId={}: {}", requestId, ex.message, ex)
        return ResponseEntity.status(400).body(
            ApiErrorResponse(
                // 단순 입력 오류 — code+message 만으로 충분(상태·조건 필드 억지로 넣지 않음).
                error = ApiError(code = "INVALID_REQUEST", message = ex.message ?: "잘못된 요청입니다"),
                status = 400,
                requestId = requestId,
            ),
        )
    }
}
