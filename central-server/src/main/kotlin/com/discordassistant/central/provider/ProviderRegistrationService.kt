package com.discordassistant.central.provider

import com.discordassistant.central.domain.ProviderState
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** 프로바이더 등록 레코드(인메모리; JPA 영속화는 K-차수 6). */
data class ProviderRecord(
    val providerId: Long,
    val guildId: Long,
    var state: ProviderState,
) {
    /** 상태머신 가드를 거쳐 전이한다(불가 전이는 false, 상태 불변). 등록/관리 경로 일관성. */
    fun transitionTo(next: ProviderState): Boolean {
        if (!state.canTransitionTo(next)) return false
        state = next
        return true
    }
}

/** 등록/승인 결과. 승인된 경우 일회용 토큰(평문)을 한 번만 돌려준다. */
data class JoinResult(
    val state: ProviderState,
    val token: String?,
)

private data class ProviderGuildKey(
    val providerId: Long,
    val guildId: Long,
)

/**
 * 프로바이더 등록/승인 라이프사이클 (K-차수 4, specs §16 프로바이더 등록).
 *
 * 등록 단위는 반드시 `(providerId, guildId)` 이다. 같은 사용자가 여러 서버에 참여해도 서버별 승인/토큰/제거가
 * 섞이지 않는다. 승인 시 일회용 토큰을 발급한다(에이전트가 이 토큰으로 WS 인증).
 */
