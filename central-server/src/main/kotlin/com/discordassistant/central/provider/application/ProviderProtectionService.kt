package com.discordassistant.central.provider.application

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.relay.ConnectionRegistry
import org.springframework.stereotype.Service

/**
 * 프로바이더 보호 명령 (K-차수 12, specs §9). pause/resume/leave 는 기본적으로 providerId+guildId 로 수행한다.
 * 자동 보호(부하/배터리/반복 실패)는 ProviderSession 이 상태 보고/실패에서 직접 처리한다.
 */
@Service
class ProviderProtectionService(
    private val registry: ConnectionRegistry,
    private val audit: AuditLog,
) {
    /** 일시정지(요청 수신 중단). */
    fun pause(
        providerId: Long,
        guildId: Long,
    ): Boolean {
        val ok = registry.byProvider(guildId, providerId)?.pause() ?: false
        if (ok) audit.record("provider_pause", "provider:$providerId", "provider:$providerId", "guild:$guildId")
        return ok
    }

    /** 기존 호출 보호용: 활성 세션이 하나일 때만 일시정지. */
    fun pause(providerId: Long): Boolean {
        val s = registry.byProvider(providerId) ?: return false
        return pause(providerId, s.guildId ?: return false)
    }

    /** 재개. */
    fun resume(
        providerId: Long,
        guildId: Long,
    ): Boolean {
        val ok = registry.byProvider(guildId, providerId)?.resume() ?: false
        if (ok) audit.record("provider_resume", "provider:$providerId", "provider:$providerId", "guild:$guildId")
        return ok
    }

    /** 기존 호출 보호용: 활성 세션이 하나일 때만 재개. */
    fun resume(providerId: Long): Boolean {
        val s = registry.byProvider(providerId) ?: return false
        return resume(providerId, s.guildId ?: return false)
    }

    /** 풀 이탈(연결 종료 + 해제). 서버별 이탈. */
    fun leave(
        providerId: Long,
        guildId: Long,
    ): Boolean {
        val s = registry.byProvider(guildId, providerId) ?: return false
        s.closeAndFailPending("provider leave")
        registry.unregister(s)
        s.connection.close("provider leave")
        audit.record("provider_leave", "provider:$providerId", "provider:$providerId", "guild:$guildId")
        return true
    }

    /** 기존 호출 보호용: 활성 세션이 하나일 때만 이탈. */
    fun leave(providerId: Long): Boolean {
        val s = registry.byProvider(providerId) ?: return false
        return leave(providerId, s.guildId ?: return false)
    }
}
