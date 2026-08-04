package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.participation.application.catchup.NiaCatchUpMessage
import com.discordassistant.central.participation.application.catchup.NiaCatchUpScope
import com.discordassistant.central.participation.application.catchup.NiaCatchUpState
import com.discordassistant.central.participation.application.catchup.NiaJudgeCadenceMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaNiaCatchUpStateStoreTest
    @Autowired
    constructor(
        private val rows: NiaCatchUpStateRepository,
        private val jdbc: JdbcTemplate,
    ) {
        private val now = Instant.parse("2026-08-02T00:00:00Z")
        private val clock = MutableClock(now)
        private val store = JpaNiaCatchUpStateStore(rows, clock)
        private val scope = NiaCatchUpScope(guildId = 71, channelId = 72)

        @BeforeEach
        fun setUp() {
            FieldCrypto.configure("catch-up-test-key")
        }

        @AfterEach
        fun tearDown() {
            FieldCrypto.configure(null)
        }

        @Test
        fun `due 상태는 user routing metadata를 암호화하고 lease token과 함께 한 번만 claim한다`() {
            val saved =
                store.save(
                    NiaCatchUpState(
                        scope = scope,
                        mode = NiaJudgeCadenceMode.CATCH_UP,
                        consecutiveIgnoreCount = 10,
                        latestMessage = message(900),
                        nextCatchUpAt = now,
                    ),
                )

            val cipher =
                jdbc.queryForObject(
                    "SELECT latest_user_id_cipher FROM nexa_channel_judge_state WHERE id = ?",
                    String::class.java,
                    saved.id,
                )
            assertThat(cipher).startsWith("enc1:").isNotEqualTo("901")

            val claim = store.claimDue(now, leaseOwner = "test-worker", leaseExpiresAt = now.plusSeconds(30), limit = 10).single()

            assertThat(claim.target?.messageId).isEqualTo(900)
            assertThat(claim.target?.userId).isEqualTo(901)
            assertThat(store.lockClaim(claim)?.leaseOwner).isEqualTo("test-worker")
            assertThat(claim.leaseToken).isNotBlank()
            assertThat(store.claimDue(now, leaseOwner = "other-worker", leaseExpiresAt = now.plusSeconds(30), limit = 10)).isEmpty()
        }

        @Test
        fun `만료 뒤 다시 claim되면 같은 worker id의 이전 lease token은 거절된다`() {
            store.save(
                NiaCatchUpState(
                    scope = scope,
                    mode = NiaJudgeCadenceMode.CATCH_UP,
                    latestMessage = message(900),
                    nextCatchUpAt = now,
                ),
            )

            val stale = store.claimDue(now, leaseOwner = "shared-worker", leaseExpiresAt = now.plusSeconds(30), limit = 1).single()
            clock.advanceSeconds(31)
            val current =
                store
                    .claimDue(clock.instant(), leaseOwner = "shared-worker", leaseExpiresAt = clock.instant().plusSeconds(30), limit = 1)
                    .single()

            assertThat(current.leaseToken).isNotEqualTo(stale.leaseToken)
            assertThat(store.lockClaim(stale)).isNull()
            assertThat(store.lockClaim(current)).isNotNull()
        }

        private fun message(id: Long): NiaCatchUpMessage =
            NiaCatchUpMessage(
                scope = scope,
                messageId = id,
                userId = id + 1,
                replyToMessageId = null,
                occurredAt = now,
                mentioned = false,
                replyToNia = false,
            )
    }

private class MutableClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
