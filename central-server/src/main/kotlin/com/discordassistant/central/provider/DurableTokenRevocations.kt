package com.discordassistant.central.provider

import com.discordassistant.central.persistence.ProviderDurableRevocationEntity
import com.discordassistant.central.persistence.ProviderDurableRevocationRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * durable 프로바이더 토큰의 per-(provider,guild) 즉시 폐기 저장소.
 *
 * durable 토큰은 상태 비저장 HMAC 이라 만료 전까지 유효하다. 프로바이더 제거/거절 시 폐기 시각을
 * 기록해, 그 시각 이하에 발급된 토큰을 즉시 무효화한다(재페어링하면 더 늦은 발급시각으로 회복).
 */
interface DurableTokenRevocations {
    /** (provider,guild) 의 마지막 폐기 시각(epoch sec). 없으면 null. */
    fun revokedAtEpoch(
        providerId: Long,
        guildId: Long,
    ): Long?

    /** (provider,guild) 토큰을 [atEpochSec] 이하 발급분까지 즉시 폐기한다(이미 더 늦으면 갱신). */
    fun revoke(
        providerId: Long,
        guildId: Long,
        atEpochSec: Long,
    )
}

/** JPA 영속 구현(서버 재시작에도 폐기 유지). */
@Component
class JpaDurableTokenRevocations(
    private val repo: ProviderDurableRevocationRepository,
) : DurableTokenRevocations {
    @Transactional(readOnly = true)
    override fun revokedAtEpoch(
        providerId: Long,
        guildId: Long,
    ): Long? = repo.findByProviderIdAndGuildId(providerId, guildId)?.revokedAtEpoch

    @Transactional
    override fun revoke(
        providerId: Long,
        guildId: Long,
        atEpochSec: Long,
    ) {
        val existing = repo.findByProviderIdAndGuildId(providerId, guildId)
        if (existing != null) {
            // 폐기 시각은 단조 증가(이전 폐기를 되돌리지 않음).
            if (atEpochSec > existing.revokedAtEpoch) {
                existing.revokedAtEpoch = atEpochSec
                repo.save(existing)
            }
        } else {
            repo.save(
                ProviderDurableRevocationEntity(
                    providerId = providerId,
                    guildId = guildId,
                    revokedAtEpoch = atEpochSec,
                ),
            )
        }
    }
}
