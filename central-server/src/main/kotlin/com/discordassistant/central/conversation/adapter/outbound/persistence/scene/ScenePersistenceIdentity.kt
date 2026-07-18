package com.discordassistant.central.conversation.adapter.outbound.persistence.scene

import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Discord snowflake를 장면 projection 전용 양수 Long 가명으로 바꿔 raw ID 영속화를 막는다. */
internal object ScenePersistenceIdentity {
    fun guild(rawGuildId: Long): Long = pseudonymousLong("guild", rawGuildId)

    fun channel(rawChannelId: Long): Long = pseudonymousLong("channel", rawChannelId)

    fun observation(rawReference: String): String {
        require(rawReference.isNotBlank()) { "scene observation reference must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256").digest(rawReference.toByteArray(Charsets.UTF_8))
        val numericRef = (ByteBuffer.wrap(digest).long and Long.MAX_VALUE).coerceAtLeast(1L)
        return "scene_observation:" +
            ScopedPseudonymizer.pseudonymize(
                purpose = ScopedPseudonymizer.Purpose.MEMORY,
                guildId = COMMON_SCOPE,
                snowflake = numericRef,
                keyVersion = STABLE_KEY_VERSION,
            )
    }

    private fun pseudonymousLong(
        kind: String,
        rawId: Long,
    ): Long {
        require(rawId > 0) { "scene persistence identity source must be positive" }
        val scoped =
            ScopedPseudonymizer.pseudonymize(
                purpose = ScopedPseudonymizer.Purpose.MEMORY,
                guildId = COMMON_SCOPE,
                snowflake = rawId,
                keyVersion = STABLE_KEY_VERSION,
            )
        val digest = MessageDigest.getInstance("SHA-256").digest("$kind\u0000$scoped".toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(digest).long and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private const val COMMON_SCOPE = 0L
    private const val STABLE_KEY_VERSION = 1
}
