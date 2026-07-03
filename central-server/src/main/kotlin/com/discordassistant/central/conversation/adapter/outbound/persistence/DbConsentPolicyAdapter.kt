package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.domain.model.ConsentDecision
import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class DbConsentPolicyAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : ConsentPolicyPort {
    @Transactional(readOnly = true)
    override fun observationDecision(
        guildId: Long,
        userId: Long,
        channelId: Long,
    ): ConsentDecision {
        if (!guildEnabled(guildId)) return ConsentDecision.DENIED
        if (userId > 0 && userOptedOut(guildId, userId)) return ConsentDecision.DENIED
        if (channelExcluded(guildId, channelId)) return ConsentDecision.DENIED
        val channel = channelScope(guildId, channelId) ?: return ConsentDecision.DENIED
        if (!channel.observeAllowed) return ConsentDecision.DENIED
        return if (channel.speakAllowed) ConsentDecision.OBSERVE_AND_SPEAK else ConsentDecision.OBSERVE_ONLY
    }

    private fun guildEnabled(guildId: Long): Boolean =
        jdbc.queryForNullableBoolean(
            """
            SELECT enabled
            FROM nexa_guild_consent
            WHERE guild_id = :guildId
            """.trimIndent(),
            params("guildId" to guildId),
        ) ?: false

    private fun userOptedOut(
        guildId: Long,
        userId: Long,
    ): Boolean =
        jdbc.queryForRequiredLong(
            """
            SELECT COUNT(1)
            FROM nexa_user_opt_out
            WHERE user_id = :userId
              AND opted_out = TRUE
              AND (guild_id = :guildId OR guild_id IS NULL)
            """.trimIndent(),
            params("guildId" to guildId, "userId" to userId),
        ) > 0

    private fun channelExcluded(
        guildId: Long,
        channelId: Long,
    ): Boolean =
        jdbc.queryForRequiredLong(
            """
            SELECT COUNT(1)
            FROM nexa_participation_channel_flag
            WHERE guild_pseudonym = :guildPseudonym
              AND channel_id = :channelId
              AND excluded = TRUE
            """.trimIndent(),
            params(
                "guildPseudonym" to guildPseudonym(guildId),
                "channelId" to channelId,
            ),
        ) > 0

    private fun channelScope(
        guildId: Long,
        channelId: Long,
    ): ChannelConsentScope? =
        jdbc
            .query(
                """
                SELECT observe_allowed, speak_allowed
                FROM nexa_channel_scope
                WHERE guild_id = :guildId
                  AND channel_id = :channelId
                """.trimIndent(),
                params("guildId" to guildId, "channelId" to channelId),
            ) { rs, _ ->
                ChannelConsentScope(
                    observeAllowed = rs.getBoolean("observe_allowed"),
                    speakAllowed = rs.getBoolean("speak_allowed"),
                )
            }.firstOrNull()

    private fun guildPseudonym(guildId: Long): String =
        ScopedPseudonymizer.pseudonymize(
            purpose = ScopedPseudonymizer.Purpose.MEMORY,
            guildId = guildId,
            snowflake = guildId,
        )
}

@Component("nexaConsentPolicy")
class NexaConsentPolicyHealthIndicator(
    private val dbAdapters: List<DbConsentPolicyAdapter>,
    @param:Value("\${central.dev.enabled:false}") private val devEnabled: Boolean,
) : HealthIndicator {
    override fun health(): Health {
        val dbBacked = dbAdapters.isNotEmpty()
        val builder = if (dbBacked || devEnabled) Health.up() else Health.down()
        return builder
            .withDetail("dbBackedConsentPolicyActive", dbBacked)
            .withDetail("failClosedOnly", !dbBacked)
            .withDetail("devEnabled", devEnabled)
            .build()
    }
}

private data class ChannelConsentScope(
    val observeAllowed: Boolean,
    val speakAllowed: Boolean,
)

private fun params(vararg pairs: Pair<String, Any?>): MapSqlParameterSource =
    MapSqlParameterSource().apply {
        pairs.forEach { (key, value) -> addValue(key, value) }
    }

private fun NamedParameterJdbcTemplate.queryForNullableBoolean(
    sql: String,
    params: MapSqlParameterSource,
): Boolean? =
    query(sql, params) { rs, _ -> rs.getBoolean(1) }
        .firstOrNull()

private fun NamedParameterJdbcTemplate.queryForRequiredLong(
    sql: String,
    params: MapSqlParameterSource,
): Long =
    query(sql, params) { rs, _ -> rs.getLong(1) }
        .firstOrNull() ?: 0L
