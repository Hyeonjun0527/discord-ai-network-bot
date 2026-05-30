package com.discordassistant.central.relay

/** 토큰 검증 결과: 어떤 프로바이더/길드에 세션을 묶을지. */
data class OwnerBinding(
    val providerId: Long,
    val guildId: Long?,
    val agentVersion: String = "",
)

/**
 * 일회용 토큰 → OwnerBinding(null = 인증 실패). 실제 구현은 `provider.TokenService`(K-차수 4).
 */
interface TokenVerifier {
    fun verify(token: String): OwnerBinding?
}
