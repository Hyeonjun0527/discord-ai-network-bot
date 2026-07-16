package com.discordassistant.central.global.adapter.inbound.web

import com.discordassistant.central.global.error.ApiErrorCodes
import com.discordassistant.central.global.error.DomainException
import com.discordassistant.central.global.observability.BugsinkScope
import com.discordassistant.central.global.security.RequestIdFilter
import io.sentry.Sentry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * REST 경계의 공통 예외 처리(예외 원칙 9: 공통 예외 처리는 전역에서). 컨트롤러마다 try-catch 를
 * 도배하지 않고, 도메인/검증 예외를 한 곳에서 구조화된 HTTP 응답으로 변환한다.
 *
 * **모든 클라이언트 대면 에러는 한 가지 바디 모양으로 통일한다**(success/status/error{code,message}/requestId).
 * 잡는 대상:
 * - 우리 예외 [DomainException]·`require()` 의 IllegalArgumentException(도메인 의미·상태코드 보존).
 * - 상태를 스스로 가진 Spring 프레임워크 예외 [ResponseStatusException](405/415/RSE 기반 등) — **상태코드는 그대로**
 *   두고 바디만 우리 모양으로 재포장한다(500 으로 바꾸지 않으므로 과거의 "프레임워크 예외 가로채면 회귀" 우려가 없다).
 * - 깨진 JSON 본문 [HttpMessageNotReadableException] → 400.
 * - 정적/리소스 경계의 [NoResourceFoundException] → 404. broad 500 처리 때문에 없는 파일이 서버 장애로
 *   오해되지 않게 한다.
 * - 경계까지 흘러온 [IllegalStateException] → 500 `INVALID_SERVER_STATE`. 내부 상태 문자열은 노출하지 않는다.
 * - 그 밖의 예상 못 한 [Exception] → 500. 내부 클래스명·스택·SQL 등은 응답에 넣지 않고 로그/Sentry 에만 남긴다.
 *   필터 단계(인증)는 MVC 밖이라 [com.discordassistant.central.global.security.AiNetworkApiSecurityFilter] 가 같은
 *   모양의 JSON 을 직접 쓴다.
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
        if (ex.httpStatus >= 500 && ex.captureAsServerError) {
            log.error("도메인 예외(5xx) code={} requestId={}: {}", ex.errorCode, requestId, ex.message, ex)
            captureServerException(ex, request, ex.httpStatus, requestId)
        } else if (ex.cause != null) {
            // 4xx 라도 원인(cause)이 있으면 디버깅을 위해 스택까지 남긴다 — 숨기지 않는다(예외 원칙 3).
            log.warn("도메인 예외({}) code={} requestId={}: {}", ex.httpStatus, ex.errorCode, requestId, ex.message, ex)
        } else {
            // 서명 위조·미인증 같은 예상된 4xx 는 원인 없는 정상 거부다. 공개 엔드포인트(Paddle webhook 등)에
            // 스프레이가 오면 매 요청 스택트레이스가 로그를 뒤덮으므로(진짜 에러 매몰) 스택 없이 한 줄만 남긴다.
            log.warn("도메인 예외({}) code={} requestId={}: {}", ex.httpStatus, ex.errorCode, requestId, ex.message)
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
                error = ApiError(code = ApiErrorCodes.INVALID_REQUEST, message = ex.message ?: "잘못된 요청입니다"),
                status = 400,
                requestId = requestId,
            ),
        )
    }

    /**
     * 상태를 스스로 가진 Spring 예외(`ResponseStatusException` 및 그 하위 — 405/415 등) →
     * **상태코드는 보존**하고 바디만 통일 모양으로. code 는 상태명(401→UNAUTHORIZED, 503→SERVICE_UNAVAILABLE …).
     * 컨트롤러가 도메인 예외 대신 RSE 를 던져도 클라이언트는 같은 모양을 받는다.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ApiErrorResponse> {
        val requestId = MDC.get(RequestIdFilter.MDC_KEY)
        val status = ex.statusCode.value()
        val code = HttpStatus.resolve(status)?.name ?: ApiErrorCodes.ERROR
        if (status >= 500) {
            log.error("프레임워크 예외({}) code={} requestId={}: {}", status, code, requestId, ex.reason, ex)
        } else {
            log.warn("프레임워크 예외({}) code={} requestId={}: {}", status, code, requestId, ex.reason)
        }
        return ResponseEntity.status(status).body(
            ApiErrorResponse(
                error =
                    ApiError(
                        code = code,
                        message = ex.reason ?: code.replace('_', ' '),
                        actionGuide = responseStatusActionGuide(status),
                    ),
                status = status,
                requestId = requestId,
            ),
        )
    }

    /** 깨진/비어 있는 JSON 본문 → 400(우리 모양). 내부 파서 메시지는 노출하지 않는다(예외 원칙 6). */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<ApiErrorResponse> {
        val requestId = MDC.get(RequestIdFilter.MDC_KEY)
        log.warn("요청 본문 해석 불가(400) requestId={}: {}", requestId, ex.message)
        return ResponseEntity.status(400).body(
            ApiErrorResponse(
                error =
                    ApiError(
                        code = ApiErrorCodes.INVALID_REQUEST,
                        message = "요청 본문을 해석할 수 없습니다",
                        failedCondition = "request_body_json_parseable",
                        blockedAction = "PARSE_REQUEST_BODY",
                        actionGuide = "요청 본문이 올바른 JSON인지 확인해 주세요.",
                    ),
                status = 400,
                requestId = requestId,
            ),
        )
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(
        ex: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val requestId = MDC.get(RequestIdFilter.MDC_KEY)
        log.warn(
            "리소스 없음(404) requestId={}: method={} path={}",
            requestId,
            ex.httpMethod,
            ex.resourcePath,
        )
        return ResponseEntity.status(404).body(
            ApiErrorResponse(
                error =
                    ApiError(
                        code = ApiErrorCodes.NOT_FOUND,
                        message = "요청한 리소스를 찾을 수 없습니다.",
                        failedCondition = "resource_exists",
                        blockedAction = "${request.method} ${request.requestURI}",
                        actionGuide = "요청 경로와 파일 이름을 확인해 주세요.",
                    ),
                status = 404,
                requestId = requestId,
            ),
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(
        ex: IllegalStateException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val requestId = MDC.get(RequestIdFilter.MDC_KEY)
        log.error("서버 상태 예외(500) requestId={}: {}", requestId, ex.message, ex)
        captureServerException(ex, request, 500, requestId)
        return ResponseEntity.status(500).body(
            ApiErrorResponse(
                error =
                    ApiError(
                        code = ApiErrorCodes.INVALID_SERVER_STATE,
                        message = "서버 상태가 요청을 처리할 준비가 아닙니다.",
                        failedCondition = "server_state_allows_request",
                        blockedAction = "${request.method} ${request.requestURI}",
                        actionGuide = "같은 문제가 반복되면 requestId와 함께 운영자에게 전달해 주세요.",
                    ),
                status = 500,
                requestId = requestId,
            ),
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val requestId = MDC.get(RequestIdFilter.MDC_KEY)
        log.error("예상 못 한 REST 예외(500) requestId={}: {}", requestId, ex.message, ex)
        captureServerException(ex, request, 500, requestId)
        return ResponseEntity.status(500).body(
            ApiErrorResponse(
                error =
                    ApiError(
                        code = ApiErrorCodes.INTERNAL_SERVER_ERROR,
                        message = "서버가 요청을 처리하지 못했습니다.",
                        failedCondition = "server_request_processing",
                        blockedAction = "${request.method} ${request.requestURI}",
                        actionGuide = "같은 문제가 반복되면 requestId와 함께 운영자에게 전달해 주세요.",
                    ),
                status = 500,
                requestId = requestId,
            ),
        )
    }

    private fun responseStatusActionGuide(status: Int): String? =
        when (status) {
            401 -> "로그인 상태를 확인한 뒤 다시 요청해 주세요."
            403 -> "요청 권한이나 관리자 토큰을 확인해 주세요."
            404 -> "요청한 리소스 ID와 경로가 맞는지 확인해 주세요."
            409 -> "현재 상태를 새로고침한 뒤 다시 시도해 주세요."
            422 -> "요청 값이 서버 조건을 만족하는지 확인해 주세요."
            503 -> "서비스 설정 또는 운영 상태를 확인한 뒤 다시 시도해 주세요."
            else -> null
        }
}
