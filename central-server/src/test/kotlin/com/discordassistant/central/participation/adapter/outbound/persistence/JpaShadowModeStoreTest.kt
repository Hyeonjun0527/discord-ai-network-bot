package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant

/**
 * NEXA-P09-T007 shadow 단계 상태·audit persistence(Flyway V60) acceptance 단위 테스트(H2 Postgres 모드).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaShadowModeStore::class)
class JpaShadowModeStoreTest
    @Autowired
    constructor(
        val store: JpaShadowModeStore,
    ) {
        @Test
        fun `acceptance — 행이 없으면 기본값 OFF`() {
            assertThat(store.currentMode("g-unknown")).isEqualTo(ShadowMode.OFF)
        }

        @Test
        fun `전이를 적용하면 현재 단계가 갱신되고 audit 가 남는다`() {
            store.applyTransition(audit("g-1", ShadowMode.OFF, ShadowMode.OBSERVE_ONLY, "관찰 시작"))
            store.applyTransition(audit("g-1", ShadowMode.OBSERVE_ONLY, ShadowMode.SHADOW_PREDICT, "예측 수집"))

            assertThat(store.currentMode("g-1")).isEqualTo(ShadowMode.SHADOW_PREDICT)
            val trail = store.auditTrail("g-1")
            assertThat(trail).hasSize(2)
            assertThat(trail.first().to).isEqualTo(ShadowMode.SHADOW_PREDICT) // 최신 우선
        }

        @Test
        fun `listModes 가 모드 설정된 길드를 모두 돌려준다`() {
            store.applyTransition(audit("g-1", ShadowMode.OFF, ShadowMode.OBSERVE_ONLY, "x"))
            store.applyTransition(audit("g-2", ShadowMode.OFF, ShadowMode.SHADOW_PREDICT, "y"))
            assertThat(store.listModes().map { it.guildPseudonym }).containsExactlyInAnyOrder("g-1", "g-2")
        }

        private fun audit(
            guild: String,
            from: ShadowMode,
            to: ShadowMode,
            reason: String,
        ): ShadowModeAudit =
            ShadowModeAudit(
                guildPseudonym = guild,
                actorId = "op-1",
                from = from,
                to = to,
                reason = reason,
                enabledRealSend = false,
                at = Instant.now(),
            )
    }
