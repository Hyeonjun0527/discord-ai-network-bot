package com.discordassistant.central.actionruntime.persistence

import com.discordassistant.central.actionruntime.adapter.outbound.persistence.GuildKillSwitchAuditRepository
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.GuildKillSwitchStateRepository
import com.discordassistant.central.actionruntime.adapter.outbound.persistence.JpaGuildKillSwitchStore
import com.discordassistant.central.actionruntime.application.port.out.GuildKillSwitchAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Instant

/**
 * NEXA-P18-T013 — JpaGuildKillSwitchStore: 길드 kill switch 상태·audit(Flyway V67)를 H2 에서 검증.
 *
 * acceptance: 발동 즉시 activeKilledGuilds 에 반영되고(즉시 발효), 발동/해제 audit 가 append-only 로 남는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaGuildKillSwitchStoreTest
    @Autowired
    constructor(
        private val stateRepo: GuildKillSwitchStateRepository,
        private val auditRepo: GuildKillSwitchAuditRepository,
    ) {
        private val store = JpaGuildKillSwitchStore(stateRepo, auditRepo)
        private val t0 = Instant.parse("2026-06-22T00:00:00Z")

        @Test
        fun `engage immediately reflects in active set and writes audit`() {
            store.engage("g-1", actor = "op-7", reason = "over_talk", cancelledPending = 3, at = t0)

            assertThat(store.activeKilledGuilds()).containsExactly("g-1")
            val audit = store.auditFor("g-1")
            assertThat(audit).hasSize(1)
            assertThat(audit[0].action).isEqualTo(GuildKillSwitchAction.ENGAGE)
            assertThat(audit[0].reason).isEqualTo("over_talk")
            assertThat(audit[0].cancelledPending).isEqualTo(3)
        }

        @Test
        fun `disengage clears active state but keeps append-only audit`() {
            store.engage("g-1", actor = "op-7", reason = "model_error", cancelledPending = 0, at = t0)
            store.disengage("g-1", actor = "op-7", at = t0.plusSeconds(60))

            assertThat(store.activeKilledGuilds()).isEmpty() // 즉시 해제 반영.
            val audit = store.auditFor("g-1")
            // 발동·해제 둘 다 시간순으로 남는다(append-only).
            assertThat(audit.map { it.action }).containsExactly(
                GuildKillSwitchAction.ENGAGE,
                GuildKillSwitchAction.DISENGAGE,
            )
        }

        @Test
        fun `re-engage is idempotent on state (single active row per guild)`() {
            store.engage("g-1", actor = "op-7", reason = "a", cancelledPending = 0, at = t0)
            store.engage("g-1", actor = "op-7", reason = "b", cancelledPending = 0, at = t0.plusSeconds(1))

            assertThat(store.activeKilledGuilds()).containsExactly("g-1") // 상태는 1행.
            assertThat(store.auditFor("g-1")).hasSize(2) // audit 은 둘 다 남는다.
        }
    }
