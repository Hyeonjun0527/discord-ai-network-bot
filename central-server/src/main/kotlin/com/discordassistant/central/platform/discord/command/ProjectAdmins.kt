package com.discordassistant.central.platform.discord.command

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 프로젝트 관리자(운영자) allow-list — `central.dashboard.admin-user-ids` 와 동일한 설정을 Discord 명령 계층에서도
 * 쓰기 위한 SSOT. MeController·AiNetworkApiSecurityFilter 가 웹/대시보드에서 쓰는 것과 같은 콤마구분 Discord
 * userId 셋이다. 서버 관리자(MANAGE_SERVER)와는 별개 — 프로젝트 운영자 본인만 통과한다.
 */
@Component
class ProjectAdmins(
    @param:Value("\${central.dashboard.admin-user-ids:}") raw: String,
) {
    private val adminUserIds: Set<String> =
        raw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun isAdmin(userId: Long): Boolean = userId.toString() in adminUserIds
}
