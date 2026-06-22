package com.discordassistant.central.global.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P17-T006: NEXA 관리자 RBAC — 단일 Discord 관리자에 모든 고위험 권한 자동 부여 금지. */
class NexaAdminAuthorizationTest {
    @Test
    fun `no single role holds all high-risk permissions`() {
        // acceptance: 어떤 단일 역할도 4개 고위험 권한 전부를 갖지 않는다.
        NexaAdminRole.entries.forEach { role ->
            assertThat(role.permissions.containsAll(NexaAdminPermission.HIGH_RISK))
                .`as`("role ${role.name} must not hold all high-risk permissions")
                .isFalse()
        }
    }

    @Test
    fun `discord manager default has no high-risk permission`() {
        val auth = NexaAdminAuthorization.forDiscordManager()
        NexaAdminPermission.HIGH_RISK.forEach { p ->
            assertThat(auth.has(p)).`as`("default discord manager must not auto-hold $p").isFalse()
        }
        // 설정까지는 가능(OPERATOR).
        assertThat(auth.has(NexaAdminPermission.VIEW_SETTINGS)).isTrue()
        assertThat(auth.has(NexaAdminPermission.EDIT_SETTINGS)).isTrue()
    }

    @Test
    fun `requirePermission fails closed when permission missing`() {
        val viewer = NexaAdminAuthorization(setOf(NexaAdminRole.VIEWER))
        assertThatThrownBy { viewer.requirePermission(NexaAdminPermission.DELETE_DATA) }
            .isInstanceOf(NexaAuthorizationException::class.java)
    }

    @Test
    fun `data officer can export and delete but not toggle live or approve model`() {
        val officer = NexaAdminAuthorization(setOf(NexaAdminRole.DATA_OFFICER))
        assertThat(officer.has(NexaAdminPermission.EXPORT_DATA)).isTrue()
        assertThat(officer.has(NexaAdminPermission.DELETE_DATA)).isTrue()
        assertThat(officer.has(NexaAdminPermission.TOGGLE_LIVE)).isFalse()
        assertThat(officer.has(NexaAdminPermission.APPROVE_MODEL)).isFalse()
    }

    @Test
    fun `permissions are the union of granted roles`() {
        val combo = NexaAdminAuthorization(setOf(NexaAdminRole.LIVE_OPERATOR, NexaAdminRole.MODEL_APPROVER))
        assertThat(combo.has(NexaAdminPermission.TOGGLE_LIVE)).isTrue()
        assertThat(combo.has(NexaAdminPermission.APPROVE_MODEL)).isTrue()
        // 명시적으로 합쳐야만 여러 고위험 권한을 갖는다(자동 부여 아님).
        assertThat(combo.has(NexaAdminPermission.DELETE_DATA)).isFalse()
    }
}
