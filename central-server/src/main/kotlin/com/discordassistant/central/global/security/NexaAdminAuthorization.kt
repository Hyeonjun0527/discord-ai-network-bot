package com.discordassistant.central.global.security

/**
 * NEXA 관리자 권한 검사기(NEXA-P17-T006, security·RBAC).
 *
 * 부여된 역할 집합에서 유효 권한을 합집합으로 계산하고, 행위별로 필요한 [NexaAdminPermission] 보유 여부를 검사한다.
 * 권한 없으면 [NexaAuthorizationException] 으로 fail-closed 거부한다(조용한 통과 금지). 모든 NEXA 관리 검사는
 * web/durable-token 경로에서만 적용된다(디스코드 명령 경로 없음 — guild-policy-boundary).
 *
 * 순수 로직: Spring/JPA/JDA 미참조. [DashboardActor] 등 web 신원과는 호출부(컨트롤러)에서 결합한다.
 */
class NexaAdminAuthorization(
    private val roles: Set<NexaAdminRole>,
) {
    /** 부여된 모든 역할의 권한 합집합. */
    val grantedPermissions: Set<NexaAdminPermission> =
        roles.flatMap { it.permissions }.toSet()

    /** [permission] 보유 여부. */
    fun has(permission: NexaAdminPermission): Boolean = permission in grantedPermissions

    /**
     * [permission] 이 없으면 [NexaAuthorizationException] 을 던진다(fail-closed). 호출 성공 = 권한 보유 보장.
     */
    fun requirePermission(permission: NexaAdminPermission) {
        if (!has(permission)) throw NexaAuthorizationException(permission)
    }

    companion object {
        /** Discord `MANAGE_SERVER` 관리자에게 기본 역할만 부여한 검사기(고위험 권한 0개). */
        fun forDiscordManager(): NexaAdminAuthorization = NexaAdminAuthorization(setOf(NexaAdminRole.DISCORD_MANAGER_DEFAULT))
    }
}

/** 필요한 권한 미보유 — fail-closed 거부. 메시지에 비밀을 담지 않는다(권한 enum 명만). */
class NexaAuthorizationException(
    val missing: NexaAdminPermission,
) : RuntimeException("NEXA 관리 권한 부족: ${missing.name} 필요")
