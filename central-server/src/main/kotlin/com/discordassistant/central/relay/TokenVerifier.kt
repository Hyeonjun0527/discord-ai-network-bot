package com.discordassistant.central.relay

import org.springframework.stereotype.Component

/** 토큰 검증 결과: 어떤 프로바이더/길드에 세션을 묶을지. */
data class OwnerBinding(
    val providerId: Long,
    val guildId: Long?,
    val agentVersion: String = "",
)

/** 일회용 토큰 → OwnerBinding(null = 인증 실패). 실제 구현은 K-차수 4(tokens). */
interface TokenVerifier {
    fun verify(token: String): OwnerBinding?
}

/**
 * 임시 스텁: 항상 거부. K-차수 4 에서 실제 토큰 발급/검증 구현이 `@Primary` 로 대체한다.
 * 컨텍스트가 TokenVerifier 빈을 요구하므로 부팅을 위해 둔다.
 */
@Component
class StubTokenVerifier : TokenVerifier {
    override fun verify(token: String): OwnerBinding? = null
}
