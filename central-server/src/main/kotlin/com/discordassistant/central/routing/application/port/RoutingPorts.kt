package com.discordassistant.central.routing.application.port

import com.discordassistant.central.routing.domain.model.AiRequestInput
import com.discordassistant.central.routing.domain.model.ProviderProfile
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.RequestState

/*
 * routing 애플리케이션 아웃바운드 포트(헥사고날). RequestOrchestrator 가 필요로 하는 외부 협력자를
 * 인터페이스로 정의하고, 구현은 각 도메인의 어댑터/서비스가 제공한다(테스트 디커플). 기본 no-op 구현으로
 * 단위 테스트에서 선택적으로 주입 생략 가능.
 *
 * 구현체: RoutingPolicy←PolicyService(guild), ProviderProfileProvider←DbProviderProfileProvider(routing.adapter),
 * BlocklistChecker←BlocklistService(provider→quota), QuotaChecker←QuotaService(usage),
 * ProviderSafetyChecker←ProviderSafetyService(network), UsageRecorder←UsageService(usage).
 */

/** 라우팅이 필요로 하는 정책 일부(테스트 디커플용). PolicyService 가 구현. */
interface RoutingPolicy {
    fun isChannelAllowed(
        guildId: Long,
        channelId: Long,
    ): Boolean

    fun maxAllowedBurden(
        guildId: Long,
        memberRoleIds: Collection<Long>,
    ): ModelBurden
}

interface ProviderProfileProvider {
    fun profile(providerId: Long): ProviderProfile

    fun profile(
        guildId: Long,
        providerId: Long,
    ): ProviderProfile = profile(providerId)

    /** 여러 프로바이더 프로필을 한 번에(라우팅 핫패스의 후보당 쿼리 N+1 방지). 기본은 개별 호출. */
    fun profilesFor(providerIds: Collection<Long>): Map<Long, ProviderProfile> = providerIds.associateWith { profile(it) }

    fun profilesFor(
        guildId: Long,
        providerIds: Collection<Long>,
    ): Map<Long, ProviderProfile> = providerIds.associateWith { profile(guildId, it) }
}

/** 차단 사용자 확인(차수 11). BlocklistService 가 구현. 기본은 차단 없음. */
interface BlocklistChecker {
    fun isBlocked(
        guildId: Long,
        userId: Long,
    ): Boolean
}

internal val ALLOW_ALL_BLOCKLIST =
    object : BlocklistChecker {
        override fun isBlocked(
            guildId: Long,
            userId: Long,
        ): Boolean = false
    }

/** 공정 사용 쿼터(차수 11). 오늘 사용량이 일일 상한을 넘었는지. 기본 무제한. */
interface QuotaChecker {
    fun exceededQuota(
        guildId: Long,
        userId: Long,
        roleIds: Set<Long>,
    ): Boolean
}

internal val UNLIMITED_QUOTA =
    object : QuotaChecker {
        override fun exceededQuota(
            guildId: Long,
            userId: Long,
            roleIds: Set<Long>,
        ): Boolean = false
    }

/** Provider 보호 상태 확인. 과부하/수신정지 Provider는 품질 라우팅보다 먼저 제외한다. */
interface ProviderSafetyChecker {
    fun isRoutingProtected(
        guildId: Long,
        providerUserId: Long,
    ): Boolean
}

internal val ALLOW_ALL_PROVIDER_SAFETY =
    object : ProviderSafetyChecker {
        override fun isRoutingProtected(
            guildId: Long,
            providerUserId: Long,
        ): Boolean = false
    }

/** 사용량/기여 기록 트리거. JPA 구현(UsageService) 또는 테스트 fake. */
interface UsageRecorder {
    fun recordSuccess(
        guildId: Long,
        userId: Long,
        providerId: Long,
        requestId: String,
    )

    /** AiRequest 종단 상태 영속화(차수 11). 기본 no-op(테스트 fake 영향 없음). */
    fun recordRequest(
        input: AiRequestInput,
        state: RequestState,
        providerId: Long?,
        failReason: String?,
        requestId: String? = null,
    ) {
    }

    /** 프로바이더 실패 기록(차수 11, ProviderHealth). 기본 no-op. */
    fun recordProviderFailure(providerId: Long) {}
}
