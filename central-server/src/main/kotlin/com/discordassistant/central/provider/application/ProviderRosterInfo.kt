package com.discordassistant.central.provider.application

import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.requestlog.application.UsageService
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 관리 화면(13) 로스터 보강 + 서버 정책 — 관리 컨트롤러가 의존하는 경계(테스트 가능하게 인터페이스화).
 * 실제 데이터는 연결 세션(모델 수)·기여 로그(오늘 건수)·길드 정책(자동 승인)에서 결합한다.
 */
interface ProviderRosterInfo {
    /** 서버에 연결된 provider 별 제공 모델 수(연결 세션의 capability). 미연결이면 키 없음. */
    fun modelsByProvider(guildId: Long): Map<Long, Int>

    /** 서버의 provider 별 오늘 처리 건수(contribution_log, 자정 UTC 이후). */
    fun todayByProvider(guildId: Long): Map<Long, Long>

    fun isAutoApprove(guildId: Long): Boolean

    fun setAutoApprove(
        guildId: Long,
        value: Boolean,
        adminId: Long,
    )
}

@Component
class ProviderRosterInfoAdapter(
    private val registry: ConnectionRegistry,
    private val usage: UsageService,
    private val policy: PolicyService,
    private val clock: Clock = Clock.systemUTC(),
) : ProviderRosterInfo {
    override fun modelsByProvider(guildId: Long): Map<Long, Int> =
        registry.byGuild(guildId).associate { it.providerId to it.capability.models.size }

    override fun todayByProvider(guildId: Long): Map<Long, Long> = usage.providerContributionsSince(guildId, startOfTodayUtc())

    override fun isAutoApprove(guildId: Long): Boolean = policy.isAutoApprove(guildId)

    override fun setAutoApprove(
        guildId: Long,
        value: Boolean,
        adminId: Long,
    ) = policy.setAutoApprove(guildId, value, adminId)

    private fun startOfTodayUtc(): Instant =
        clock
            .instant()
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
}
