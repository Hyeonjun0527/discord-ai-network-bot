package com.discordassistant.central.licensing.application.port

/**
 * 결제 checkout 링크 생성 아웃바운드 포트(ADR 0005). 구현은 Paddle API 호출.
 * 결제 비활성(ENV 미설정)이면 null 을 반환해 컨트롤러가 503 으로 안내한다.
 */
fun interface CheckoutPort {
    /** [userId]에 귀속된 $10 라이선스 checkout URL. custom_data.discordUserId 는 문자열(64bit 정밀도). */
    fun createCheckoutUrl(userId: Long): String?
}
