package com.discordassistant.central.web

import jakarta.servlet.http.HttpServletRequest

/**
 * 대시보드 관리자 작업의 **서버측 인증 주체**(#1 크로스길드 IDOR/권한상승 수정).
 *
 * 이 제품은 self-hosted 단일 운영자 모델이다: [AiNetworkApiSecurityFilter] 를 통과했다는 사실
 * 자체가 "신뢰된 대시보드 관리자(전역)" 임을 의미한다. 따라서 권한/신원 플래그는 **요청 body 가
 * 아니라 이 인증 주체에서 유도**한다. 클라이언트가 보낸 `isGuildAdmin`/`roleIds`/`userId` 는
 * 절대 신뢰하지 않는다.
 *
 * - [userId]      OAuth 로그인 사용자의 Discord user id. admin-token 헤더로 통과한 system 호출은 null.
 * - [systemToken] admin-token 헤더로 통과했으면 true(운영자 자동화/CLI). OAuth 통과면 false.
 *
 * 한계(범위 밖): admin-token/허용목록은 **인스턴스 전역** 신뢰다. per-guild 토큰 격리는 하지 않으며,
 * 단일 운영자 모델을 가정한다(공유 토큰 유출 시 모든 길드에 권한이 미친다 — 토큰 보호가 1차 방어선).
 */
data class DashboardActor(
    val userId: Long?,
    val systemToken: Boolean,
) {
    companion object {
        /** [AiNetworkApiSecurityFilter] 가 통과한 인증 주체를 실어 컨트롤러로 넘기는 request attribute 키. */
        const val REQUEST_ATTRIBUTE = "com.discordassistant.central.web.dashboardActor"

        /**
         * 필터가 세운 인증 주체를 요청에서 읽는다. 보안 필터가 보호하는 변경 엔드포인트는 필터를 통과해야만
         * 도달하므로 attribute 가 항상 존재한다. **fail-closed**: attribute 가 없다는 것은 그 경로가
         * [AiNetworkApiSecurityFilter] 의 보호 매칭에서 누락됐다는 뜻이므로, 전역 권한을 부여하는 대신
         * 즉시 거부한다(향후 새 변경 엔드포인트가 필터 매칭에서 빠져도 침묵 권한 우회가 생기지 않게).
         */
        fun from(request: HttpServletRequest): DashboardActor =
            request.getAttribute(REQUEST_ATTRIBUTE) as? DashboardActor
                ?: throw IllegalStateException(
                    "dashboard actor missing: request did not pass AiNetworkApiSecurityFilter (fail-closed)",
                )
    }
}
