package com.discordassistant.central.licensing.adapter.outbound

import com.discordassistant.central.licensing.application.port.ContributionPort
import com.discordassistant.central.requestlog.adapter.outbound.persistence.ContributionLogRepository
import org.springframework.stereotype.Component

/** [ContributionPort] 구현 — contribution_log 에 해당 providerId(=Discord userId) 기록이 1건 이상이면 true. */
@Component
class ContributionAdapter(
    private val contributions: ContributionLogRepository,
) : ContributionPort {
    override fun hasContributed(userId: Long): Boolean = contributions.countByProviderId(userId) > 0
}
