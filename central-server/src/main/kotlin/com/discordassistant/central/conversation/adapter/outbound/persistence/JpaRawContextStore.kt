package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.port.out.RawContextStorePort
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextAppendResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextBulkRedactionResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRedactionResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRetentionPolicy
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextUnavailableReason
import com.discordassistant.central.global.crypto.EncryptedStringConverter
import com.discordassistant.central.global.crypto.FieldCrypto
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Repository
class JpaRawContextStore(
    private val rows: NexaRawContextMessageRepository,
    @Value("\${nexa.raw-context.max-raw-chars-per-scope:300000}")
    maxRawCharsPerScope: Int = 300_000,
    private val clock: Clock = Clock.systemUTC(),
) : RawContextStorePort {
    private val retention = RawContextRetentionPolicy(maxRawCharsPerScope)

    @Transactional
    override fun append(entry: RawContextEntry): RawContextAppendResult {
        if (entry.content is RawContextContent.Available) {
            require(FieldCrypto.isConfigured()) { "raw context encryption key is not configured" }
        }
        retention.ensureFits(entry)

        val now = Instant.now(clock)
        val saved =
            rows
                .findByScopeAndMessage(entry.scope, entry.messageId)
                ?.apply { updateFrom(entry, now) }
                ?: entry.toEntity(now)
        rows.save(saved)

        val evicted = trimOldest(entry.scope)
        return RawContextAppendResult(readRecent(entry.scope), evicted)
    }

    @Transactional(readOnly = true)
    override fun readRecent(scope: RawContextScope): RawContextSnapshot =
        RawContextSnapshot(scope, rows.findByScope(scope).map { it.toDomain() })

    @Transactional
    override fun redact(
        scope: RawContextScope,
        messageId: Long,
        reason: RawContextUnavailableReason,
    ): RawContextRedactionResult {
        validateRedactionReason(reason)
        val existing = rows.findByScopeAndMessage(scope, messageId)
        if (existing != null) rows.delete(existing)
        return RawContextRedactionResult(readRecent(scope), removed = existing != null)
    }

    @Transactional
    override fun redactScope(
        scope: RawContextScope,
        reason: RawContextUnavailableReason,
    ): RawContextBulkRedactionResult {
        validateRedactionReason(reason)
        return deleteRows(rows.findByScope(scope))
    }

    @Transactional
    override fun redactChannel(
        guildId: Long,
        channelId: Long,
        reason: RawContextUnavailableReason,
    ): RawContextBulkRedactionResult {
        validateRawId("guildId", guildId)
        validateRawId("channelId", channelId)
        validateRedactionReason(reason)
        return deleteRows(rows.findByGuildIdAndChannelIdOrderByOccurredAtAscMessageIdAsc(guildId, channelId))
    }

    @Transactional
    override fun redactGuild(
        guildId: Long,
        reason: RawContextUnavailableReason,
    ): RawContextBulkRedactionResult {
        validateRawId("guildId", guildId)
        validateRedactionReason(reason)
        return deleteRows(rows.findByGuildIdOrderByOccurredAtAscMessageIdAsc(guildId))
    }

    @Transactional
    override fun redactAuthor(
        guildId: Long,
        authorPseudonym: String,
        reason: RawContextUnavailableReason,
    ): RawContextBulkRedactionResult {
        validateRawId("guildId", guildId)
        require(authorPseudonym.isNotBlank()) { "authorPseudonym 은 비어 있을 수 없다" }
        validateRedactionReason(reason)
        return deleteRows(rows.findByGuildIdAndAuthorPseudonymOrderByOccurredAtAscMessageIdAsc(guildId, authorPseudonym))
    }

    private fun trimOldest(scope: RawContextScope): List<Long> {
        val ordered = rows.findByScope(scope).toMutableList()
        val evicted = mutableListOf<Long>()
        var retainedRawChars = ordered.sumOf { it.contentLength }

        while (retainedRawChars > retention.maxRawChars && ordered.isNotEmpty()) {
            val oldest = ordered.removeAt(0)
            retainedRawChars -= oldest.contentLength
            evicted += oldest.messageId
            rows.delete(oldest)
        }

        return evicted
    }

    private fun deleteRows(existing: List<NexaRawContextMessageEntity>): RawContextBulkRedactionResult {
        if (existing.isNotEmpty()) rows.deleteAll(existing)
        return RawContextBulkRedactionResult(existing.size)
    }

    private fun validateRedactionReason(reason: RawContextUnavailableReason) {
        require(reason in REDACTION_REASONS) { "raw context redaction reason is not removable: ${reason.wireName}" }
    }

    private companion object {
        val REDACTION_REASONS =
            setOf(
                RawContextUnavailableReason.REDACTED,
                RawContextUnavailableReason.CONSENT_REVOKED,
                RawContextUnavailableReason.EVICTED,
            )
    }
}

