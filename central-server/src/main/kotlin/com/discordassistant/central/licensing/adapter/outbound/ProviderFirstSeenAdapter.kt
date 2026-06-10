package com.discordassistant.central.licensing.adapter.outbound

import com.discordassistant.central.licensing.application.port.UserFirstSeenPort
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderRepository
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * [UserFirstSeenPort] 구현 — provider 등록(가입)의 최초 createdAt 을 유저의 가입 시각으로 본다.
 *
 * 유저는 여러 길드에 참여할 수 있으므로 MIN(created_at). 초기 데이터 오염(Instant.EPOCH=1970 등
 * 비정상적으로 과거)은 신뢰할 수 없으므로 null 로 취급해 호출자가 now 로 폴백하게 한다(오만료 방지).
 */
@Component
class ProviderFirstSeenAdapter(
    private val providers: ProviderRepository,
) : UserFirstSeenPort {
    override fun firstSeenAt(userId: Long): Instant? {
        val earliest =
            providers.findByProviderUserId(userId).minOfOrNull { it.createdAt } ?: return null
        // EPOCH 근처 오염값은 신뢰 불가 → null(now 폴백). 정상 가입은 2025 이후.
        return if (earliest.isBefore(SANE_LOWER_BOUND)) null else earliest
    }

    private companion object {
        /** 합리적 하한: 이 서비스 운영 개시 이전 시각은 데이터 오염으로 간주. */
        val SANE_LOWER_BOUND: Instant = Instant.parse("2025-01-01T00:00:00Z")
    }
}
