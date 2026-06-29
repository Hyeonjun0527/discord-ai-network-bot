package com.discordassistant.central.conversation.domain.model.rawcontext

import java.time.Instant

data class RawContextScope(
    val guildId: Long,
    val channelId: Long,
    val threadId: Long? = null,
) {
    init {
        require(guildId > 0) { "guildId 는 양수여야 한다: $guildId" }
        require(channelId > 0) { "channelId 는 양수여야 한다: $channelId" }
        threadId?.let { require(it > 0) { "threadId 는 양수여야 한다: $it" } }
    }

    val stableKey: String
        get() = listOfNotNull(guildId, channelId, threadId).joinToString(":")
}

data class RawContextEntry(
    val scope: RawContextScope,
    val messageId: Long,
    val authorPseudonym: String,
    val occurredAt: Instant,
    val replyToMessageId: Long?,
    val sourceType: RawContextSourceType,
    val content: RawContextContent,
) {
    init {
        require(messageId > 0) { "messageId 는 양수여야 한다: $messageId" }
        require(authorPseudonym.isNotBlank()) { "authorPseudonym 은 비어 있을 수 없다" }
        replyToMessageId?.let { require(it > 0) { "replyToMessageId 는 양수여야 한다: $it" } }
    }

    val contentLength: Int get() = content.rawCharLength
}

enum class RawContextSourceType {
    HUMAN,
    BOT,
    WEBHOOK,
    SYSTEM,
}

sealed interface RawContextContent {
    val rawCharLength: Int

    data class Available(
        val text: String,
    ) : RawContextContent {
        init {
            require(text.isNotEmpty()) { "raw context text 는 비어 있을 수 없다" }
        }

        override val rawCharLength: Int get() = text.length
    }

    data class Unavailable(
        val reason: RawContextUnavailableReason,
    ) : RawContextContent {
        override val rawCharLength: Int get() = 0
    }
}

enum class RawContextUnavailableReason(
    val wireName: String,
) {
    INTENT_MISSING("intent_missing"),
    EMPTY("empty"),
    REDACTED("redacted"),
    EVICTED("evicted"),
    CONSENT_REVOKED("consent_revoked"),
}

data class RawContextRetentionPolicy(
    val maxRawChars: Int,
) {
    init {
        require(maxRawChars > 0) { "maxRawChars 는 양수여야 한다: $maxRawChars" }
    }

    fun ensureFits(entry: RawContextEntry) {
        require(entry.contentLength <= maxRawChars) {
            "single raw context entry exceeds maxRawChars: messageId=${entry.messageId}, " +
                "contentLength=${entry.contentLength}, maxRawChars=$maxRawChars"
        }
    }
}

data class RawContextSnapshot(
    val scope: RawContextScope,
    val entries: List<RawContextEntry>,
) {
    val retainedRawChars: Int = entries.sumOf { it.contentLength }
}

data class RawContextAppendResult(
    val snapshot: RawContextSnapshot,
    val evictedMessageIds: List<Long>,
)

data class RawContextRedactionResult(
    val snapshot: RawContextSnapshot,
    val removed: Boolean,
)
