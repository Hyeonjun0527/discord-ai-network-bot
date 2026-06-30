package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.domain.model.ConsentDecision
import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/**
 * M7 DB consent adapter integration tests. These use the real Flyway schema and
 * prove the production [ConsentPolicyPort] is DB-backed instead of the
 * fail-closed fallback when consent tables are available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DbConsentPolicyAdapter::class, FailClosedConsentPolicyConfig::class)
class DbConsentPolicyAdapterTest
    @Autowired
    constructor(
        private val consentPolicy: ConsentPolicyPort,
        private val consentPolicies: List<ConsentPolicyPort>,
        private val adapter: DbConsentPolicyAdapter,
        private val jdbc: JdbcTemplate,
    ) {
        @Test
        fun `empty consent tables fail closed and fallback bean is not registered beside db adapter`() {
            assertThat(consentPolicies).hasSize(1)

            val decision = consentPolicy.observationDecision(guildId = 10_001L, userId = 20_001L, channelId = 30_001L)

            assertThat(decision).isEqualTo(ConsentDecision.DENIED)
        }

        @Test
        fun `enabled guild and speaking channel returns observe and speak`() {
            seedGuild(guildId = 10_101L)
            seedChannel(guildId = 10_101L, channelId = 30_101L, observeAllowed = true, speakAllowed = true)

            val decision = consentPolicy.observationDecision(guildId = 10_101L, userId = 20_101L, channelId = 30_101L)

            assertThat(decision).isEqualTo(ConsentDecision.OBSERVE_AND_SPEAK)
        }

        @Test
        fun `enabled guild and observe only channel returns observe only`() {
            seedGuild(guildId = 10_102L)
            seedChannel(guildId = 10_102L, channelId = 30_102L, observeAllowed = true, speakAllowed = false)

            val decision = consentPolicy.observationDecision(guildId = 10_102L, userId = 20_102L, channelId = 30_102L)

            assertThat(decision).isEqualTo(ConsentDecision.OBSERVE_ONLY)
        }

        @Test
        fun `disabled guild missing channel observe denied and user opt out all deny`() {
            seedGuild(guildId = 10_201L, enabled = false)
            seedChannel(guildId = 10_201L, channelId = 30_201L, observeAllowed = true, speakAllowed = true)
            assertThat(consentPolicy.observationDecision(10_201L, 20_201L, 30_201L)).isEqualTo(ConsentDecision.DENIED)

            seedGuild(guildId = 10_202L)
            assertThat(consentPolicy.observationDecision(10_202L, 20_202L, 30_202L)).isEqualTo(ConsentDecision.DENIED)

            seedGuild(guildId = 10_203L)
            seedChannel(guildId = 10_203L, channelId = 30_203L, observeAllowed = false, speakAllowed = true)
            assertThat(consentPolicy.observationDecision(10_203L, 20_203L, 30_203L)).isEqualTo(ConsentDecision.DENIED)

            seedGuild(guildId = 10_204L)
            seedChannel(guildId = 10_204L, channelId = 30_204L, observeAllowed = true, speakAllowed = true)
            seedUserOptOut(guildId = null, userId = 20_204L)
            assertThat(consentPolicy.observationDecision(10_204L, 20_204L, 30_204L)).isEqualTo(ConsentDecision.DENIED)

            seedGuild(guildId = 10_205L)
            seedChannel(guildId = 10_205L, channelId = 30_205L, observeAllowed = true, speakAllowed = true)
            seedUserOptOut(guildId = 10_205L, userId = 20_205L)
            assertThat(consentPolicy.observationDecision(10_205L, 20_205L, 30_205L)).isEqualTo(ConsentDecision.DENIED)
        }

        @Test
        fun `participation channel exclusion denies otherwise active consent`() {
            seedGuild(guildId = 10_301L)
            seedChannel(guildId = 10_301L, channelId = 30_301L, observeAllowed = true, speakAllowed = true)
            seedChannelFlag(guildId = 10_301L, channelId = 30_301L, excluded = true)

            val decision = consentPolicy.observationDecision(guildId = 10_301L, userId = 20_301L, channelId = 30_301L)

            assertThat(decision).isEqualTo(ConsentDecision.DENIED)
        }

        @Test
        fun `health indicator reports db backed active status and fail closed fallback status`() {
            val dbBacked = NexaConsentPolicyHealthIndicator(listOf(adapter), devEnabled = false).health()
            assertThat(dbBacked.status).isEqualTo(Status.UP)
            assertThat(dbBacked.details).containsEntry("dbBackedConsentPolicyActive", true)
            assertThat(dbBacked.details).containsEntry("failClosedOnly", false)

            val productionFallbackOnly = NexaConsentPolicyHealthIndicator(emptyList(), devEnabled = false).health()
            assertThat(productionFallbackOnly.status).isEqualTo(Status.DOWN)
            assertThat(productionFallbackOnly.details).containsEntry("failClosedOnly", true)

            val devFallbackOnly = NexaConsentPolicyHealthIndicator(emptyList(), devEnabled = true).health()
            assertThat(devFallbackOnly.status).isEqualTo(Status.UP)
            assertThat(devFallbackOnly.details).containsEntry("devEnabled", true)
        }

        private fun seedGuild(
            guildId: Long,
            enabled: Boolean = true,
        ) {
            jdbc.update(
                """
                INSERT INTO nexa_guild_consent(guild_id, enabled, learning_opt_in, speak_mode)
                VALUES (?, ?, false, 'OFF')
                """.trimIndent(),
                guildId,
                enabled,
            )
        }

        private fun seedChannel(
            guildId: Long,
            channelId: Long,
            observeAllowed: Boolean,
            speakAllowed: Boolean,
        ) {
            jdbc.update(
                """
                INSERT INTO nexa_channel_scope(guild_id, channel_id, observe_allowed, speak_allowed)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                guildId,
                channelId,
                observeAllowed,
                speakAllowed,
            )
        }

        private fun seedUserOptOut(
            guildId: Long?,
            userId: Long,
        ) {
            jdbc.update(
                """
                INSERT INTO nexa_user_opt_out(guild_id, user_id, opted_out)
                VALUES (?, ?, true)
                """.trimIndent(),
                guildId,
                userId,
            )
        }

        private fun seedChannelFlag(
            guildId: Long,
            channelId: Long,
            excluded: Boolean,
        ) {
            jdbc.update(
                """
                INSERT INTO nexa_participation_channel_flag(guild_pseudonym, channel_id, lane, excluded, updated_at)
                VALUES (?, ?, null, ?, ?)
                """.trimIndent(),
                guildPseudonym(guildId),
                channelId,
                excluded,
                Instant.parse("2026-06-30T00:00:00Z"),
            )
        }

        private fun guildPseudonym(guildId: Long): String =
            ScopedPseudonymizer.pseudonymize(
                purpose = ScopedPseudonymizer.Purpose.MEMORY,
                guildId = guildId,
                snowflake = guildId,
            )
    }
