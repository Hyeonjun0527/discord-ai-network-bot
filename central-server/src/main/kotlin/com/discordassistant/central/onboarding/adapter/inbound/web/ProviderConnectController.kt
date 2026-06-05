package com.discordassistant.central.onboarding.adapter.inbound.web

import com.discordassistant.central.onboarding.application.ProviderConnectOnboardingService
import com.discordassistant.central.onboarding.application.ProviderConnectOnboardingService.CallbackResult
import com.discordassistant.central.onboarding.application.ProviderConnectOnboardingService.ConnectResult
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * 웹 ‘토큰 받기’(`--gui` 의 버튼) — Discord 로그인으로 **내가 고른 서버**의 프로바이더 토큰을 발급.
 *
 * 흐름: 에이전트 UI → `/provider/connect?cb=<로컬콜백>&state=<세션키>` → Discord OAuth(identify+guilds)
 * → `/provider/connect/callback` → 사용자 식별 + 소속 길드 조회 → **봇이 있는 서버 ∩ 내 서버** 후보 산출
 * → 1개면 자동, 여러 개면 선택 화면(/provider/connect/pick) → 그 서버에 가입(자동 승인 정책 따름)·일회용
 * 토큰 발급 → 로컬 `cb?token=…&state=…` 로 리디렉트(에이전트가 저장).
 *
 * lean: OAuth 오케스트레이션·토큰 정책·콜백 시퀀스는 [ProviderConnectOnboardingService] 가, HTML/리디렉트
 * 렌더링은 [ConnectPageRenderer] 가 담당한다. 컨트롤러는 4 엔드포인트(connect/connectStatus/callback/pick)
 * 에서 파싱 → 서비스 단일 호출 → 세일드 결과 → HTTP 매핑만 한다.
 *
 * 보안: client-id/secret 미설정이면 비활성(503). cb 는 localhost+경로 `/connect/callback` 만 허용
 * (토큰 탈취 방지). OAuth state·선택 키는 서버 발급·단발성(CSRF/재생 방지). 위 불변식은 서비스가 강제한다.
 */
@Controller
class ProviderConnectController(
    private val onboarding: ProviderConnectOnboardingService,
    private val renderer: ConnectPageRenderer,
) {
    @GetMapping("/provider/connect")
    fun connect(
        @RequestParam cb: String,
        @RequestParam state: String,
    ): ResponseEntity<String> =
        when (val r = onboarding.connect(cb, state)) {
            ConnectResult.NotEnabled ->
                renderer.page(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "‘토큰 받기’가 아직 설정되지 않았습니다. 디스코드에서 <b>/프로바이더참여</b> 으로 토큰을 받아 붙여넣어 주세요.",
                )
            ConnectResult.InvalidCallback -> renderer.page(HttpStatus.BAD_REQUEST, "잘못된 콜백 주소입니다.")
            ConnectResult.MissingState -> renderer.page(HttpStatus.BAD_REQUEST, "세션 정보가 없습니다.")
            is ConnectResult.Redirect -> renderer.redirectToAuthorize(r.authorizeUrl)
        }

    /** 에이전트가 '디스코드 로그인 추가' 버튼을 켤지 판단하도록 OAuth 활성 여부만 알려준다(설정 노출 없음). */
    @GetMapping("/provider/connect/status", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun connectStatus(): Map<String, Boolean> = mapOf("enabled" to onboarding.enabled)

    @GetMapping("/provider/connect/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
    ): ResponseEntity<String> = render(onboarding.callback(code, state))

    @GetMapping("/provider/connect/pick")
    fun pick(
        @RequestParam sel: String,
        @RequestParam guild: Long,
    ): ResponseEntity<String> = render(onboarding.pick(sel, guild))

    /** callback/pick 세일드 결과 → HTTP/HTML 매핑(안내·에러 메시지·리디렉트 쿼리 그대로). */
    private fun render(result: CallbackResult): ResponseEntity<String> =
        when (result) {
            CallbackResult.InvalidRequest ->
                renderer.page(HttpStatus.BAD_REQUEST, "만료되었거나 잘못된 요청입니다. 처음부터 다시 시도해 주세요.")
            CallbackResult.InvalidSelection -> renderer.page(HttpStatus.BAD_REQUEST, "선택할 수 없는 서버입니다.")
            is CallbackResult.Cancelled -> renderer.redirectToCb(result.cb, result.localState, error = "cancelled")
            is CallbackResult.TokenFailed -> renderer.redirectToCb(result.cb, result.localState, error = "token")
            is CallbackResult.IdentifyFailed -> renderer.redirectToCb(result.cb, result.localState, error = "identify")
            CallbackResult.NoCandidate ->
                renderer.page(
                    HttpStatus.OK,
                    "이 봇이 들어가 있는 서버 중 당신이 속한 곳이 없습니다.<br>봇이 있는 서버에 가입한 뒤 다시 시도해 주세요.",
                )
            is CallbackResult.Issued ->
                // 성공: 토큰과 함께 어느 서버인지(guildId/guildName)도 전달 → 에이전트가 '내 서버 목록'에 표시.
                renderer.redirectToCb(
                    result.cb,
                    result.localState,
                    token = result.token,
                    guildId = result.guildId,
                    guildName = result.guildName,
                )
            is CallbackResult.Pending ->
                // 자동 승인이 아니어서 관리자 승인 대기 상태(PENDING).
                renderer.redirectToCb(result.cb, result.localState, error = "pending")
            is CallbackResult.NeedSelection -> renderer.chooserPage(result.sel, result.candidates)
        }
}
