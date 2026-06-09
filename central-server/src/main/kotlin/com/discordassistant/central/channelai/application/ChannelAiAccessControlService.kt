package com.discordassistant.central.channelai.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.guild.adapter.outbound.persistence.AiAdminRoleEntity
import com.discordassistant.central.guild.adapter.outbound.persistence.AiAdminRoleRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * 채널 AI **접근 제어 + AI 관리자 역할** 협력자(SRP 분해, 감사 2026-06-09 SE-004).
 *
 * 하나의 책임 = "누가 채널 AI 를 관리할 수 있는가". AI-admin 역할 매칭(없으면 디스코드 관리자 폴백)으로
 * 권한을 판정하고, 그 역할 집합을 조회/교체한다. [ChannelAiCustomizationService] 의 write 메서드들이
 * 권한 게이트(requireCanManageChannelAi)로 이 협력자를 호출한다.
 *
 * **@Transactional 미부여(의도적)**: replaceAiAdminRoles 의 삭제+삽입과 거부 감사는 호출자(파사드의
 * @Transactional write)의 활성 TX 에 합류한다 — 추출 전 같은 빈 내부 호출과 원자성·락 의미가 1바이트도
 * 다르지 않다([CustomizationAuditRecorder]·[BehaviorVersionWriter] 와 동일한 추출 패턴).
 */
@Component
class ChannelAiAccessControlService(
    private val auditRecorder: CustomizationAuditRecorder,
    private val aiAdminRoles: AiAdminRoleRepository? = null,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun aiAdminRolePolicy(guildId: Long): AiAdminRolePolicy {
        featureGate.requireChannelAiEnabled()
        val roles = aiAdminRoleIds(guildId)
        return AiAdminRolePolicy(guildId = guildId, roleIds = roles, protectedMode = roles.isNotEmpty())
    }

    fun canManageChannelAi(
        guildId: Long,
        actorRoleIds: Collection<Long>,
        actorIsGuildAdmin: Boolean,
    ): AiAdminAccessDecision {
        val requiredRoles = aiAdminRoleIds(guildId)
        if (requiredRoles.isEmpty()) {
            return if (actorIsGuildAdmin) {
                AiAdminAccessDecision(true, "discord_admin_fallback", emptyList())
            } else {
                AiAdminAccessDecision(false, "discord_admin_required", emptyList())
            }
        }
        val actorRoles = actorRoleIds.toSet()
        val matched = requiredRoles.filter { it in actorRoles }
        return if (matched.isNotEmpty()) {
            AiAdminAccessDecision(true, "ai_admin_role_matched", requiredRoles, matched)
        } else {
            AiAdminAccessDecision(false, "ai_admin_role_required", requiredRoles)
        }
    }

    fun requireCanManageChannelAi(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        actorRoleIds: Collection<Long>,
        actorIsGuildAdmin: Boolean,
        action: String,
    ): AiAdminAccessDecision {
        val decision = canManageChannelAi(guildId, actorRoleIds, actorIsGuildAdmin)
        if (decision.allowed) return decision
        auditRecorder.audit(
            guildId = guildId,
            channelId = channelId,
            actorUserId = actorUserId,
            action = "ai_admin_denied",
            targetType = "channel_ai_permission",
            targetId = null,
            summary = "$action denied: ${decision.reason}",
        )
        throw IllegalStateException(decision.userMessage())
    }

    fun replaceAiAdminRoles(
        guildId: Long,
        roleIds: Collection<Long>,
        actorUserId: Long?,
        actorRoleIds: Collection<Long> = emptyList(),
        actorIsGuildAdmin: Boolean = true,
    ): AiAdminRolePolicy {
        featureGate.requireChannelAiEnabled()
        requireCanManageChannelAi(guildId, 0, actorUserId, actorRoleIds, actorIsGuildAdmin, "replace_ai_admin_roles")
        val now = Instant.now(clock)
        val normalized = roleIds.filter { it > 0 }.distinct().sorted()
        aiAdminRoles?.deleteByGuildId(guildId)
        normalized.forEach { roleId ->
            aiAdminRoles?.save(
                AiAdminRoleEntity(
                    guildId = guildId,
                    roleId = roleId,
                    createdBy = actorUserId,
                    createdAt = now,
                ),
            )
        }
        auditRecorder.audit(
            guildId = guildId,
            channelId = 0,
            actorUserId = actorUserId,
            action = "replace_ai_admin_roles",
            targetType = "ai_admin_role",
            targetId = null,
            summary = "roles=${normalized.joinToString(",").ifBlank { "fallback_to_discord_admin" }}",
        )
        return AiAdminRolePolicy(guildId = guildId, roleIds = normalized, protectedMode = normalized.isNotEmpty())
    }

    private fun aiAdminRoleIds(guildId: Long): List<Long> =
        aiAdminRoles
            ?.findByGuildId(guildId)
            ?.map { it.roleId }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
}
