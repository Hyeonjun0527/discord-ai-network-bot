package com.discordassistant.central.discord

import com.discordassistant.central.domain.ProviderState

/**
 * 프로바이더 휴식 권장(차수 12 #173). 과부하/한도 임박 시 가벼운 안내 문구를 만든다.
 * 강제가 아니라 자발적 기여 모델에 맞는 배려 메시지.
 */
object RestHint {
    fun forStatus(
        state: ProviderState,
        activeRequests: Int,
        remainingDaily: Int,
    ): String? =
        when {
            state == ProviderState.LIMITED ->
                "💤 부하가 높습니다. `/provider-pause` 로 잠시 쉬어가도 좋아요."
            remainingDaily in 1..5 ->
                "💤 오늘 한도가 거의 찼어요. 무리하지 말고 쉬어도 됩니다. 고마워요!"
            else -> null
        }
}