@Entity
@Table(name = "nexa_raw_context_message")
class NexaRawContextMessageEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_id") var guildId: Long = 0,
    @Column(name = "channel_id") var channelId: Long = 0,
    @Column(name = "thread_id") var threadId: Long = 0,
    @Column(name = "message_id") var messageId: Long = 0,
    @Column(name = "author_pseudonym") var authorPseudonym: String = "",
    @Column(name = "occurred_at") var occurredAt: Instant = Instant.EPOCH,
    @Column(name = "reply_to_message_id") var replyToMessageId: Long? = null,
    @Column(name = "source_type") var sourceType: String = RawContextSourceType.HUMAN.name,
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "content_cipher")
    var contentCipher: String? = null,
    @Column(name = "content_unavailable_reason") var contentUnavailableReason: String? = null,
    @Column(name = "content_length") var contentLength: Int = 0,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "NexaRawContextMessageEntity(scope=$guildId:$channelId:$threadId, messageId=$messageId, " +
            "sourceType=$sourceType, contentLength=$contentLength)"
}

interface NexaRawContextMessageRepository : JpaRepository<NexaRawContextMessageEntity, Long> {
    fun findByGuildIdAndChannelIdAndThreadIdAndMessageId(
        guildId: Long,
        channelId: Long,
        threadId: Long,
        messageId: Long,
    ): NexaRawContextMessageEntity?

    fun findByGuildIdAndChannelIdAndThreadIdOrderByOccurredAtAscMessageIdAsc(
        guildId: Long,
        channelId: Long,
        threadId: Long,
    ): List<NexaRawContextMessageEntity>

    fun findByGuildIdAndChannelIdOrderByOccurredAtAscMessageIdAsc(
        guildId: Long,
        channelId: Long,
    ): List<NexaRawContextMessageEntity>

    fun findByGuildIdOrderByOccurredAtAscMessageIdAsc(guildId: Long): List<NexaRawContextMessageEntity>

    fun findByGuildIdAndAuthorPseudonymOrderByOccurredAtAscMessageIdAsc(
        guildId: Long,
        authorPseudonym: String,
    ): List<NexaRawContextMessageEntity>
}

private fun NexaRawContextMessageRepository.findByScopeAndMessage(
    scope: RawContextScope,
    messageId: Long,
): NexaRawContextMessageEntity? =
    findByGuildIdAndChannelIdAndThreadIdAndMessageId(
        guildId = scope.guildId,
        channelId = scope.channelId,
        threadId = scope.threadIdOrZero(),
        messageId = messageId,
    )

private fun NexaRawContextMessageRepository.findByScope(scope: RawContextScope): List<NexaRawContextMessageEntity> =
    findByGuildIdAndChannelIdAndThreadIdOrderByOccurredAtAscMessageIdAsc(
        guildId = scope.guildId,
        channelId = scope.channelId,
        threadId = scope.threadIdOrZero(),
    )

private fun RawContextEntry.toEntity(now: Instant): NexaRawContextMessageEntity =
    NexaRawContextMessageEntity(
        guildId = scope.guildId,
        channelId = scope.channelId,
        threadId = scope.threadIdOrZero(),
        messageId = messageId,
        authorPseudonym = authorPseudonym,
        occurredAt = occurredAt,
        replyToMessageId = replyToMessageId,
        sourceType = sourceType.name,
        contentCipher = availableTextOrNull(),
        contentUnavailableReason = unavailableReasonOrNull(),
        contentLength = contentLength,
        createdAt = now,
        updatedAt = now,
    )

private fun NexaRawContextMessageEntity.updateFrom(
    entry: RawContextEntry,
    now: Instant,
) {
    authorPseudonym = entry.authorPseudonym
    occurredAt = entry.occurredAt
    replyToMessageId = entry.replyToMessageId
    sourceType = entry.sourceType.name
    contentCipher = entry.availableTextOrNull()
    contentUnavailableReason = entry.unavailableReasonOrNull()
    contentLength = entry.contentLength
    updatedAt = now
}

private fun NexaRawContextMessageEntity.toDomain(): RawContextEntry =
    RawContextEntry(
        scope = RawContextScope(guildId = guildId, channelId = channelId, threadId = threadId.takeIf { it > 0 }),
        messageId = messageId,
        authorPseudonym = authorPseudonym,
        occurredAt = occurredAt,
        replyToMessageId = replyToMessageId,
        sourceType = RawContextSourceType.valueOf(sourceType),
        content =
            contentCipher?.let { RawContextContent.Available(it) }
                ?: RawContextContent.Unavailable(
                    RawContextUnavailableReason.entries.first { it.wireName == contentUnavailableReason },
                ),
    )

private fun RawContextEntry.availableTextOrNull(): String? =
    when (content) {
        is RawContextContent.Available -> content.text
        is RawContextContent.Unavailable -> null
    }

private fun RawContextEntry.unavailableReasonOrNull(): String? =
    when (content) {
        is RawContextContent.Available -> null
        is RawContextContent.Unavailable -> content.reason.wireName
    }

private fun RawContextScope.threadIdOrZero(): Long = threadId ?: 0L

private fun validateRawId(
    name: String,
    value: Long,
) {
    require(value > 0) { "$name 는 양수여야 한다: $value" }
}
