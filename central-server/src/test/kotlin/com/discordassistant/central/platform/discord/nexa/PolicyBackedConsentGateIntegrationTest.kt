package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.adapter.outbound.persistence.DbConsentPolicyAdapter
import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.global.privacy.ConsentRevokedException
import com.discordassistant.central.global.privacy.ProcessingStage
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * M7 consent boundary integration. The gate must read the DB-backed
 * [ConsentPolicyPort] at every risky stage instead of relying on a parallel
 * in-memory grant.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DbConsentPolicyAdapter::class)
class PolicyBackedConsentGateIntegrationTest
    @Autowired
    constructor(
        private val consentPolicy: ConsentPolicyPort,
        private val jdbc: JdbcTemplate,
    ) {
        @Test
        fun `observe only consent allows speech pipeline entry but blocks external glm request`() {
            seedGuild(guildId = 11_001L)
            seedChannel(guildId = 11_001L, channelId = 31_001L, observeAllowed = true, speakAllowed = false)
            val subject = PolicyBackedConsentGate.pseudonymOf(guildId = 11_001L, userId = 21_001L, channelId = 31_001L)
            val gate = PolicyBackedConsentGate(consentPolicy)

            assertThatCode { gate.checkAllowed(subject, ProcessingStage.SPEECH_GENERATION) }.doesNotThrowAnyException()
            assertThatThrownBy { gate.checkAllowed(subject, ProcessingStage.EXTERNAL_GLM_REQUEST) }
                .isInstanceOf(ConsentRevokedException::class.java)
        }

        @Test
        fun `observe and speak consent allows both generation and external glm request boundaries`() {
            seedGuild(guildId = 11_002L)
            seedChannel(guildId = 11_002L, channelId = 31_002L, observeAllowed = true, speakAllowed = true)
            val subject = PolicyBackedConsentGate.pseudonymOf(guildId = 11_002L, userId = 21_002L, channelId = 31_002L)
            val gate = PolicyBackedConsentGate(consentPolicy)

            assertThatCode { gate.checkAllowed(subject, ProcessingStage.SPEECH_GENERATION) }.doesNotThrowAnyException()
            assertThatCode { gate.checkAllowed(subject, ProcessingStage.EXTERNAL_GLM_REQUEST) }.doesNotThrowAnyException()
        }

        @Test
        fun `user opt out before pending action blocks scheduled send boundary`() {
            seedGuild(guildId = 11_003L)
            seedChannel(guildId = 11_003L, channelId = 31_003L, observeAllowed = true, speakAllowed = true)
            val subject = PolicyBackedConsentGate.pseudonymOf(guildId = 11_003L, userId = 21_003L, channelId = 31_003L)
            val gate = PolicyBackedConsentGate(consentPolicy)
            assertThatCode { gate.checkAllowed(subject, ProcessingStage.PENDING_ACTION) }.doesNotThrowAnyException()

            seedUserOptOut(guildId = 11_003L, userId = 21_003L)

            assertThatThrownBy { gate.checkAllowed(subject, ProcessingStage.PENDING_ACTION) }
                .isInstanceOf(ConsentRevokedException::class.java)
        }

        private fun seedGuild(guildId: Long) {
            jdbc.update(
                """
                INSERT INTO nexa_guild_consent(guild_id, enabled, learning_opt_in, speak_mode)
                VALUES (?, true, false, 'OFF')
                """.trimIndent(),
                guildId,
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
            guildId: Long,
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
    }