@Component
class ProviderRegistrationService(
    private val tokens: TokenService,
    private val audit: AuditLog,
) {
    private val providers = ConcurrentHashMap<ProviderGuildKey, ProviderRecord>()

    /** 활성(미제거) 등록 여부. */
    private fun isActive(rec: ProviderRecord?): Boolean =
        rec != null && rec.state != ProviderState.REMOVED && rec.state != ProviderState.UNREGISTERED

    /** 프로바이더 참여 요청. autoApprove 면 즉시 승인+토큰, 아니면 PENDING. */
    fun requestJoin(
        providerId: Long,
        guildId: Long,
        autoApprove: Boolean,
    ): JoinResult {
        val key = ProviderGuildKey(providerId, guildId)
        val existing = providers[key]
        if (isActive(existing)) {
            return JoinResult(existing!!.state, null) // 이미 해당 서버에 등록/대기 중
        }
        return if (autoApprove) {
            providers[key] = ProviderRecord(providerId, guildId, ProviderState.APPROVED)
            val token = tokens.issue(providerId, guildId)
            audit.record("provider_join_auto", "provider:$providerId", "guild:$guildId")
            JoinResult(ProviderState.APPROVED, token)
        } else {
            providers[key] = ProviderRecord(providerId, guildId, ProviderState.PENDING)
            audit.record("provider_join_request", "provider:$providerId", "guild:$guildId")
            JoinResult(ProviderState.PENDING, null)
        }
    }

    /**
     * 이미 승인된 프로바이더에게 새 일회용 토큰을 발급한다(차수 19, OS 선택 재클릭/설치 명령 재요청 시).
     * 요청한 길드의 승인 상태가 아니면 null.
     */
    fun reissueToken(
        providerId: Long,
        guildId: Long,
    ): String? {
        val rec = providers[ProviderGuildKey(providerId, guildId)] ?: return null
        return if (rec.state == ProviderState.APPROVED) tokens.issue(providerId, guildId) else null
    }

    /**
     * 웹 ‘토큰 받기’(OAuth 온보딩): 이미 활성 등록이면 새 일회용 토큰을 발급한다. 이미 연결한 적
     * 있는(ONLINE/OFFLINE 등) 프로바이더가 재설치·재페어링할 때도 토큰을 받을 수 있게 한다.
     * 승인 대기(PENDING)·제거됨(REMOVED)·미등록은 null(먼저 requestJoin 필요).
     */
    fun issueOnboardingToken(
        providerId: Long,
        guildId: Long,
    ): String? {
        val rec = providers[ProviderGuildKey(providerId, guildId)] ?: return null
        return if (rec.state != ProviderState.PENDING && rec.state != ProviderState.REMOVED) {
            tokens.issue(providerId, guildId)
        } else {
            null
        }
    }

    /** 관리자 승인(PENDING → APPROVED). 승인 토큰(평문) 반환, 실패 시 null. */
    fun approve(
        providerId: Long,
        guildId: Long,
        adminId: Long,
    ): String? {
        val rec = providers[ProviderGuildKey(providerId, guildId)] ?: return null
        if (rec.state != ProviderState.PENDING) return null
        if (!rec.transitionTo(ProviderState.APPROVED)) return null // 상태머신 가드(PENDING→APPROVED 허용)
        val token = tokens.issue(providerId, guildId)
        audit.record("provider_approve", "admin:$adminId", "provider:$providerId", "guild:$guildId")
        return token
    }

    /** 기존 호출 보호용: 단일 PENDING 이 명확할 때만 승인한다. */
    fun approve(
        providerId: Long,
        adminId: Long,
    ): String? {
        val pending = providers.values.filter { it.providerId == providerId && it.state == ProviderState.PENDING }
        return pending.singleOrNull()?.let { approve(providerId, it.guildId, adminId) }
    }

    /** 등록 요청 거절(PENDING 제거). */
    fun reject(
        providerId: Long,
        guildId: Long,
        adminId: Long,
    ): Boolean {
        val key = ProviderGuildKey(providerId, guildId)
        val rec = providers[key] ?: return false
        if (rec.state != ProviderState.PENDING) return false
        providers.remove(key)
        tokens.revokeProviderGuild(providerId, guildId)
        tokens.revokeDurable(providerId, guildId)
        audit.record("provider_reject", "admin:$adminId", "provider:$providerId", "guild:$guildId")
        return true
    }

    /** 기존 호출 보호용: 단일 PENDING 이 명확할 때만 거절한다. */
    fun reject(
        providerId: Long,
        adminId: Long,
    ): Boolean {
        val pending = providers.values.filter { it.providerId == providerId && it.state == ProviderState.PENDING }
        return pending.singleOrNull()?.let { reject(providerId, it.guildId, adminId) } ?: false
    }

    /** 풀에서 제거(→ REMOVED). 서버별 제거. */
    fun remove(
        providerId: Long,
        guildId: Long,
        adminId: Long,
    ): Boolean {
        val rec = providers[ProviderGuildKey(providerId, guildId)] ?: return false
        rec.transitionTo(ProviderState.REMOVED) // 상태머신 가드(이미 REMOVED 면 no-op). 폐기는 멱등하게 진행.
        tokens.revokeProviderGuild(providerId, guildId)
        tokens.revokeDurable(providerId, guildId)
        audit.record("provider_remove", "admin:$adminId", "provider:$providerId", "guild:$guildId")
        return true
    }

    /** 기존 호출 보호용: 단일 등록이 명확할 때만 제거한다. */
    fun remove(
        providerId: Long,
        adminId: Long,
    ): Boolean {
        val matches = providers.values.filter { it.providerId == providerId && isActive(it) }
        return matches.singleOrNull()?.let { remove(providerId, it.guildId, adminId) } ?: false
    }

    /** 봇이 길드에서 제거되면 해당 길드에 묶인 등록/승인 상태를 모두 제거하고 토큰을 폐기한다. */
    fun removeGuild(guildId: Long): List<Long> {
        val removedRecords = providers.entries.filter { it.value.guildId == guildId }
        val removed = removedRecords.map { it.value.providerId }
        removedRecords.forEach { providers.remove(it.key) }
        val revoked = tokens.revokeGuild(guildId)
        removed.forEach { tokens.revokeDurable(it, guildId) }
        audit.record("guild_provider_cleanup", "system", "guild:$guildId", "providers=${removed.size},tokens=$revoked")
        return removed
    }

    /** 멤버가 서버를 나가면 해당 서버의 프로바이더 등록만 제거한다. */
    fun removeMemberFromGuild(
        providerId: Long,
        guildId: Long,
    ): Boolean {
        val key = ProviderGuildKey(providerId, guildId)
        val removed = providers.remove(key) != null
        val revoked = tokens.revokeProviderGuild(providerId, guildId)
        tokens.revokeDurable(providerId, guildId)
        if (removed || revoked > 0) {
            audit.record("guild_member_provider_cleanup", "system", "provider:$providerId", "guild:$guildId,tokens=$revoked")
        }
        return removed || revoked > 0
    }

    fun stateOf(
        providerId: Long,
        guildId: Long,
    ): ProviderState? = providers[ProviderGuildKey(providerId, guildId)]?.state

    /** 기존 테스트/호출 보호용: 단일 등록이 명확하면 반환, 아니면 null. */
    fun stateOf(providerId: Long): ProviderState? {
        val matches = providers.values.filter { it.providerId == providerId }
        return matches.singleOrNull()?.state
    }

    /** 길드의 승인 대기(PENDING) 목록. */
    fun pending(guildId: Long): List<Long> =
        providers.values
            .filter { it.guildId == guildId && it.state == ProviderState.PENDING }
            .map { it.providerId }

    fun providersInGuild(guildId: Long): List<Long> =
        providers.values
            .filter { it.guildId == guildId && isActive(it) }
            .map { it.providerId }
}
