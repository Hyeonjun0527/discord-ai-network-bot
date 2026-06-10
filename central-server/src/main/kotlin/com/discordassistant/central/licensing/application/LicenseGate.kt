package com.discordassistant.central.licensing.application

import org.springframework.stereotype.Component

/**
 * 유료 기능 게이트(ADR 0005, 차수 4). 서버 관리 프리미엄 쓰기 액션의 **단일 판정 지점**.
 *
 * 판정은 central 실시간([LicenseService.hasPaidAccess]) — 체험 중(TRIAL)·라이선스·이벤트면 통과, 만료면 거부.
 * 거부는 예외 대신 사유 메시지를 반환해 기존 컨트롤러의 `ok=false, message` 응답 형식과 일관되게 한다.
 */
@Component
class LicenseGate(
    private val licenses: LicenseService,
) {
    /** 유료 접근 가능하면 null, 아니면 사용자에게 보여줄 거부 사유. */
    fun denyReason(userId: Long): String? =
        if (licenses.hasPaidAccess(userId)) {
            null
        } else {
            "이 기능은 라이선스가 필요해요(무료 체험이 끝났어요). 앱에서 구매하거나 이벤트로 평생 무료를 받을 수 있어요."
        }
}
