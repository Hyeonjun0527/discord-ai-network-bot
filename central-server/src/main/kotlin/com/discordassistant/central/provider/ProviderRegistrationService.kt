package com.discordassistant.central.provider

import com.discordassistant.central.domain.ProviderState
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** 프로바이더 등록 레코드(인메모리; JPA 영속화는 K-차수 6). */
data class ProviderRecord(
    val providerId: Long,
    val guildId: Long,
    var state: ProviderState,
)

/** 등록/승인 결과. 승인된 경우 일회용 토큰(평문)을 한 번만 돌려준다. */
data class JoinResult(
    val state: ProviderState,
    val token: String?,
)

/**
 * 프로바이더 등록/승인 라이프사이클 (K-차수 4, specs §16 프로바이더 등록).
 *
 * unregistered → pending → approved → … (online_* 은 세션 연결 시 K-차수 5 가 전이) / removed.
 * 승인 시 일회용 토큰을 발급한다(에이전트가 이 토큰으로 WS 인증).
 */
@Component
class ProviderRegistrationService(
    private val tokens: TokenService,
    private val audit: AuditLog,
) {
    private val providers = ConcurrentHashMap<Long, ProviderRecord>()

    /** 활성(미제거) 등록 여부. */
    private fun isActive(rec: ProviderRecord?): Boolean =
        rec != null && rec.state != ProviderState.REMOVED && rec.state != ProviderState.UNREGISTERED

    /** 프로바이더 참여 요청. autoApprove 면 즉시 승인+토큰, 아니면 PENDING. */
    fun requestJoin(
        providerId: Long,
        guildId: Long,
        autoApprove: Boolean,
    ): JoinResult {
        val existing = providers[providerId]
        if (isActive(existing)) {
            return JoinResult(existing!!.state, null) // 이미 등록/대기 중
        }
        return if (autoApprove) {
            providers[providerId] = ProviderRecord(providerId, guildId, ProviderState.APPROVED)
            val token = tokens.issue(providerId, guildId)
            audit.record("provider_join_auto", "provider:$providerId", "guild:$guildId")
            JoinResult(ProviderState.APPROVED, token)
        } else {
            providers[providerId] = ProviderRecord(providerId, guildId, ProviderState.PENDING)
            audit.record("provider_join_request", "provider:$providerId", "guild:$guildId")
            JoinResult(ProviderState.PENDING, null)
        }
    }

    /**
     * 이미 승인된 프로바이더에게 새 일회용 토큰을 발급한다(차수 19, OS 선택 재클릭/설치 명령 재요청 시).
     * 승인 상태가 아니면 null.
     */
    fun reissueToken(
        providerId: Long,
        guildId: Long,
    ): String? {
        val rec = providers[providerId] ?: return null
        return if (rec.state == ProviderState.APPROVED) tokens.issue(providerId, rec.guildId) else null
    }

    /** 관리자 승인(PENDING → APPROVED). 승인 토큰(평문) 반환, 실패 시 null. */
    fun approve(
        providerId: Long,
        adminId: Long,
    ): String? {
        val rec = providers[providerId] ?: return null
        if (rec.state != ProviderState.PENDING) return null
        rec.state = ProviderState.APPROVED
        val token = tokens.issue(providerId, rec.guildId)
        audit.record("provider_approve", "admin:$adminId", "provider:$providerId")
        return token
    }

    /** 등록 요청 거절(PENDING 제거). */
    fun reject(
        providerId: Long,
        adminId: Long,
    ): Boolean {
        val rec = providers[providerId] ?: return false
        if (rec.state != ProviderState.PENDING) return false
        providers.remove(providerId)
        audit.record("provider_reject", "admin:$adminId", "provider:$providerId")
        return true
    }

    /** 풀에서 제거(→ REMOVED). */
    fun remove(
        providerId: Long,
        adminId: Long,
    ): Boolean {
        val rec = providers[providerId] ?: return false
        rec.state = ProviderState.REMOVED
        audit.record("provider_remove", "admin:$adminId", "provider:$providerId")
        return true
    }

    /** 봇이 길드에서 제거되면 해당 길드에 묶인 등록/승인 상태를 모두 제거하고 토큰을 폐기한다. */
    fun removeGuild(guildId: Long): List<Long> {
        val removed =
            providers.values
                .filter { it.guildId == guildId }
                .map { it.providerId }
        removed.forEach { providers.remove(it) }
        val revoked = tokens.revokeGuild(guildId)
        audit.record("guild_provider_cleanup", "system", "guild:$guildId", "providers=${removed.size},tokens=$revoked")
        return removed
    }

    fun stateOf(providerId: Long): ProviderState? = providers[providerId]?.state

    /** 길드의 승인 대기(PENDING) 목록. */
    fun pending(guildId: Long): List<Long> =
        providers.values
            .filter { it.guildId == guildId && it.state == ProviderState.PENDING }
            .map { it.providerId }
}
