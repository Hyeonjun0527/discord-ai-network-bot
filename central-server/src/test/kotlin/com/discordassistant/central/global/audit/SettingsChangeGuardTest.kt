package com.discordassistant.central.global.audit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P17-T007: 설정 변경 optimistic lock·audit — stale 대시보드가 최신 설정을 덮어쓰지 못한다. */
class SettingsChangeGuardTest {
    private val guard = SettingsChangeGuard()

    @Test
    fun `matching version applies and produces before-after audit`() {
        val result =
            guard.applyOrReject(
                settingKey = "persona",
                actor = "admin_1",
                expectedVersion = 3,
                currentVersion = 3,
                before = "old",
                after = "new",
            )
        assertThat(result.newVersion).isEqualTo(4)
        assertThat(result.audit.before).isEqualTo("old")
        assertThat(result.audit.after).isEqualTo("new")
        assertThat(result.audit.fromVersion).isEqualTo(3)
        assertThat(result.audit.toVersion).isEqualTo(4)
        assertThat(result.audit.actor).isEqualTo("admin_1")
    }

    @Test
    fun `stale version is rejected (concurrent admin conflict)`() {
        // 클라이언트가 v2 를 들고 있는데 현재는 v5 → stale write 차단.
        assertThatThrownBy {
            guard.applyOrReject(
                settingKey = "channel_mode",
                actor = "admin_2",
                expectedVersion = 2,
                currentVersion = 5,
                before = "MEMBER",
                after = "OFF",
            )
        }.isInstanceOf(StaleSettingsWriteException::class.java)
    }

    @Test
    fun `rejection carries both versions for retry guidance`() {
        val ex =
            runCatching {
                guard.applyOrReject("k", "a", expectedVersion = 1, currentVersion = 9, before = 0, after = 1)
            }.exceptionOrNull() as StaleSettingsWriteException
        assertThat(ex.expectedVersion).isEqualTo(1)
        assertThat(ex.currentVersion).isEqualTo(9)
    }
}
